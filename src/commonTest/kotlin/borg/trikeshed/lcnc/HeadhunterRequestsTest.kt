package borg.trikeshed.lcnc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HeadhunterRequestsTest {
    @Test fun drainWaitsForAcceptedWorkAndConcurrentCallers() = runTest {
        val requests = HeadhunterRequests(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var committed = false
        val caller = async { requests.call { entered.complete(Unit); release.await(); committed = true; 7 } }
        entered.await()
        val first = async { requests.drain() }
        val second = async { requests.drain() }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertEquals(7, caller.await())
        first.await(); second.await()
        assertTrue(committed)
        assertFails { requests.call { error("Cannot execute after drain") } }
    }

    @Test fun clientCancellationDoesNotDiscardAnAcceptedWrite() = runTest {
        val requests = HeadhunterRequests(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var committed = false
        val caller = launch { requests.call { entered.complete(Unit); release.await(); committed = true } }
        entered.await()
        caller.cancelAndJoin()
        release.complete(Unit)
        requests.drain()
        assertTrue(committed)
    }

    @Test fun oneFailedOperationDoesNotStrandOtherRequests() = runTest {
        val requests = HeadhunterRequests(this)
        assertFailsWith<IllegalArgumentException> { requests.call { throw IllegalArgumentException("invalid evidence") } }
        assertEquals("saved", requests.call { "saved" })
        requests.drain()
    }
}
