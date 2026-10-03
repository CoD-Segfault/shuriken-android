package lt.gfau.se.shuriken.wigle

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WigleCredentialsTest {
    @Test fun activationQrUsesApiNameNotUsernameForBasicAuth() {
        val credentials = WigleCredentials.fromQr("display_user:api_name:api_token")
        assertEquals("display_user", credentials.username)
        assertEquals("api_name:api_token", String(Base64.getDecoder().decode(
            credentials.authorizationHeader().removePrefix("Basic ")), Charsets.UTF_8))
        assertEquals("WigleCredentials([redacted])", credentials.toString())
        assertFalse(credentials.toString().contains("api_token"))
    }

    @Test fun malformedPayloadsAreRejectedWithoutEchoingSecrets() {
        listOf("", "user:secret", "user:api:secret:extra", ":api:secret",
            "user::secret", "user:api:", "user:api\r\nInjected:secret",
            "user:api:sec ret", "user:api:" + "s".repeat(513)).forEach { input ->
            try {
                WigleCredentials.fromQr(input)
                fail("Malformed QR accepted")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.orEmpty().contains("secret"))
            }
        }
    }
}
