package lt.gfau.se.shuriken.wigle

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Fail-fast, sequential, and never retries a POST or starts the next after cancellation. */
object WigleBatch {
    suspend fun <T> run(items: List<T>, intervalMillis: Long = 1000, send: suspend (T, Int) -> Unit) {
        items.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            if (index > 0) delay(intervalMillis)
            currentCoroutineContext().ensureActive()
            send(item, index + 1)
        }
    }
}
