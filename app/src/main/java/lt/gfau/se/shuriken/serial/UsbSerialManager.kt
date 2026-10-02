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
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.util.SerialInputOutputManager
import lt.gfau.se.shuriken.model.SerialDevicePort
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class UsbSerialManager(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "lt.gfau.se.shuriken.USB_PERMISSION"
        private const val TAG = "UsbSerialManager"
        const val DEFAULT_BAUD_RATE = 115200
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class PortData(val portIndex: Int, val data: String)

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _availablePorts = MutableStateFlow<List<SerialDevicePort>>(emptyList())
    val availablePorts: StateFlow<List<SerialDevicePort>> = _availablePorts.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Console rendering is best-effort. Never let a paused or slow UI collector
    // back up the USB reader; retain the newest data so the display recovers.
    private val _receivedData = MutableSharedFlow<PortData>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val receivedData: SharedFlow<PortData> = _receivedData.asSharedFlow()

    private val _consoleBytesReceived = MutableStateFlow(0L)
    val consoleBytesReceived: StateFlow<Long> = _consoleBytesReceived.asStateFlow()

    private val _connectedPortLabel = MutableStateFlow("")
    val connectedPortLabel: StateFlow<String> = _connectedPortLabel.asStateFlow()

    var baudRate: Int = DEFAULT_BAUD_RATE

    private val activePorts = ConcurrentHashMap<Int, UsbSerialPort>()
    // requestWait() receives completions for the whole connection. Independent
    // CDC readers must not compete for completions on a shared connection.
    private val activeConnections = mutableMapOf<Int, UsbDeviceConnection>()
    private val ioManagers = mutableMapOf<Int, SerialInputOutputManager>()
    @Volatile private var sessionGeneration = 0L

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
        disconnect()
        runCatching { context.unregisterReceiver(permissionReceiver) }
        runCatching { context.unregisterReceiver(detachReceiver) }
    }

    // ── Device enumeration ───────────────────────────────────────────────────

    fun enumerateDevices() {
        val prober = UsbSerialProber.getDefaultProber()
        val ports = mutableListOf<SerialDevicePort>()
        for (driver in prober.findAllDrivers(usbManager)) {
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
        
        _consoleBytesReceived.value = 0L
        val generation = sessionGeneration

        try {
            check(driver.ports.isNotEmpty()) { "Device has no serial ports" }
            for ((idx, port) in driver.ports.withIndex()) {
                val connection = usbManager.openDevice(driver.device)
                    ?: error("Could not open USB connection for port $idx")
                activeConnections[idx] = connection
                port.open(connection)
                activePorts[idx] = port
                port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port.dtr = true
                port.rts = true

                val ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        if (sessionGeneration != generation) return
                        if (idx == 0) {
                            _consoleBytesReceived.value += data.size
                        }
                        _receivedData.tryEmit(PortData(idx, String(data, Charsets.ISO_8859_1)))
                    }
                    override fun onRunError(e: Exception) {
                        Log.e(TAG, "Error on port $idx", e)
                        // Release DTR if a reader dies. The connection identity
                        // prevents delayed errors from closing a newer session.
                        mainHandler.post {
                            if (activeConnections[idx] === connection) {
                                disconnect()
                                _connectionState.value = ConnectionState.ERROR
                            }
                        }
                    }
                })
                ioManagers[idx] = ioManager
                ioManager.start()
                Log.d(TAG, "Opened port $idx on its own USB connection")
            }
            // A partly opened device is not a working session: Console must be
            // drained as well as NMEA being writable.
            _connectionState.value = ConnectionState.CONNECTED
            _connectedPortLabel.value = driver.device.deviceName
            onResult?.invoke(true)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during port opening", e)
            disconnect()
            _connectionState.value = ConnectionState.ERROR
            onResult?.invoke(false)
        }
    }

    fun disconnect() {
        sessionGeneration++
        // Invalidate callbacks before closing requests; close can wake readers
        // with errors that must not tear down a subsequent connection.
        val ports = activePorts.values.toList()
        activePorts.clear()
        val connections = activeConnections.values.toList()
        activeConnections.clear()
        for (iom in ioManagers.values) iom.stop()
        ioManagers.clear()
        for (port in ports) {
            runCatching { port.dtr = false }
            runCatching { port.rts = false }
            runCatching { port.close() }
        }
        for (connection in connections) runCatching { connection.close() }
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedPortLabel.value = ""
    }

    // ── I/O ─────────────────────────────────────────────────────────────────

    suspend fun send(data: ByteArray, portIndex: Int = 0): Boolean = withContext(Dispatchers.IO) {
        val port = activePorts[portIndex] ?: return@withContext false
        return@withContext try {
            port.write(data, 200)
            true
        } catch (e: Exception) {
            false
        }
    }

    val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED
}
