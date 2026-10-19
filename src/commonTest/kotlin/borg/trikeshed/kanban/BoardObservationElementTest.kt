package borg.trikeshed.kanban

import borg.trikeshed.context.ElementState
import borg.trikeshed.job.CasStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BoardObservationElementTest {
    @Test fun invalidIntervalOrCapacityLeavesNoOwnedJobInTheParent() = runTest {
        val parent = Job()
        val store = BoardStoreElement(null, CasStore.inMemory(), parentJob = currentCoroutineContext().job)
        store.open()
        try {
            assertFailsWith<IllegalArgumentException> {
                BoardObservationElement(store, seed = {}, committed = {}, tick = {}, intervalMs = 0, parentJob = parent)
            }
            assertTrue(parent.children.none(), "Invalid interval must fail before allocating an owned supervisor")
            assertFailsWith<IllegalArgumentException> {
                BoardObservationElement(store, seed = {}, committed = {}, tick = {}, capacity = 0, parentJob = parent)
            }
            assertTrue(parent.children.none(), "Invalid capacity must fail before allocating an owned supervisor")
            parent.complete()
            withTimeout(1_000) { parent.join() }
            assertTrue(parent.isCompleted)
        } finally { parent.cancelAndJoin(); store.drain() }
    }

    private suspend fun submit(store: BoardStoreElement, id: String): BoardApply.Committed {
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(BoardIntake(mapOf("type" to "submit", "jobId" to id,
            "idempotencyKey" to "submit/$id"), reply))
        return assertIs<BoardApply.Committed>(reply.await())
    }

    private fun closed(element: BoardObservationElement) {
        assertEquals(ElementState.CLOSED, element.state)
        assertTrue(element.supervisor.isCompleted)
        assertTrue(element.supervisor.children.none(), "Every owned producer and consumer is joined")
    }

    @Test fun openReturnsOnlyAfterTheSeededPrefixHasBeenProjected() = runTest {
        val store = BoardStoreElement(null, CasStore.inMemory(), parentJob = currentCoroutineContext().job)
        store.open()
        val prefix = submit(store, "seeded")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val seeded = mutableListOf<String>()
        lateinit var element: BoardObservationElement
        element = BoardObservationElement(store, seed = {
            assertSame(element, currentCoroutineContext()[BoardObservationElement])
            val rows = store.cards()
            entered.complete(Unit)
            release.await()
            seeded.addAll(rows.map { it.jobId })
        }, committed = {}, tick = {}, parentJob = currentCoroutineContext().job)
        try {
            val opening = async(start = CoroutineStart.UNDISPATCHED) { element.open() }
            entered.await()
            assertFalse(opening.isCompleted)
            assertTrue(seeded.isEmpty())
            release.complete(Unit)
            opening.await()
            assertEquals(listOf("seeded"), seeded)
            assertEquals(ElementState.ACTIVE, element.state)
            element.awaitSequence(prefix.sequence)
            element.drain()
            closed(element)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { element.drain(); store.drain() }
        }
    }

    @Test fun commitWatermarkAndDrainWaitForTheActualProjectionCallback() = runTest {
        val store = BoardStoreElement(null, CasStore.inMemory(), parentJob = currentCoroutineContext().job)
        store.open()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val projected = mutableListOf<Long>()
        val element = BoardObservationElement(store, seed = {}, committed = { event ->
            entered.complete(Unit)
            release.await()
            projected.add(event.sequence)
        }, tick = {}, capacity = 1, parentJob = currentCoroutineContext().job)
        try {
            element.open()
            element.stopTicker()
            val committed = submit(store, "held")
            entered.await()
            val barrier = async(start = CoroutineStart.UNDISPATCHED) { element.awaitSequence(committed.sequence) }
            val draining = async(start = CoroutineStart.UNDISPATCHED) { element.drain() }
            assertFalse(barrier.isCompleted)
            assertFalse(draining.isCompleted)
            assertTrue(projected.isEmpty())
            release.complete(Unit)
            barrier.await()
            draining.await()
            assertEquals(listOf(committed.sequence), projected)
            closed(element)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { element.drain(); store.drain() }
        }
    }

    @Test fun stopTickerJoinsTheHeldTickWithoutCancellingItsBody() = runTest {
        val store = BoardStoreElement(null, CasStore.inMemory(), parentJob = currentCoroutineContext().job)
        store.open()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var calls = 0
        var cancelled = false
        val element = BoardObservationElement(store, seed = {}, committed = {}, tick = {
            calls++
            entered.complete(Unit)
            try { release.await() }
            catch (failure: CancellationException) { cancelled = true; throw failure }
            finally { finished.complete(Unit) }
        }, intervalMs = 1, parentJob = currentCoroutineContext().job)
        try {
            element.open()
            entered.await()
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { element.stopTicker() }
            assertFalse(stopping.isCompleted)
            assertFalse(finished.isCompleted)
            release.complete(Unit)
            stopping.await()
            assertTrue(finished.isCompleted)
            assertFalse(cancelled)
            assertEquals(1, calls)
            element.drain()
            assertEquals(1, calls)
            closed(element)
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { element.drain(); store.drain() }
        }
    }

    @Test fun projectionFailureFailsBarriersAndDrainAfterJoiningTheOwner() = runTest {
        val store = BoardStoreElement(null, CasStore.inMemory(), parentJob = currentCoroutineContext().job)
        store.open()
        val failure = IllegalStateException("projection failed")
        val element = BoardObservationElement(store, seed = {}, committed = { throw failure }, tick = {},
            capacity = 1, parentJob = currentCoroutineContext().job)
        try {
            element.open()
            element.stopTicker()
            val committed = submit(store, "failure")
            assertEquals(failure.message, assertFailsWith<IllegalStateException> {
                element.awaitSequence(committed.sequence)
            }.message)
            assertEquals(failure.message, assertFailsWith<IllegalStateException> { element.drain() }.message)
            closed(element)
            assertEquals(committed.sequence + 1, submit(store, "after-observer-close").sequence,
                "The failed observer no longer retains a blocked SharedFlow subscriber")
        } finally {
            withContext(NonCancellable) {
                runCatching { element.drain() }
                store.drain()
            }
        }
    }
}
