package lt.gfau.se.shuriken.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import lt.gfau.se.shuriken.model.LocationData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationProvider(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _locationData = MutableStateFlow<LocationData?>(null)
    val locationData: StateFlow<LocationData?> = _locationData.asStateFlow()

    private val _locationUpdates = MutableSharedFlow<LocationData>(extraBufferCapacity = 16)
    val locationUpdates: SharedFlow<LocationData> = _locationUpdates.asSharedFlow()

    private val _sourceLabel = MutableStateFlow("none")
    val sourceLabel: StateFlow<String> = _sourceLabel.asStateFlow()

    @Volatile private var lastPublishedTimestamp: Long = 0L
    @Volatile private var usedSatellites: Int = 0
    @Volatile private var hdop: Float = 9.9f

    // ── GPS (raw GNSS) ───────────────────────────────────────────────────────

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            publishLocation(location, "gps", fixQuality = 1)
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) used++
            }
            usedSatellites = used
            hdop = when {
                used >= 8 -> 1.0f
                used >= 6 -> 1.5f
                used >= 5 -> 2.0f
                used >= 4 -> 2.5f
                used >= 3 -> 5.0f
                else -> 9.9f
            }
        }
    }

    // ── Fused (Google Play Services) ─────────────────────────────────────────

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { loc ->
                publishLocation(loc, "fused", fixQuality = if (loc.provider == LocationManager.GPS_PROVIDER) 1 else 2)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    @Synchronized
    private fun publishLocation(location: Location, source: String, fixQuality: Int) {
        // Drop updates that are older than or equal to the last one to prevent 
        // out-of-order sentences and unnecessary processing.
        if (location.time <= lastPublishedTimestamp) return
        lastPublishedTimestamp = location.time

        val data = LocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            speed = location.speed,
            bearing = location.bearing,
            accuracy = location.accuracy,
            timestamp = location.time,
            provider = source,
            satellites = usedSatellites,
            hdop = hdop,
            fixQuality = fixQuality
        )
        _locationData.value = data
        _locationUpdates.tryEmit(data)
        _sourceLabel.value = source
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val looper = Looper.getMainLooper()

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                gpsListener,
                looper
            )
            locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 200L)
            .setMinUpdateIntervalMillis(100L)
            .setMaxUpdateDelayMillis(0L)
            .build()
        fusedClient.requestLocationUpdates(request, fusedCallback, looper)
    }

    fun stop() {
        locationManager.removeUpdates(gpsListener)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        fusedClient.removeLocationUpdates(fusedCallback)
        lastPublishedTimestamp = 0L
    }
}
