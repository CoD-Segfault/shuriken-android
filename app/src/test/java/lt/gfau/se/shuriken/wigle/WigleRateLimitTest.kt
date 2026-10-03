package lt.gfau.se.shuriken.wigle

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WigleRateLimitTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z").toEpochMilli()

    @Test fun retryAfterSecondsAndHttpDateAreHonored() {
        assertEquals(now + 120000, WigleRateLimit.retryAt("120", now))
        assertEquals(now + 120000, WigleRateLimit.retryAt("Sat, 3 Oct 2026 12:02:00 GMT", now))
        assertEquals(now, WigleRateLimit.retryAt("Sat, 3 Oct 2026 11:00:00 GMT", now))
        assertEquals(now, WigleRateLimit.retryAt("0", now))
    }

    @Test fun absentOrInvalidHeaderUsesConservativeDailyCooldown() {
        for (header in listOf(null, "", "garbage", "-1")) {
            assertEquals(now + WigleRateLimit.FALLBACK_MILLIS, WigleRateLimit.retryAt(header, now))
        }
        assertTrue(WigleRateLimit.retryAt(Long.MAX_VALUE.toString(), now) >= now)
    }
}
