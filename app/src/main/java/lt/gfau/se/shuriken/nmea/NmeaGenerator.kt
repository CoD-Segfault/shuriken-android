package lt.gfau.se.shuriken.nmea

import lt.gfau.se.shuriken.model.LocationData
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

object NmeaGenerator {

    private const val KNOTS_PER_MPS = 1.94384f

    fun generate(location: LocationData): List<String> =
        listOf(generateGGA(location), generateRMC(location))

    fun generateGGA(location: LocationData): String {
        val cal = utcCalendar(location.timestamp)
        val time = formatTime(cal)
        val (latStr, latDir) = formatLatitude(location.latitude)
        val (lonStr, lonDir) = formatLongitude(location.longitude)
        val quality = location.fixQuality.coerceIn(0, 8)
        val sats = "%02d".format(location.satellites.coerceIn(0, 99))
        val hdop = "%.1f".format(location.hdop.coerceAtMost(99.9f))
        val alt = "%.2f".format(location.altitude)
        // Geoidal separation: not provided by Android, emit empty field
        val content = "GPGGA,$time,$latStr,$latDir,$lonStr,$lonDir,$quality,$sats,$hdop,$alt,M,,M,,"
        return buildSentence(content)
    }

    fun generateRMC(location: LocationData): String {
        val cal = utcCalendar(location.timestamp)
        val time = formatTime(cal)
        val status = if (location.fixQuality > 0) "A" else "V"
        val (latStr, latDir) = formatLatitude(location.latitude)
        val (lonStr, lonDir) = formatLongitude(location.longitude)
        val speedKnots = "%.2f".format(location.speed * KNOTS_PER_MPS)
        val course = "%.2f".format(location.bearing)
        val date = "%02d%02d%02d".format(
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR) % 100
        )
        // Mode indicator: A=autonomous, N=no fix
        val mode = if (location.fixQuality > 0) "A" else "N"
        val content = "GPRMC,$time,$status,$latStr,$latDir,$lonStr,$lonDir,$speedKnots,$course,$date,,$mode"
        return buildSentence(content)
    }

    private fun utcCalendar(timestampMs: Long): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).also { it.timeInMillis = timestampMs }

    private fun formatTime(cal: Calendar): String {
        val seconds = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000.0
        return "%02d%02d%06.3f".format(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            seconds
        )
    }

    private fun formatLatitude(lat: Double): Pair<String, String> {
        val absLat = abs(lat)
        val degrees = absLat.toInt()
        val minutes = (absLat - degrees) * 60.0
        return "%02d%09.6f".format(degrees, minutes) to if (lat >= 0.0) "N" else "S"
    }

    private fun formatLongitude(lon: Double): Pair<String, String> {
        val absLon = abs(lon)
        val degrees = absLon.toInt()
        val minutes = (absLon - degrees) * 60.0
        return "%03d%09.6f".format(degrees, minutes) to if (lon >= 0.0) "E" else "W"
    }

    private fun buildSentence(content: String): String {
        val checksum = content.fold(0) { acc, c -> acc xor c.code }
        return "\$$content*%02X\r\n".format(checksum)
    }
}
