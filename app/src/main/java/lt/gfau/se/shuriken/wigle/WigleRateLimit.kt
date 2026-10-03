package lt.gfau.se.shuriken.wigle

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object WigleRateLimit {
    const val FALLBACK_MILLIS = 24L * 60 * 60 * 1000

    /** The daily reset time isn't documented: use a conservative local 24h cooldown if absent. */
    fun retryAt(header: String?, now: Long = System.currentTimeMillis()): Long {
        val seconds = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        if (seconds != null) return now + seconds.coerceAtMost((Long.MAX_VALUE - now) / 1000) * 1000
        val date = runCatching {
            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull()
        return date?.coerceAtLeast(now) ?: (now + FALLBACK_MILLIS)
    }
}
