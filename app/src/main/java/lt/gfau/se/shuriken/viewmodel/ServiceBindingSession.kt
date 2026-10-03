package lt.gfau.se.shuriken.viewmodel

import kotlinx.coroutines.Job

/** Main-thread owner of one Android binding registration and its active observers. */
internal class ServiceBindingSession(private val bind: () -> Boolean, private val unbind: () -> Unit) {
    private var registered = false
    private var closed = false
    private var observers: Job? = null
    var isConnected = false
        private set
    val isBinding: Boolean get() = registered && !isConnected

    fun requestBinding(): Boolean {
        if (closed) return false
        if (registered) return true
        // Set before requesting: binding is asynchronous, including across rotations.
        registered = true
        return try {
            bind().also { if (!it) release() }
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    fun connected(observe: () -> Job): Boolean {
        if (closed || !registered) return false
        disconnected()
        isConnected = true
        observers = observe()
        return true
    }

    fun disconnected() {
        observers?.cancel()
        observers = null
        isConnected = false
        // Android retains the registration on transient service loss and reconnects it.
    }

    fun release() {
        disconnected()
        if (registered) {
            registered = false
            unbind()
        }
    }

    fun close() {
        closed = true
        release()
    }
}
