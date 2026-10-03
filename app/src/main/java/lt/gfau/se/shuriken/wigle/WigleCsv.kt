package lt.gfau.se.shuriken.wigle

import java.io.File
import java.io.InputStream

class WigleFileException(val userMessage: String) : Exception(userMessage)

object WigleCsv {
    fun isDiagnosticFile(name: String): Boolean = name.substringAfterLast('/')
        .substringAfterLast('\\').trim().equals("RESETLOG.CSV", ignoreCase = true)

    const val MAX_BYTES = 100L * 1024 * 1024
    private const val COLUMNS = "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type"

    /** Make a bounded, stable snapshot before sending anything to WiGLE. */
    fun copyAndValidate(input: InputStream, destination: File, checkCancelled: () -> Unit = {}) {
        try {
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > MAX_BYTES) throw WigleFileException("This app limits uploads to 100 MiB.")
                    output.write(buffer, 0, count)
                }
            }
            validate(destination)
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    fun validate(file: File) {
        if (file.length() > MAX_BYTES) throw WigleFileException("This app limits uploads to 100 MiB.")
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            fun line(): String {
                val text = StringBuilder()
                while (true) {
                    val c = reader.read()
                    if (c < 0 || c == '\n'.code) break
                    if (text.length >= 2048) throw WigleFileException("Not a supported WiGLE CSV file.")
                    text.append(c.toChar())
                }
                return text.toString().trimEnd('\r')
            }
            val metadata = line().removePrefix("\uFEFF")
            val columns = line()
            if (!metadata.matches(Regex("WigleWifi-1\\.[0-9]+,.*")) ||
                columns.split(',').take(11).joinToString(",") != COLUMNS) {
                throw WigleFileException("Select a WiGLE CSV log saved from Shuriken, not a diagnostic file.")
            }
            if (reader.read() < 0) throw WigleFileException("The CSV contains no observations.")
        }
    }
}
