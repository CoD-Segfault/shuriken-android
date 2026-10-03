package lt.gfau.se.shuriken.wigle

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Uses real Android SQLite, with a database separate from user upload history. */
class WigleUploadHistoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "wigle-upload-history-instrumentation-test.db"
    private lateinit var history: WigleUploadHistory

    @Before fun setUp() {
        context.deleteDatabase(databaseName)
        history = WigleUploadHistory(context, databaseName)
    }

    @After fun tearDown() {
        history.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun acceptedUploadsPersistAcrossReopening() {
        val key = UploadedFileKey("survey.csv", 123456L)
        assertTrue(history.read().isEmpty())
        history.record(key)
        history.close()
        history = WigleUploadHistory(context, databaseName)
        assertEquals(setOf(key), history.read())
    }

    @Test fun matchingRequiresBothOriginalNameAndUncompressedSize() {
        val key = UploadedFileKey("survey.csv", 3_000_000_000L)
        history.record(key)
        history.record(key)
        val uploaded = history.read()
        assertEquals(1, uploaded.size)
        assertTrue(key in uploaded)
        assertFalse(UploadedFileKey("survey.csv", key.size + 1) in uploaded)
        assertFalse(UploadedFileKey("other.csv", key.size) in uploaded)
        history.record(UploadedFileKey(key.name, key.size + 1))
        assertEquals(2, history.read().size)
    }
}
