package lt.gfau.se.shuriken.wigle

import java.util.Base64

/** Deliberately not a data class: generated toString must never expose secrets. */
class WigleCredentials private constructor(
    val username: String,
    val apiName: String,
    val apiToken: String
) {
    fun authorizationHeader(): String = "Basic " + Base64.getEncoder()
        .encodeToString("$apiName:$apiToken".toByteArray(Charsets.UTF_8))

    internal fun storageBytes() = "$username:$apiName:$apiToken".toByteArray(Charsets.UTF_8)

    override fun toString() = "WigleCredentials([redacted])"

    companion object {
        fun fromQr(text: String): WigleCredentials {
            require(text.length <= 4096) { "Invalid WiGLE activation QR." }
            val fields = text.trim().split(':')
            require(fields.size == 3) { "Expected Username:API_name:API_token." }
            val username = fields[0]
            require(username.length in 1..128 && username.isNotBlank() && username.none { it.isISOControl() }) {
                "Invalid WiGLE username."
            }
            require(fields.drop(1).all { field ->
                field.length in 1..512 && field.all { it in '!'..'~' && it != ':' }
            }) { "Invalid WiGLE API credentials." }
            return WigleCredentials(username, fields[1], fields[2])
        }
    }
}
