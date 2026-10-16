package borg.trikeshed.userspace.nio.process

import borg.trikeshed.vm.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessCallsTest {
    @Test
    fun boundedAdmissionFansOutAndDrainJoinsTheReplyWriter() = runTest {
        val parent = SupervisorJob()
        val release = CompletableDeferred<Unit>()
        val write = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val replies = mutableListOf<ProcessCallReply>()
        val calls = ProcessCalls(coroutineContext + parent, concurrency = 2, capacity = 1) {
            write.await()
            replies.add(it)
        }
        val admitted = (1..3).map { id -> assertNotNull(calls.trySubmit(id, "parallel", null) {
            assertSame(calls, currentCoroutineContext()[ProcessCalls])
            entered.add(id)
            release.await()
            Teleported.Num(id.toLong())
        }) }
        assertNull(calls.trySubmit(4, "overflow", null) { error("not admitted") })
        runCurrent()
        assertEquals(listOf(1, 2), entered)
        val drained = async(start = CoroutineStart.UNDISPATCHED) { calls.drain() }
        assertNull(calls.trySubmit(5, "closed", null) { error("not admitted") })
        release.complete(Unit)
        runCurrent()
        assertTrue(admitted.all { it.isCompleted })
        assertEquals(listOf(1, 2, 3), entered)
        assertFalse(drained.isCompleted, "Drain must join reply delivery, not just callback execution")
        write.complete(Unit)
        drained.await()
        assertEquals(listOf(1, 2, 3), replies.map { it.a }.sortedBy { it })
        assertTrue(parent.children.none())
        parent.complete()
        parent.join()
    }

    @Test
    fun deadlinesIncludePermitWaitAndDoNotRunExpiredQueuedEffects() = runTest {
        val parent = SupervisorJob()
        val release = CompletableDeferred<Unit>()
        val replies = mutableListOf<ProcessCallReply>()
        val calls = ProcessCalls(coroutineContext + parent, concurrency = 1, capacity = 1,
            clock = testScheduler.timeSource) { replies.add(it) }
        val first = assertNotNull(calls.trySubmit(1, "hold", null) { release.await(); Teleported.Null })
        val queued = assertNotNull(calls.trySubmit(2, "queued", 100) { error("expired queued effect ran") })
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals("process call 'queued' exceeded 100ms", queued.await().b.exceptionOrNull()?.message)
        assertFalse(first.isCompleted)
        release.complete(Unit)
        calls.drain()
        assertEquals(listOf(2, 1), replies.map { it.a })
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test
    fun deadlineIncludesDispatchDelayAndJoinsCancellationCleanup() = runTest {
        val parent = SupervisorJob()
        val cleanup = CompletableDeferred<Unit>()
        var cleaning = false
        var replied = false
        val clock = TestTimeSource()
        val calls = ProcessCalls(coroutineContext + parent, clock = clock) { replied = true }
        val expired = assertNotNull(calls.trySubmit(1, "before-dispatch", 10) { error("expired effect ran") })
        clock += 10.milliseconds // Advance the admission clock without allowing the dispatcher to run.
        runCurrent()
        assertEquals("process call 'before-dispatch' exceeded 10ms", expired.await().b.exceptionOrNull()?.message)
        replied = false
        val held = assertNotNull(calls.trySubmit(2, "cleanup", 100) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleaning = true; cleanup.await() } }
        })
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(cleaning)
        assertFalse(held.isCompleted)
        assertFalse(replied)
        cleanup.complete(Unit)
        runCurrent()
        assertEquals("process call 'cleanup' exceeded 100ms", held.await().b.exceptionOrNull()?.message)
        assertTrue(replied)
        calls.drain()
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test
    fun nestedDeadlineRetainsItsOwnFailure() = runTest {
        val parent = SupervisorJob()
        val calls = ProcessCalls(coroutineContext + parent) { }
        var nested: TimeoutCancellationException? = null
        val result = assertNotNull(calls.trySubmit(1, "outer", 1000) {
            try { withTimeout(10) { awaitCancellation() } }
            catch (failure: TimeoutCancellationException) { nested = failure; throw failure }
        })
        advanceUntilIdle()
        val failure = assertNotNull(result.await().b.exceptionOrNull()?.message)
        assertEquals(assertNotNull(nested).message, failure, "Preserve the nested failure, not the outer lease")
        assertFalse("1000" in failure, failure)
        calls.drain()
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test
    fun abandonedResultAndCancelledDrainWaiterDoNotDiscardAdmittedEffects() = runTest {
        val parent = SupervisorJob()
        val release = CompletableDeferred<Unit>()
        val replies = mutableListOf<ProcessCallReply>()
        var finished = false
        val calls = ProcessCalls(coroutineContext + parent) { replies.add(it) }
        val result = assertNotNull(calls.trySubmit(1, "owned", null) {
            release.await()
            finished = true
            Teleported.Num(42)
        })
        runCurrent()
        result.cancel()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { calls.drain() }
        waiter.cancel()
        runCurrent()
        assertFalse(waiter.isCompleted, "Cancelling a drain waiter must not detach admitted work")
        assertFalse(finished)
        assertNull(calls.trySubmit(2, "closed", null) { error("not admitted") })
        release.complete(Unit)
        waiter.join()
        assertTrue(finished)
        assertEquals(Teleported.Num(42), replies.single().b.getOrThrow())
        calls.drain() // Repeated drain observes the same joined completion.
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test
    fun finalOperationIsReservedAtCapacityAndRunsAfterSerialAdmissions() = runTest {
        val parent = SupervisorJob()
        val release = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val replies = mutableListOf<ProcessCallReply>()
        val calls = ProcessCalls(coroutineContext + parent, concurrency = 1, capacity = 1) {
            written.await()
            replies.add(it)
        }
        val first = assertNotNull(calls.trySubmit(1, "first", null) {
            entered.add(1); release.await(); Teleported.Num(1)
        })
        val second = assertNotNull(calls.trySubmit(2, "second", null) {
            entered.add(2); Teleported.Num(2)
        })
        assertNull(calls.trySubmit(3, "overflow", null) { error("not admitted") })
        val last = assertNotNull(calls.finish(4, "close") { entered.add(4); Teleported.Null })
        assertNull(calls.finish(5, "second-close") { error("not admitted") })
        assertNull(calls.trySubmit(6, "closed", null) { error("not admitted") })
        runCurrent()
        assertEquals(listOf(1), entered)
        assertFalse(last.isCompleted)
        val drained = async { calls.drain() }
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2, 4), entered)
        assertEquals(Teleported.Num(1), first.await().b.getOrThrow())
        assertEquals(Teleported.Num(2), second.await().b.getOrThrow())
        assertEquals(Teleported.Null, last.await().b.getOrThrow())
        assertFalse(drained.isCompleted, "Final operation completion is not final reply delivery")
        written.complete(Unit)
        drained.await()
        assertEquals(listOf(1, 2, 4), replies.map { it.a })
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test
    fun finalOperationRetainsItsFailureAndClosesAdmission() = runTest {
        val parent = SupervisorJob()
        val replies = mutableListOf<ProcessCallReply>()
        val calls = ProcessCalls(coroutineContext + parent) { replies.add(it) }
        val cause = IllegalStateException("context close failed")
        val last = assertNotNull(calls.finish(1, "close") { throw cause })
        calls.drain()
        assertSame(cause, last.await().b.exceptionOrNull())
        assertSame(cause, replies.single().b.exceptionOrNull())
        assertNull(calls.trySubmit(2, "closed", null) { error("not admitted") })
        assertTrue(parent.children.none())
        parent.complete()
    }

    @Test
    fun parentCancellationSettlesCallsEvenBeforeDispatchStarts() = runTest {
        for (start in listOf(false, true)) {
            val parent = SupervisorJob()
            var settled = false
            val calls = ProcessCalls(coroutineContext + parent, concurrency = 1, capacity = 1) { error("no reply expected") }
            val active = assertNotNull(calls.trySubmit(1, "active", null) {
                try { awaitCancellation() } finally { settled = true }
            })
            val queued = assertNotNull(calls.trySubmit(2, "queued", null) { error("queued callback ran") })
            val final = assertNotNull(calls.finish(3, "close") { error("final operation ran after cancellation") })
            if (start) runCurrent()
            parent.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { active.await() }
            assertFailsWith<CancellationException> { queued.await() }
            assertFailsWith<CancellationException> { final.await() }
            assertEquals(start, settled)
            assertNull(calls.trySubmit(3, "closed", null) { error("not admitted") })
            assertFailsWith<CancellationException> { calls.drain() }
            parent.join()
            assertTrue(parent.children.none())
        }
    }

    @Test
    fun callbackFailureIsAReplyButTransportFailureClosesTheComposition() = runTest {
        val parent = SupervisorJob()
        val replies = mutableListOf<ProcessCallReply>()
        val calls = ProcessCalls(coroutineContext + parent) { replies.add(it) }
        val failed = assertNotNull(calls.trySubmit(1, "failure", null) { error("delegate failure") })
        runCurrent()
        assertEquals("delegate failure", failed.await().b.exceptionOrNull()?.message)
        val next = assertNotNull(calls.trySubmit(2, "success", null) { Teleported.Num(42) })
        calls.drain()
        assertEquals(Teleported.Num(42), next.await().b.getOrThrow())
        assertEquals(2, replies.size)
        val broken = ProcessCalls(coroutineContext + parent) { error("wire closed") }
        assertNotNull(broken.trySubmit(3, "wire", null) { Teleported.Null })
        runCurrent()
        assertEquals("wire closed", assertFailsWith<IllegalStateException> { broken.drain() }.message)
        assertNull(broken.trySubmit(4, "closed", null) { error("not admitted") })
        assertTrue(parent.children.none())
        parent.complete()
    }
}
