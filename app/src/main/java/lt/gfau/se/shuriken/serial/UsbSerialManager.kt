package lt.gfau.se.shuriken.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.util.SerialInputOutputManager
import lt.gfau.se.shuriken.model.SerialDevicePort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbSerialManager(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "lt.gfau.se.shuriken.USB_PERMISSION"
        private const val TAG = "UsbSerialManager"
        const val DEFAULT_BAUD_RATE = 115200
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class PortData(val portIndex: Int, val data: String)

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _availablePorts = MutableStateFlow<List<SerialDevicePort>>(emptyList())
    val availablePorts: StateFlow<List<SerialDevicePort>> = _availablePorts.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _receivedData = MutableSharedFlow<PortData>(replay = 0, extraBufferCapacity = 256)
    val receivedData: SharedFlow<PortData> = _receivedData.asSharedFlow()

    private val _connectedPortLabel = MutableStateFlow("")
    val connectedPortLabel: StateFlow<String> = _connectedPortLabel.asStateFlow()

    var baudRate: Int = DEFAULT_BAUD_RATE

    private val activePorts = mutableMapOf<Int, UsbSerialPort>()
    private var activeConnection: UsbDeviceConnection? = null
    private val ioManagers = mutableMapOf<Int, SerialInputOutputManager>()

    private var pendingPort: SerialDevicePort? = null
    private var pendingCallback: ((Boolean) -> Unit)? = null

    // ── Broadcast receivers ──────────────────────────────────────────────────

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val port = pendingPort ?: return
            pendingPort = null
            if (granted) {
                openDevicePorts(port.port.driver, pendingCallback)
            } else {
                _connectionState.value = ConnectionState.ERROR
                pendingCallback?.invoke(false)
            }
            pendingCallback = null
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                disconnect()
            }
        }
    }

    fun register() {
        val permFlags = if (Build.VERSION.SDK_INT >= 34)
            Context.RECEIVER_NOT_EXPORTED else 0
        context.registerReceiver(
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            permFlags
        )
        context.registerReceiver(
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            Context.RECEIVER_EXPORTED
        )
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(permissionReceiver) }
        runCatching { context.unregisterReceiver(detachReceiver) }
    }

    // ── Device enumeration ───────────────────────────────────────────────────

    fun enumerateDevices() {
        val prober = UsbSerialProber.getDefaultProber()
        val ports = mutableListOf<SerialDevicePort>()
        for (driver in prober.findAllDrivers(usbManager)) {
            // Only add the first port of each driver to represent the device
            val port = driver.ports.firstOrNull() ?: continue
            ports += SerialDevicePort(
                deviceName = driver.device.deviceName,
                portIndex = 0,
                driverName = driver.javaClass.simpleName
                    .removeSuffix("SerialDriver")
                    .removeSuffix("Driver"),
                port = port
            )
        }
        _availablePorts.value = ports
    }

    // ── Connection ───────────────────────────────────────────────────────────

    fun connect(serialPort: SerialDevicePort, onResult: ((Boolean) -> Unit)? = null) {
        val device: UsbDevice = serialPort.port.driver.device
        if (!usbManager.hasPermission(device)) {
            pendingPort = serialPort
            pendingCallback = onResult
            
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                `package` = context.packageName
            }
            
            val flags = when {
                Build.VERSION.SDK_INT >= 34 -> {
                    PendingIntent.FLAG_MUTABLE or 0x00001000 // FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    PendingIntent.FLAG_MUTABLE
                }
                else -> 0
            }

            val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, pi)
            return
        }
        openDevicePorts(serialPort.port.driver, onResult)
    }

    private fun openDevicePorts(driver: UsbSerialDriver, onResult: ((Boolean) -> Unit)?) {
        disconnect()
        _connectionState.value = ConnectionState.CONNECTING
        
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            _connectionState.value = ConnectionState.ERROR
            onResult?.invoke(false)
            return
        }
        
        activeConnection = connection
        var anyPortOpened = false
        
        try {
            for ((idx, port) in driver.ports.withIndex()) {
                try {
                    port.open(connection)
                    port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    port.dtr = true
                    port.rts = true
                    activePorts[idx] = port
                    
                    val ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                        override fun onNewData(data: ByteArray) {
                            _receivedData.tryEmit(PortData(idx, String(data, Charsets.ISO_8859_1)))
                        }
                        override fun onRunError(e: Exception) {
                            Log.e(TAG, "Error on port $idx", e)
                        }
                    })
                    ioManagers[idx] = ioManager
                    ioManager.start()
                    anyPortOpened = true
                    Log.d(TAG, "Opened port $idx")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open port $idx", e)
                }
            }
            
            if (anyPortOpened) {
                _connectionState.value = ConnectionState.CONNECTED
                _connectedPortLabel.value = driver.device.deviceName
                onResult?.invoke(true)
            } else {
                connection.close()
                activeConnection = null
                _connectionState.value = ConnectionState.ERROR
                onResult?.invoke(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during port opening", e)
            disconnect()
            _connectionState.value = ConnectionState.ERROR
            onResult?.invoke(false)
        }
    }

    fun disconnect() {
        for (iom in ioManagers.values) iom.stop()
        ioManagers.clear()
        for (port in activePorts.values) runCatching { port.close() }
        activePorts.clear()
        runCatching { activeConnection?.close() }
        activeConnection = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedPortLabel.value = ""
    }

    // ── I/O ─────────────────────────────────────────────────────────────────

    fun send(data: ByteArray, portIndex: Int = 0): Boolean {
        val port = activePorts[portIndex] ?: return false
        return try {
            port.write(data, 200)
            true
        } catch (e: Exception) {
            false
        }
    }

    val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED
}
