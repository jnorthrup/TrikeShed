package borg.trikeshed.kernel

import borg.trikeshed.lib.*
import keymux.KeyMux
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import modelmux.ModelMux
import kotlin.test.*

class KernelElementTest {
    class Points : KernelPointcut {
        val entered = mutableListOf<KernelTurn>()
        val completed = mutableListOf<KernelReceipt>()
        var closed = false
        override fun dispatch(turn: KernelTurn) { check(!closed); entered.add(turn) }
        override fun complete(receipt: KernelReceipt) { check(!closed); completed.add(receipt) }
        override fun close() { closed = true }
    }

    @Test fun boundedAdmissionDrainsAndFansInSuccessAndFailure() = runTest {
        val kernel = KernelElement(coroutineContext, KernelId("root"))
        val points = Points()
        val seat = kernel.seat(KernelId("tasks"), capacity = 1, pointcut = points)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val first = seat.submit("first") { started.complete(Unit); release.await(); 42 }
        started.await()
        val second = seat.submit<Int>("second") { error("task payload is not an observation") }
        val overflow = async { runCatching { seat.submit("third") { 99 } } }
        runCurrent()
        assertFalse(overflow.isCompleted, "Admission must wait at the bounded channel")
        val drain = async { kernel.drain() }
        runCurrent()
        assertTrue(overflow.await().isFailure)
        assertFalse(drain.isCompleted, "Drain must await the admitted effect")
        release.complete(Unit)
        drain.await()
        assertEquals(42, first.await().getOrThrow())
        assertTrue(second.await().isFailure)
        assertEquals(listOf("first", "second"), points.entered.map { it.name })
        assertEquals(listOf(KernelOutcome.SUCCESS, KernelOutcome.FAILURE), points.completed.map { it.outcome })
        assertEquals("IllegalStateException", points.completed.last().failure)
        assertTrue(points.closed)
        assertEquals(0, kernel.seats().size)
        assertFailsWith<IllegalStateException> { seat.execute("late") { 1 } }
    }

    @Test fun cancellationWaitsForAdmittedWorkAndItsPointcut() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val points = Points()
        var result: Deferred<Result<Int>>? = null
        val owner = launch {
            val kernel = KernelElement(coroutineContext, KernelId("root"))
            try {
                val seat = kernel.seat(KernelId("tasks"), pointcut = points)
                result = seat.submit("owned") { started.complete(Unit); release.await(); 7 }
                awaitCancellation()
            } finally { kernel.drain() }
        }
        started.await()
        owner.cancel()
        runCurrent()
        assertFalse(owner.isCompleted)
        assertFalse(points.closed)
        release.complete(Unit)
        owner.join()
        assertEquals(7, result!!.await().getOrThrow())
        assertEquals(1, points.completed.size)
        assertTrue(points.closed)
    }

    @Test fun cancellationAtAdmissionResumeReturnsItsCapacity() = runTest {
        val kernel = KernelElement(coroutineContext, KernelId("root"))
        val release = CompletableDeferred<Unit>()
        try {
            val seat = kernel.seat(KernelId("tasks"), capacity = 1)
            val started = CompletableDeferred<Unit>()
            val first = seat.submit("first") { started.complete(Unit); release.await(); 1 }
            started.await()
            lateinit var waiting: Deferred<Deferred<Result<Int>>>
            val second = seat.submit("second") {
                // The worker just delivered a permit to the waiting caller, but that
                // caller has not resumed on the test dispatcher yet.
                waiting.cancel()
                2
            }
            var cancelledExecuted = false
            waiting = async { seat.submit("cancelled") { cancelledExecuted = true; -1 } }
            runCurrent()
            assertFalse(waiting.isCompleted)
            release.complete(Unit)
            waiting.join()
            assertEquals(1, first.await().getOrThrow())
            assertEquals(2, second.await().getOrThrow())
            assertFalse(cancelledExecuted)
            assertEquals(3, withTimeout(1000) { seat.execute("after cancellation") { 3 } })
        } finally {
            release.complete(Unit)
            kernel.drain()
        }
    }

    @Test fun childRemovalClosesTheSingleHandleAndModelBoundaryRejectsEscalation() = runTest {
        val root = KernelElement(coroutineContext, KernelId("root"))
        try {
            val child = root.child(KernelId("child"))
            assertEquals("child", root.seats()[0].value)
            assertFailsWith<IllegalArgumentException> {
                child.seat(KernelId("model"), KernelCapability.MODEL)
            }
            val points = Points()
            val seat = child.seat(KernelId("compute"), pointcut = points)
            val mux = ModelMux(KeyMux {}) {}
            assertFailsWith<IllegalStateException> {
                seat.execute("model-boundary") { mux.session("unconfigured") }
            }
            assertEquals(KernelOutcome.FAILURE, points.completed.single().outcome)
            assertEquals(4, seat.execute("compute") {
                assertEquals(child, currentCoroutineContext()[KernelElement])
                2 + 2
            })
            root.remove(child.id)
            assertTrue(points.closed)
            assertEquals(0, root.seats().size)
            assertFailsWith<IllegalStateException> { child.seat(KernelId("late")) }
        } finally { root.drain() }
    }

    @Test fun resultWaitsForCompletionAndPointcutFailureIsReturned() = runTest {
        val kernel = KernelElement(coroutineContext, KernelId("root"))
        var observed = false
        try {
            val seat = kernel.seat(KernelId("tasks"), pointcut = object : KernelPointcut {
                override fun dispatch(turn: KernelTurn) {}
                override fun complete(receipt: KernelReceipt) { observed = true; error("pointcut unavailable") }
                override fun close() {}
            })
            val result = seat.submit("task") { "value" }.await()
            assertTrue(observed)
            assertEquals("pointcut unavailable", result.exceptionOrNull()?.message)
        } finally { kernel.drain() }
    }
}
