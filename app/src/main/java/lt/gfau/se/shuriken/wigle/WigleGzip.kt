package lt.gfau.se.shuriken.wigle

import java.io.File
import java.util.zip.GZIPOutputStream

object WigleGzip {
    fun compress(source: File, destination: File, checkCancelled: () -> Unit = {}) {
        try {
            source.inputStream().use { input ->
                GZIPOutputStream(destination.outputStream().buffered()).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            checkCancelled()
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    internal fun uploadName(originalName: String): String {
        // Keep the display basename, never provider-supplied paths or control characters.
        val basename = originalName.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() }.take(256)
            .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "shuriken.csv"
        return "$basename.gz"
    }
}
