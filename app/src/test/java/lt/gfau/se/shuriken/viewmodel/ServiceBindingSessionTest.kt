package lt.gfau.se.shuriken.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ServiceBindingSessionTest {
    @Test fun repeatedRequestsDoNotDuplicatePendingBindingAndCloseStillUnbinds() {
        var binds = 0
        var unbinds = 0
        val session = ServiceBindingSession({ binds++; true }, { unbinds++ })
        repeat(5) { assertTrue(session.requestBinding()) }
        assertEquals(1, binds)
        assertTrue(session.isBinding)
        assertFalse(session.isConnected)
        session.close()
        session.close()
        assertEquals(1, unbinds)
        assertFalse(session.requestBinding())
        assertFalse(session.connected { fail("Late callback started observers"); Job() })
    }

    @Test fun disconnectCancelsCollectorsButRetainsAndroidRegistrationForReconnect() {
        var binds = 0
        var unbinds = 0
        val session = ServiceBindingSession({ binds++; true }, { unbinds++ })
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val oldSource = MutableStateFlow(0)
        val newSource = MutableStateFlow(10)
        val seen = mutableListOf<Int>()
        try {
            session.requestBinding()
            session.connected { scope.launch { oldSource.collect { seen += it } } }
            assertEquals(1, oldSource.subscriptionCount.value)
            session.disconnected()
            assertEquals(0, oldSource.subscriptionCount.value)
            assertTrue(session.isBinding)
            assertFalse(session.isConnected)
            assertEquals(0, unbinds)
            session.requestBinding()
            assertEquals(1, binds)
            session.connected { scope.launch { newSource.collect { seen += it } } }
            oldSource.value = 1
            newSource.value = 11
            assertEquals(listOf(0, 10, 11), seen)
            session.close()
            assertEquals(0, newSource.subscriptionCount.value)
            assertEquals(1, unbinds)
        } finally { session.close(); scope.cancel() }
    }

    @Test fun replacementConnectionCancelsPreviousObservationJob() {
        val session = ServiceBindingSession({ true }, {})
        session.requestBinding()
        val first = Job()
        val second = Job()
        session.connected { first }
        session.connected { second }
        assertTrue(first.isCancelled)
        assertTrue(second.isActive)
        session.close()
        assertTrue(second.isCancelled)
    }

    @Test fun failedBindReleasesTrackingAndAllowsExplicitRetry() {
        var allowed = false
        var unbinds = 0
        val session = ServiceBindingSession({ allowed }, { unbinds++ })
        assertFalse(session.requestBinding())
        assertFalse(session.isBinding)
        assertEquals(1, unbinds)
        allowed = true
        assertTrue(session.requestBinding())
        session.close()
        assertEquals(2, unbinds)
    }

    @Test fun deadOrNullBindingReleasesAndFreshBindHasOneObserverSet() {
        var binds = 0
        var unbinds = 0
        val session = ServiceBindingSession({ binds++; true }, { unbinds++ })
        session.requestBinding()
        val old = Job()
        session.connected { old }
        session.release()
        assertTrue(old.isCancelled)
        assertFalse(session.isBinding)
        assertFalse(session.isConnected)
        assertFalse(session.connected { fail("Released binding started observers"); Job() })
        session.requestBinding()
        val fresh = Job()
        session.connected { fresh }
        assertEquals(2, binds)
        assertEquals(1, unbinds)
        session.close()
        assertTrue(fresh.isCancelled)
        assertEquals(2, unbinds)
    }

    @Test fun throwingBindCleansUpRegistration() {
        var unbinds = 0
        val session = ServiceBindingSession({ throw SecurityException("denied") }, { unbinds++ })
        try { session.requestBinding(); fail("Expected bind failure") }
        catch (_: SecurityException) { }
        assertFalse(session.isBinding)
        assertEquals(1, unbinds)
        session.close()
        assertEquals(1, unbinds)
    }
}
