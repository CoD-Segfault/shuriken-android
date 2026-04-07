package lt.gfau.se.shuriken.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import lt.gfau.se.shuriken.MainActivity
import lt.gfau.se.shuriken.R
import lt.gfau.se.shuriken.location.LocationProvider
import lt.gfau.se.shuriken.model.LocationData
import lt.gfau.se.shuriken.nmea.NmeaGenerator
import lt.gfau.se.shuriken.serial.UsbSerialManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NmeaService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "nmea_service_channel"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private var nmeaJob: Job? = null
    private var lastTxRealTime = 0L

    lateinit var locationProvider: LocationProvider
    lateinit var usbSerialManager: UsbSerialManager

    private val _sentNmea = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val sentNmea: SharedFlow<String> = _sentNmea.asSharedFlow()

    inner class LocalBinder : Binder() {
        fun getService(): NmeaService = this@NmeaService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        locationProvider = LocationProvider(this)
        usbSerialManager = UsbSerialManager(this)
        usbSerialManager.register()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Ready to transmit")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    fun startTransmitting() {
        if (nmeaJob?.isActive == true) return
        lastTxRealTime = 0L
        
        updateNotification("Transmitting NMEA...")

        nmeaJob = serviceScope.launch {
            // Heartbeat loop (1Hz minimum)
            launch {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (now - lastTxRealTime >= 1000L) {
                        locationProvider.locationData.value?.let { loc ->
                            if (usbSerialManager.isConnected) {
                                transmitLocation(loc, forceCurrentTime = true)
                            }
                        }
                    }
                    delay(200)
                }
            }

            // Real-time location updates
            locationProvider.locationUpdates.collect { loc ->
                if (usbSerialManager.isConnected) {
                    transmitLocation(loc, forceCurrentTime = true)
                }
            }
        }
    }

    fun stopTransmitting() {
        nmeaJob?.cancel()
        nmeaJob = null
        updateNotification("Ready to transmit")
    }

    private suspend fun transmitLocation(loc: LocationData, forceCurrentTime: Boolean = false) {
        val locToTransmit = if (forceCurrentTime) loc.copy(timestamp = System.currentTimeMillis()) else loc
        for (sentence in NmeaGenerator.generate(locToTransmit)) {
            usbSerialManager.send(sentence.toByteArray(Charsets.US_ASCII), portIndex = 1)
            _sentNmea.emit(sentence.trimEnd())
        }
        lastTxRealTime = System.currentTimeMillis()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shuriken NMEA")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "NMEA Transmission Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTransmitting()
        usbSerialManager.unregister()
        serviceScope.cancel()
    }
}
