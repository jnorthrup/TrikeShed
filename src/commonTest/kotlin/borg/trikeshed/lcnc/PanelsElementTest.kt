package borg.trikeshed.lcnc

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PanelsElementTest {
    @Test fun aCancelledWaiterCannotFreeTheFiniteActionAdmissionBeforeActualCompletion() = runTest {
        val owner = PanelsElement(coroutineContext.job, capacity = 1, workers = 1, maxActions = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var finished = false
        owner.open()
        try {
            val waiter = assertNotNull(owner.submit { entered.complete(Unit); release.await(); finished = true })
            entered.await()
            waiter.cancel()
            assertNull(owner.submit { error("Cancelled observation cannot free a live action's slot") })
            val drained = async { owner.drain() }
            runCurrent()
            assertFalse(drained.isCompleted)
            assertFalse(finished)
            release.complete(Unit)
            drained.await()
            assertTrue(finished)
            assertEquals(ElementState.CLOSED, owner.state)
            assertFalse(owner.supervisor.children.any())
            assertNull(owner.submit { error("Closed page cannot admit work") })
        } finally { release.complete(Unit); owner.close() }
    }

    @Test fun everyBusyActionCanAwaitOneSharedPicklistAfterExternalAdmissionStops() = runTest {
        val owner = PanelsElement(coroutineContext.job, capacity = 1, workers = 3, maxActions = 3,
            maxPicklists = 1, maxCachedPicklists = 1)
        val entered = CompletableDeferred<Unit>()
        val releaseActions = CompletableDeferred<Unit>()
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var actionEntries = 0
        var reads = 0
        val values = mutableListOf<List<String>>()
        owner.open()
        try {
            val waiters = List(3) { assertNotNull(owner.submit {
                if (++actionEntries == 3) entered.complete(Unit)
                releaseActions.await()
                values.add(owner.picklist("shared") {
                    reads++
                    readEntered.complete(Unit)
                    releaseRead.await()
                    listOf("retained")
                })
            }) }
            entered.await()
            val drained = async { owner.drain() }
            runCurrent()
            assertNull(owner.submit { error("External roots must stop before dependent lookup admission") })
            releaseActions.complete(Unit)
            readEntered.await()
            runCurrent()
            assertEquals(1, reads)
            assertFalse(drained.isCompleted)
            assertTrue(values.isEmpty())
            releaseRead.complete(Unit)
            waiters.awaitAll()
            drained.await()
            assertEquals(List(3) { listOf("retained") }, values)
            assertEquals(1, reads, "Every waiter shares actual request completion")
            assertFalse(owner.supervisor.children.any())
        } finally { releaseActions.complete(Unit); releaseRead.complete(Unit); owner.close() }
    }

    @Test fun distinctRequestsWaitForFiniteAdmissionWithoutOccupyingTheActionsWorkerPool() = runTest {
        val owner = PanelsElement(coroutineContext.job, capacity = 1, workers = 3, maxActions = 3,
            maxPicklists = 1, maxCachedPicklists = 1)
        val firstRead = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        var completed = 0
        owner.open()
        try {
            val waiters = List(3) { index -> assertNotNull(owner.submit {
                val value = owner.picklist("request-$index") {
                    active++
                    peak = maxOf(peak, active)
                    firstRead.complete(Unit)
                    release.await()
                    active--
                    listOf(index.toString())
                }
                assertEquals(listOf(index.toString()), value)
                completed++
            }) }
            firstRead.await()
            val drained = async { owner.drain() }
            runCurrent()
            assertEquals(1, active)
            assertEquals(1, peak)
            assertFalse(drained.isCompleted)
            release.complete(Unit)
            waiters.awaitAll()
            drained.await()
            assertEquals(3, completed)
            assertEquals(1, peak)
            assertFalse(owner.supervisor.children.any())
        } finally { release.complete(Unit); owner.close() }
    }

    @Test fun selfDrainAndExpiredCarriersCannotCloseOrReuseThePageOwner() = runTest {
        val owner = PanelsElement(coroutineContext.job, capacity = 1, workers = 1)
        owner.open()
        var carrier: kotlin.coroutines.CoroutineContext? = null
        try {
            assertFailsWith<IllegalStateException> { owner.picklist("external") { error("No external reader") } }
            assertNotNull(owner.submit {
                carrier = currentCoroutineContext().minusKey(Job)
                assertFailsWith<IllegalStateException> { owner.drain() }
                assertEquals(listOf("live"), owner.picklist("inside") {
                    assertFailsWith<IllegalStateException> { owner.close() }
                    listOf("live")
                })
            }).await()
            withContext(checkNotNull(carrier)) {
                assertFailsWith<IllegalStateException> { owner.picklist("expired") { error("Expired carrier") } }
            }
            assertNotNull(owner.submit {}).await()
            owner.drain()
            assertEquals(ElementState.CLOSED, owner.state)
            assertFalse(owner.supervisor.children.any())
        } finally { owner.close() }
    }

    @Test fun aFailedSharedReadCanBeRetriedAndDrainStillRetainsItsFailure() = runTest {
        val owner = PanelsElement(coroutineContext.job, capacity = 1, workers = 1,
            maxPicklists = 1, maxCachedPicklists = 1)
        var reads = 0
        owner.open()
        try {
            assertNotNull(owner.submit {
                val failure = assertFailsWith<IllegalArgumentException> {
                    owner.picklist("retry") { reads++; throw IllegalArgumentException("picklist unavailable") }
                }
                assertEquals("picklist unavailable", failure.message)
                assertEquals(listOf("restored"), owner.picklist("retry") { reads++; listOf("restored") })
            }).await()
            assertEquals(2, reads)
            val failure = assertFailsWith<IllegalStateException> { owner.drain() }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
                it is IllegalArgumentException && it.message == "picklist unavailable"
            })
            assertEquals(ElementState.CLOSED, owner.state)
            assertFalse(owner.supervisor.children.any())
        } finally { runCatching { owner.close() } }
    }

    @Test fun parentCancellationStillJoinsTheActualRequestCleanupAndSettlesEveryWaiter() = runTest {
        val parent = Job()
        val owner = PanelsElement(parent, capacity = 1, workers = 1, maxActions = 1,
            maxPicklists = 1, maxCachedPicklists = 1)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        owner.open()
        try {
            val waiter = assertNotNull(owner.submit {
                owner.picklist("held") {
                    try { entered.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
                }
            })
            entered.await()
            val cancelled = async { parent.cancelAndJoin() }
            cleaning.await()
            runCurrent()
            assertFalse(cancelled.isCompleted, "The parent must retain actual request cleanup")
            release.complete(Unit)
            cancelled.await()
            assertTrue(waiter.isCompleted)
            assertFailsWith<IllegalStateException> { owner.drain() }
            assertEquals(ElementState.CLOSED, owner.state)
            assertFalse(parent.children.any())
            assertFalse(owner.supervisor.children.any())
        } finally { release.complete(Unit); parent.cancelAndJoin(); runCatching { owner.close() } }
    }
}
