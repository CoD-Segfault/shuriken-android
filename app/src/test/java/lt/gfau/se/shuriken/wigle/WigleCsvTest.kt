package lt.gfau.se.shuriken.wigle

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class WigleCsvTest {
    @Test fun resetDiagnosticIsBlockedRegardlessOfCaseOrProviderPath() {
        for (name in listOf("RESETLOG.CSV", "resetlog.csv", "/logs/ResetLog.Csv", "C:\\logs\\RESETLOG.CSV")) {
            assertTrue(WigleCsv.isDiagnosticFile(name))
        }
        assertFalse(WigleCsv.isDiagnosticFile("survey.csv"))
        assertFalse(WigleCsv.isDiagnosticFile("RESETLOG-other.csv"))
    }

    private val columns = "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type"
    private fun snapshot(text: String): File {
        val file = File.createTempFile("wigle-test-", ".csv")
        try {
            WigleCsv.copyAndValidate(ByteArrayInputStream(text.toByteArray()), file)
            return file
        } catch (e: Exception) {
            assertFalse(file.exists())
            throw e
        }
    }

    @Test fun shuriken14AndWigle16AreCopiedWithoutReformatting() {
        for (version in listOf("1.4", "1.6")) {
            val text = "WigleWifi-$version,appRelease=test\r\n$columns\r\n" +
                "AA:BB:CC:DD:EE:FF,test,[OPEN],2026-10-02 00:00:00,1,-50,1,2,3,4,WIFI\r\n"
            val file = snapshot(text)
            try { assertEquals(text, file.readText()) } finally { file.delete() }
        }
    }

    @Test fun diagnosticsEmptyLogsAndOversizedHeadersAreRejected() {
        for (text in listOf("not CSV", "WigleWifi-1.4,appRelease=test\n$columns\n",
            "WigleWifi-1.4," + "x".repeat(2048) + "\n$columns\nrow\n")) {
            try { snapshot(text).also { it.delete() }; fail("Invalid CSV accepted") }
            catch (e: WigleFileException) { /* expected; partial snapshot removed */ }
        }
    }

    @Test fun cancellationRemovesPartialSnapshot() {
        val file = File.createTempFile("wigle-test-", ".csv")
        try {
            try {
                WigleCsv.copyAndValidate(ByteArrayInputStream(ByteArray(128 * 1024)), file) {
                    throw java.util.concurrent.CancellationException()
                }
                fail("Cancellation ignored")
            } catch (e: java.util.concurrent.CancellationException) {
                assertFalse(file.exists())
            }
        } finally { file.delete() }
    }

    @Test fun sizeLimitIsEnforcedWhileStreaming() {
        val file = File.createTempFile("wigle-test-", ".csv")
        val input = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                java.util.Arrays.fill(buffer, offset, offset + length, 0.toByte())
                return length
            }
        }
        try {
            try { WigleCsv.copyAndValidate(input, file); fail("Size limit ignored") }
            catch (e: WigleFileException) {
                assertTrue(e.userMessage.contains("100 MiB"))
                assertFalse(file.exists())
            }
        } finally { file.delete() }
    }
}
