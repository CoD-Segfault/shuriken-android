package lt.gfau.se.shuriken.wigle

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

class WigleGzipTest {
    @Test fun compressedSnapshotPreservesAllBytesAndSource() {
        val source = File.createTempFile("wigle-source-", ".csv")
        val destination = File.createTempFile("wigle-gzip-", ".gz")
        val original = "SSID,café,東京\r\n".repeat(20000).toByteArray()
        try {
            source.writeBytes(original)
            WigleGzip.compress(source, destination)
            assertArrayEquals(original, GZIPInputStream(destination.inputStream()).use { it.readBytes() })
            assertArrayEquals(original, source.readBytes())
            assertTrue(destination.length() < source.length())
        } finally { source.delete(); destination.delete() }
    }

    @Test fun cancellationRemovesPartialCompressedFile() {
        val source = File.createTempFile("wigle-source-", ".csv")
        val destination = File.createTempFile("wigle-gzip-", ".gz")
        try {
            source.writeBytes(ByteArray(200000) { it.toByte() })
            var checks = 0
            try {
                WigleGzip.compress(source, destination) {
                    if (++checks == 2) throw CancellationException()
                }
                fail("Compression ignored cancellation")
            } catch (_: CancellationException) { }
            assertFalse(destination.exists())
            assertEquals(200000L, source.length())
        } finally { source.delete(); destination.delete() }
    }

    @Test fun originalBasenameIsRetainedAndUnsafeNamesAreSanitized() {
        assertEquals("survey.csv.gz", WigleGzip.uploadName("survey.csv"))
        assertEquals("café survey.csv.gz", WigleGzip.uploadName("café survey.csv"))
        assertEquals("survey.csv.gz", WigleGzip.uploadName("/provider/path/survey.csv"))
        assertEquals("survey.csv.gz", WigleGzip.uploadName("C:\\logs\\survey.csv"))
        assertEquals("evil.csv.gz", WigleGzip.uploadName("evil\r\n.csv"))
        assertEquals("shuriken.csv.gz", WigleGzip.uploadName(""))
        assertEquals("shuriken.csv.gz", WigleGzip.uploadName(".."))
    }
}
