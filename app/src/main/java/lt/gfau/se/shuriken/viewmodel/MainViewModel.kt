package lt.gfau.se.shuriken.viewmodel

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import lt.gfau.se.shuriken.model.LocationData
import lt.gfau.se.shuriken.model.SerialDevicePort
import lt.gfau.se.shuriken.service.NmeaService
import lt.gfau.se.shuriken.serial.UsbSerialManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // USB reads arrive in arbitrarily sized chunks. Cap retained Console
        // text by characters rather than chunk count so rendering stays bounded.
        private const val SERIAL_LOG_MAX_CHARS = 64 * 1024
    }

    sealed class Event {
        object ShowDeviceSelection : Event()
    }

    private val _connectionState = MutableStateFlow(UsbSerialManager.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<UsbSerialManager.ConnectionState> = _connectionState.asStateFlow()

    private val _locationData = MutableStateFlow<LocationData?>(null)
    val locationData: StateFlow<LocationData?> = _locationData.asStateFlow()

    private val _locationSource = MutableStateFlow("none")
    val locationSource: StateFlow<String> = _locationSource.asStateFlow()

    private val _availablePorts = MutableStateFlow<List<SerialDevicePort>>(emptyList())
    val availablePorts: StateFlow<List<SerialDevicePort>> = _availablePorts.asStateFlow()

    private val _connectedPortLabel = MutableStateFlow("")
    val connectedPortLabel: StateFlow<String> = _connectedPortLabel.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    private val _nmeaLog = MutableStateFlow<List<String>>(emptyList())
    val nmeaLog: StateFlow<List<String>> = _nmeaLog.asStateFlow()

    private val _serialInputLog = MutableStateFlow<List<String>>(emptyList())
    val serialInputLog: StateFlow<List<String>> = _serialInputLog.asStateFlow()

    private val _consoleBytesReceived = MutableStateFlow(0L)
    val consoleBytesReceived: StateFlow<Long> = _consoleBytesReceived.asStateFlow()

    private val _txCount = MutableStateFlow(0L)
    val txCount: StateFlow<Long> = _txCount.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var nmeaService: NmeaService? = null
    private var isBound = false
    private var autoConnectPending = false

    private val nmeaUpdateChannel = Channel<String>(capacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val serialUpdateChannel = Channel<String>(capacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NmeaService.LocalBinder
            val s = binder.getService()
            nmeaService = s
            isBound = true
            Log.d("MainViewModel", "Service connected")
            observeService(s)
            
            s.usbSerialManager.enumerateDevices()
            checkAutoConnect()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nmeaService = null
            isBound = false
            Log.d("MainViewModel", "Service disconnected")
        }
    }

    private val intent = Intent(application, NmeaService::class.java)

    init {
        startLogProcessors()
    }

    fun bindService() {
        if (!isBound) {
            getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun observeService(service: NmeaService) {
        viewModelScope.launch {
            launch { service.locationProvider.locationData.collect { _locationData.value = it } }
            launch { service.locationProvider.sourceLabel.collect { _locationSource.value = it } }
            launch { 
                service.usbSerialManager.availablePorts.collect { 
                    _availablePorts.value = it 
                    checkAutoConnect()
                } 
            }
            launch {
                service.usbSerialManager.connectionState.collect {
                    _connectionState.value = it
                    if (it != UsbSerialManager.ConnectionState.CONNECTED) {
                        _isTransmitting.value = false
                    }
                }
            }
            launch { service.usbSerialManager.connectedPortLabel.collect { _connectedPortLabel.value = it } }
            launch { service.usbSerialManager.consoleBytesReceived.collect { _consoleBytesReceived.value = it } }
            launch { service.sentNmea.collect { nmeaUpdateChannel.send(it) } }
            launch {
                service.usbSerialManager.receivedData.collect { portData ->
                    if (portData.portIndex == 0) {
                        // Serial rendering is best-effort. The USB reader must
                        // never wait for this bounded UI-log buffer.
                        serialUpdateChannel.trySend(portData.data)
                    }
                }
            }
        }
    }

    private fun startLogProcessors() {
        viewModelScope.launch {
            val pendingNmea = mutableListOf<String>()
            val pendingSerial = mutableListOf<String>()
            
            while (isActive) {
                delay(100) 
                
                while (true) {
                    val msg = nmeaUpdateChannel.tryReceive().getOrNull() ?: break
                    pendingNmea.add(msg)
                }
                if (pendingNmea.isNotEmpty()) {
                    val current = _nmeaLog.value.toMutableList()
                    current.addAll(pendingNmea)
                    while (current.size > 200) current.removeAt(0)
                    _nmeaLog.value = current
                    pendingNmea.clear()
                }

                while (true) {
                    val msg = serialUpdateChannel.tryReceive().getOrNull() ?: break
                    pendingSerial.add(msg)
                }
                if (pendingSerial.isNotEmpty()) {
                    val current = _serialInputLog.value.toMutableList()
                    current.addAll(pendingSerial)
                    var retainedChars = current.sumOf { it.length }
                    while (retainedChars > SERIAL_LOG_MAX_CHARS && current.isNotEmpty()) {
                        val excess = retainedChars - SERIAL_LOG_MAX_CHARS
                        val oldest = current.first()
                        if (oldest.length <= excess) {
                            retainedChars -= current.removeAt(0).length
                        } else {
                            current[0] = oldest.substring(excess)
                            retainedChars -= excess
                        }
                    }
                    _serialInputLog.value = current
                    pendingSerial.clear()
                }
            }
        }
    }

    fun setAutoConnectPending(pending: Boolean) {
        autoConnectPending = pending
        checkAutoConnect()
    }

    private fun checkAutoConnect() {
        if (autoConnectPending && isBound) {
            val ports = _availablePorts.value
            if (ports.size == 1) {
                connectToPort(ports[0])
                autoConnectPending = false
            } else if (ports.size > 1) {
                _events.tryEmit(Event.ShowDeviceSelection)
                autoConnectPending = false
            }
        }
    }

    fun refreshDevices() {
        nmeaService?.usbSerialManager?.enumerateDevices()
    }

    fun connectToPort(port: SerialDevicePort) {
        nmeaService?.usbSerialManager?.connect(port) { success ->
            if (success) {
                val app = getApplication<Application>()
                val hasFineLocation = ContextCompat.checkSelfPermission(
                    app,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasFineLocation) {
                    app.startForegroundService(intent)
                    nmeaService?.startConnectedSession()
                    _isTransmitting.value = true
                } else {
                    Log.w("MainViewModel", "USB connected without location permission; session not started")
                    _isTransmitting.value = false
                }
            }
        }
    }

    fun disconnect() {
        nmeaService?.stopConnectedSession()
        nmeaService?.usbSerialManager?.disconnect()
        _isTransmitting.value = false
    }

    fun clearNmeaLog() { _nmeaLog.value = emptyList() }
    fun clearSerialInputLog() { _serialInputLog.value = emptyList() }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}
