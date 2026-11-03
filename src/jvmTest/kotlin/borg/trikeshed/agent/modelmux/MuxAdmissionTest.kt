package borg.trikeshed.agent.modelmux

import kotlinx.coroutines.*
import kotlin.test.*

class MuxAdmissionTest {
    @Test fun physicalPoolLimitsDistinctBindingsAndReleasesCancelledWaiters() = runBlocking {
        val owner = MuxAdmission(coroutineContext[Job]!!, workers = 4, capacity = 8, pools = mapOf(
            "a" to MuxPool("account", 2), "b" to MuxPool("account", 2), "c" to MuxPool("account", 2),
        ))
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val thirdEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val first = async { owner.run("a") { firstEntered.complete(Unit); release.await() } }
            val second = async { owner.run("b") { secondEntered.complete(Unit); release.await() } }
            withTimeout(5_000) { firstEntered.await(); secondEntered.await() }
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { owner.run("c") { thirdEntered.complete(Unit) } }
            assertNull(withTimeoutOrNull(150) { thirdEntered.await() })
            cancelled.cancelAndJoin()
            release.complete(Unit)
            withTimeout(5_000) { first.await(); second.await() }
            withTimeout(5_000) { owner.run("c") { thirdEntered.complete(Unit) } }
            assertTrue(thirdEntered.isCompleted)
        } finally {
            release.complete(Unit)
            owner.drain()
        }
    }

    @Test fun rejectsConflictingPhysicalPoolCapacities(): Unit = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            MuxAdmission(coroutineContext[Job]!!, pools = mapOf("a" to MuxPool("account", 1), "b" to MuxPool("account", 2)))
        }
    }

    @Test fun boundedQueueSkipsCanceledWorkAndDrainsActiveTransport(): Unit = runBlocking {
        val owner = MuxAdmission(coroutineContext[Job]!!, workers = 1, capacity = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val invoked = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            owner.run("key") {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { owner.run("key") { invoked.complete(Unit) } }
        assertFailsWith<IllegalStateException> { owner.run("key") { Unit } }
        first.cancel()
        second.cancel()
        val closing = async(start = CoroutineStart.UNDISPATCHED) { owner.drain() }
        assertFalse(closing.isCompleted)
        assertFalse(first.isCompleted)
        release.complete(Unit)
        closing.await()
        first.join()
        second.join()
        assertFalse(invoked.isCompleted)
        assertFailsWith<IllegalStateException> { owner.run("key") { Unit } }
    }

    @Test fun distinctBindingsRunTogetherWhileOneBindingIsSerial() = runBlocking {
        val owner = MuxAdmission(coroutineContext[Job]!!, workers = 3, capacity = 4)
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val otherStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val first = async { owner.run("a") { firstStarted.complete(Unit); release.await() } }
            firstStarted.await()
            val second = async { owner.run("a") { secondStarted.complete(Unit) } }
            val other = async { owner.run("b") { otherStarted.complete(Unit) } }
            withTimeout(5_000) { otherStarted.await() }
            assertFalse(secondStarted.isCompleted)
            release.complete(Unit)
            first.await(); second.await(); other.await()
            assertTrue(secondStarted.isCompleted)
        } finally {
            release.complete(Unit)
            owner.drain()
        }
    }

    @Test fun waitingOnOneBindingDoesNotBlockAnotherBinding(): Unit = runBlocking {
        val owner = MuxAdmission(coroutineContext[Job]!!, workers = 2, capacity = 16)
        val entered = CompletableDeferred<Unit>()
        val other = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val first = async { owner.run("a") { entered.complete(Unit); release.await() } }
            entered.await()
            val waiting = mutableListOf<Deferred<Unit>>()
            repeat(8) { waiting.add(async(start = CoroutineStart.UNDISPATCHED) { owner.run("a") { Unit } }) }
            val independent = async(start = CoroutineStart.UNDISPATCHED) { owner.run("b") { other.complete(Unit) } }
            withTimeout(5_000) { other.await() }
            assertFalse(first.isCompleted)
            release.complete(Unit)
            first.await()
            waiting.awaitAll()
            independent.await()
        } finally {
            release.complete(Unit)
            owner.drain()
        }
    }
}
