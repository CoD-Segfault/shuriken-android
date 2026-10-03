package lt.gfau.se.shuriken.wigle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WigleBatchTest {
    @Test fun uploadsSequentiallyInSelectionOrder() = runBlocking {
        val events = mutableListOf<String>()
        WigleBatch.run(listOf("a", "b", "c"), 0) { item, position ->
            events += "start:$position:$item"
            events += "accepted:$item"
        }
        assertEquals(listOf("start:1:a", "accepted:a", "start:2:b", "accepted:b", "start:3:c", "accepted:c"), events)
    }

    @Test fun rateLimitAndOtherFailuresStopWithoutRetryingOrSendingRemainder() = runBlocking {
        for (code in listOf(400, 401, 418, 429, 500)) {
            val sent = mutableListOf<String>()
            val accepted = mutableListOf<String>()
            try {
                WigleBatch.run(listOf("a", "b", "c"), 0) { item, _ ->
                    sent += item
                    if (item == "b") throw WigleUploadException("rejected", code)
                    accepted += item
                }
                fail("Batch continued after HTTP $code")
            } catch (e: WigleUploadException) { assertEquals(code, e.httpCode) }
            assertEquals(listOf("a", "b"), sent)
            assertEquals(listOf("a"), accepted)
        }
    }

    @Test fun cancellationBetweenUploadsDoesNotStartNextFile() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val job = launch {
            WigleBatch.run(listOf("a", "b"), 60000) { item, _ ->
                sent += item
                first.complete(Unit)
            }
        }
        first.await()
        job.cancelAndJoin()
        assertEquals(listOf("a"), sent)
    }
}
