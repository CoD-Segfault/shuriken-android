package lt.gfau.se.shuriken.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import lt.gfau.se.shuriken.model.LocationData
import lt.gfau.se.shuriken.model.SerialDevicePort
import lt.gfau.se.shuriken.service.NmeaService
import lt.gfau.se.shuriken.serial.UsbSerialManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var nmeaService: NmeaService? = null
    private var isBound = false

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

    private val _txCount = MutableStateFlow(0L)
    val txCount: StateFlow<Long> = _txCount.asStateFlow()

    private val nmeaUpdateChannel = Channel<String>(capacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val serialUpdateChannel = Channel<String>(capacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NmeaService.LocalBinder
            val s = binder.getService()
            nmeaService = s
            isBound = true
            observeService(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nmeaService = null
            isBound = false
        }
    }

    init {
        val intent = Intent(application, NmeaService::class.java)
        application.startForegroundService(intent)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        startLogProcessors()
    }

    private fun observeService(service: NmeaService) {
        viewModelScope.launch {
            launch { service.locationProvider.locationData.collect { _locationData.value = it } }
            launch { service.locationProvider.sourceLabel.collect { _locationSource.value = it } }
            launch { service.usbSerialManager.availablePorts.collect { _availablePorts.value = it } }
            launch { service.usbSerialManager.connectionState.collect { _connectionState.value = it } }
            launch { service.usbSerialManager.connectedPortLabel.collect { _connectedPortLabel.value = it } }
            launch { service.sentNmea.collect { nmeaUpdateChannel.send(it) } }
            launch {
                service.usbSerialManager.receivedData.collect { portData ->
                    // Corrected port order: Port 0 is Serial In
                    if (portData.portIndex == 0) {
                        serialUpdateChannel.send(portData.data)
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
                delay(100) // Update UI at 10Hz max
                
                // Drain NMEA channel
                while (true) {
                    val msg = nmeaUpdateChannel.tryReceive().getOrNull() ?: break
                    pendingNmea.add(msg)
                }
                if (pendingNmea.isNotEmpty()) {
                    val current = _nmeaLog.value.toMutableList()
                    current.addAll(pendingNmea)
                    while (current.size > 200) current.removeAt(0)
                    _nmeaLog.value = current
                    _txCount.value += pendingNmea.size
                    pendingNmea.clear()
                }

                // Drain Serial channel
                while (true) {
                    val msg = serialUpdateChannel.tryReceive().getOrNull() ?: break
                    pendingSerial.add(msg)
                }
                if (pendingSerial.isNotEmpty()) {
                    val current = _serialInputLog.value.toMutableList()
                    current.addAll(pendingSerial)
                    while (current.size > 500) current.removeAt(0)
                    _serialInputLog.value = current
                    pendingSerial.clear()
                }
            }
        }
    }

    fun startLocation() {
        nmeaService?.locationProvider?.start()
    }

    fun refreshDevices() {
        nmeaService?.usbSerialManager?.enumerateDevices()
    }

    fun connectToPort(port: SerialDevicePort) {
        nmeaService?.usbSerialManager?.connect(port) { success ->
            if (success) {
                nmeaService?.startTransmitting()
                _isTransmitting.value = true
            }
        }
    }

    fun disconnect() {
        nmeaService?.stopTransmitting()
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
