package borg.trikeshed.graal

import borg.trikeshed.context.ElementState
import borg.trikeshed.dag.*
import borg.trikeshed.lib.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BlackboardProjectionDrainTest {
    @Test fun cancellingAManualWaiterRetainsItsAdmittedProjection() = runTest {
        val board = ConfixBlackboard.empty()
        val rete = ReteNetwork()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val projecting = CompletableDeferred<Unit>()
        val element = BlackboardChangesFactElement(board, rete, admit = {
            projecting.complete(Unit)
            true
        })
        val lockOwner = async(Dispatchers.IO) {
            rete.snapshot {
                locked.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(locked.await(10, TimeUnit.SECONDS)) }
            board.put("probe/accepted", "retained", "test")
            val waiter = launch { element.drainKeys() }
            projecting.await()
            waiter.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { element.close() }
            release.countDown()
            lockOwner.await()
            waiter.join()
            closing.await()
            val facts = rete.snapshot()
            assertEquals(1, facts.size)
            assertEquals("retained", facts[0].fields[BlackboardChangesFactElement.VALUE])
            assertTrue(element.supervisor.isCompleted)
        } finally {
            release.countDown()
            lockOwner.await()
            element.supervisor.cancelAndJoin()
        }
    }

    @Test fun closeWaitsForTheRealReteWriteLockWithoutCancellingProjection() = runTest {
        val board = ConfixBlackboard.empty()
        val rete = ReteNetwork()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val projecting = CompletableDeferred<Unit>()
        val element = BlackboardChangesFactElement(board, rete, admit = {
            projecting.complete(Unit)
            true
        })
        val lockOwner = async(Dispatchers.IO) {
            rete.snapshot {
                locked.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Rete test lock was not released" }
            }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(locked.await(10, TimeUnit.SECONDS)) }
            board.put("camel/route/final", mapOf("status" to "stopped"), "camel")
            element.open()
            projecting.await()
            val barrier = async(start = CoroutineStart.UNDISPATCHED) {
                element.awaitRevision(board.snapshot().revision)
            }
            val closing = async(start = CoroutineStart.UNDISPATCHED) { element.close() }
            withContext(Dispatchers.Default) { delay(100) }
            assertFalse(barrier.isCompleted, "Accepting a wakeup cannot acknowledge the locked Rete write")
            assertFalse(closing.isCompleted, "Close must retain the projection blocked on the real Rete mutex")
            release.countDown()
            lockOwner.await()
            barrier.await()
            closing.await()
            val facts = rete.snapshot()
            assertEquals(1, facts.size)
            assertEquals("stopped", facts[0].fields["status"])
            assertEquals(ElementState.CLOSED, element.state)
            assertTrue(element.supervisor.isCompleted)
        } finally {
            release.countDown()
            lockOwner.await()
            element.supervisor.cancelAndJoin()
        }
    }
}
