package borg.trikeshed.kanban

import borg.trikeshed.context.nuid.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BoardActionElementTest {
    @Test fun invalidCapacityOrWorkerCountLeavesNoOwnedJobInTheParent() = runTest {
        val parent = Job()
        try {
            assertFailsWith<IllegalArgumentException> { BoardActionElement(parentJob = parent, capacity = 0) }
            assertTrue(parent.children.none(), "Invalid capacity must fail before allocating an owned supervisor")
            assertFailsWith<IllegalArgumentException> { BoardActionElement(parentJob = parent, workers = 0) }
            assertTrue(parent.children.none(), "Invalid worker count must fail before allocating an owned supervisor")
            parent.complete()
            withTimeout(1_000) { parent.join() }
            assertTrue(parent.isCompleted)
        } finally { parent.cancelAndJoin() }
    }

    @Test fun aFullBoundedStageRetainsEveryAdmittedProducerWithoutBlockingItsCallback() = runTest {
        val owner = BoardActionElement(capacity = 1, workers = 1)
        val callbackLock = Mutex()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val processed = mutableListOf<Int>()
        owner.open()
        try {
            val accepted = callbackLock.withLock {
                (0 until 7).map { ordinal -> assertNotNull(owner.submit {
                    callbackLock.withLock { entered.complete(Unit) }
                    release.await()
                    processed += ordinal
                }) }
            }
            entered.await()
            assertTrue(accepted.none { it.isCompleted })
            owner.stopAdmission()
            assertNull(owner.submit { error("Admission stopped") })
            val draining = async { owner.drain() }
            runCurrent()
            assertFalse(draining.isCompleted)
            release.complete(Unit)
            withTimeout(1_000) { draining.await() }
            accepted.awaitAll()
            assertEquals((0 until 7).toList(), processed)
            assertTrue(owner.supervisor.isCompleted)
            assertFalse(owner.supervisor.children.any())
        } finally { release.complete(Unit); owner.close() }
    }

    @Test fun failedActionsSettleTheirWaitersAndDoNotStrandLaterActionsOrDrain() = runTest {
        val owner = BoardActionElement(capacity = 1, workers = 1)
        owner.open()
        val first = assertNotNull(owner.submit { yield(); throw IllegalArgumentException("first action") })
        val second = assertNotNull(owner.submit { throw IllegalStateException("second action") })
        var recovered = false
        val recovery = assertNotNull(owner.submit { recovered = true })
        try {
            assertEquals("first action", assertFailsWith<IllegalArgumentException> { first.await() }.message)
            assertEquals("second action", assertFailsWith<IllegalStateException> { second.await() }.message)
            recovery.await()
            owner.awaitIdle()
            assertTrue(recovered)
            val workFailure = assertFailsWith<IllegalStateException> {
                owner.prepare { error("Prepared work failed") }
            }
            assertFalse(workFailure is BoardActionElement.AdmissionFailure)
            assertEquals("Prepared work failed", workFailure.message)
            assertNotNull(owner.submit { recovered = false }).await()
            assertFalse(recovered, "A failed preparation must not cancel its owner")
            val failure = assertFailsWith<IllegalStateException> { owner.drain() }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
                it is IllegalArgumentException && it.message == "first action"
            })
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
                it.suppressedExceptions.any { other -> other.message == "second action" }
            })
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
                it.suppressedExceptions.any { other -> other.message == "Prepared work failed" }
            })
            assertTrue(owner.supervisor.isCompleted)
            assertFalse(owner.supervisor.children.any())
        } finally { runCatching { owner.close() } }
    }

    @Test fun externalPreparationStopRetainsLivePreparationAndActionAuthorityButExpiresTheirCarriers() = runTest {
        val owner = BoardActionElement(capacity = 1, workers = 1)
        val prepared = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var carrier: CoroutineContext? = null
        var effects = 0
        owner.open()
        val caller = async {
            owner.prepare {
                carrier = currentCoroutineContext()
                prepared.complete(Unit)
                release.await()
                owner.prepare { effects++ }
            }
        }
        try {
            prepared.await()
            owner.stopPreparations()
            assertFailsWith<BoardActionElement.AdmissionFailure> { owner.prepare { error("External") } }
            withTimeout(1_000) { caller.cancelAndJoin() }
            assertEquals(0, effects)
            val preparations = async { owner.awaitPreparations() }
            runCurrent()
            assertFalse(preparations.isCompleted)
            release.complete(Unit)
            preparations.await()
            caller.join()
            assertEquals(1, effects, "Caller cancellation cannot detach an admitted preparation")
            withContext(checkNotNull(carrier).minusKey(Job)) {
                assertFailsWith<BoardActionElement.AdmissionFailure> { owner.prepare { error("Expired") } }
            }
            assertNotNull(owner.submit { owner.prepare { effects++ } }).await()
            owner.awaitIdle()
            assertEquals(2, effects)
            owner.drain()
            assertFailsWith<BoardActionElement.AdmissionFailure> { owner.prepare { error("Closed") } }
        } finally { release.complete(Unit); caller.cancelAndJoin(); owner.close() }
    }

    @Test fun aCancelledWaiterReturnsWhileItsPreparationAndChildrenRemainDrainOwned() = runTest {
        val owner = BoardActionElement()
        val entered = CompletableDeferred<Unit>()
        val childEntered = CompletableDeferred<Unit>()
        val bodyReleased = CompletableDeferred<Unit>()
        val releaseBody = CompletableDeferred<Unit>()
        val releaseChild = CompletableDeferred<Unit>()
        var effects = 0
        owner.open()
        val caller = async {
            owner.prepare {
                coroutineScope {
                    val executing = currentCoroutineContext().job
                    owner.prepare { assertSame(executing, currentCoroutineContext().job) }
                    assertFailsWith<IllegalStateException> { owner.awaitPreparations() }
                    assertFailsWith<IllegalStateException> { owner.drain() }
                    assertTrue(owner.supervisor.isActive)
                    launch { childEntered.complete(Unit); releaseChild.await(); effects++ }
                    entered.complete(Unit)
                    releaseBody.await()
                    bodyReleased.complete(Unit)
                }
            }
        }
        try {
            entered.await()
            childEntered.await()
            withTimeout(1_000) { caller.cancelAndJoin() }
            assertTrue(caller.isCancelled)
            assertEquals(0, effects)
            val preparations = async { owner.awaitPreparations() }
            val draining = async { owner.drain() }
            runCurrent()
            assertFalse(preparations.isCompleted)
            assertFalse(draining.isCompleted)
            releaseBody.complete(Unit)
            bodyReleased.await()
            runCurrent()
            assertFalse(preparations.isCompleted, "The owned preparation still has a structured child")
            assertFalse(draining.isCompleted)
            releaseChild.complete(Unit)
            withTimeout(1_000) { preparations.await(); draining.await() }
            assertEquals(1, effects)
            assertTrue(owner.supervisor.isCompleted)
            assertFalse(owner.supervisor.children.any())
        } finally {
            releaseBody.complete(Unit)
            releaseChild.complete(Unit)
            caller.cancelAndJoin()
            owner.close()
        }
    }

    @Test fun parentCancellationStopsAndJoinsTheOwnedPreparationAfterItsWaiterLeaves() = runTest {
        val parent = Job()
        val owner = BoardActionElement(parentJob = parent)
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleaned = false
        owner.open()
        val caller = async {
            owner.prepare {
                try { entered.complete(Unit); awaitCancellation() }
                finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        releaseCleanup.await()
                        cleaned = true
                    }
                }
            }
        }
        try {
            entered.await()
            withTimeout(1_000) { caller.cancelAndJoin() }
            val stopping = async { parent.cancelAndJoin() }
            cleaning.await()
            assertFalse(stopping.isCompleted, "The parent still owns the preparation's awaited cleanup")
            assertFalse(cleaned)
            releaseCleanup.complete(Unit)
            withTimeout(1_000) { stopping.await() }
            assertTrue(cleaned)
            assertFalse(parent.children.any())
            assertFalse(owner.supervisor.children.any())
            assertFailsWith<IllegalStateException> { owner.drain() }
            assertTrue(owner.supervisor.isCompleted)
        } finally {
            releaseCleanup.complete(Unit)
            caller.cancelAndJoin()
            parent.cancelAndJoin()
            runCatching { owner.close() }
        }
    }

    @Test fun idleIncludesActionsAdmittedByAnExecutingActionAndSelfJoinFailsBeforeStop() = runTest {
        val owner = BoardActionElement(capacity = 1, workers = 1)
        val childEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        owner.open()
        try {
            assertNotNull(owner.submit {
                assertFailsWith<IllegalStateException> { owner.drain() }
                assertTrue(owner.supervisor.isActive)
                assertNotNull(owner.submit { childEntered.complete(Unit); release.await() })
            }).await()
            childEntered.await()
            val idle = async { owner.awaitIdle() }
            runCurrent()
            assertFalse(idle.isCompleted)
            release.complete(Unit)
            idle.await()
            owner.drain()
        } finally { release.complete(Unit); owner.close() }
    }

    @Test fun onlyTheConfiguredLiveFanoutOwnerCanPrepareAfterExternalStop() = runTest {
        val fanout = NuidFanoutElement()
        val owner = BoardActionElement(dependency = { fanout.currentInvocation() != null })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = nuid(Capability.Process("board"), Nonce.RandomBytes(), Subnet.local)
        var captured: NuidInvocation? = null
        var effects = 0
        fanout.open()
        owner.open()
        fanout.register(Workgroup("board", Subnet.local, traitSpaceOf(request.capability))) {
            captured = currentCoroutineContext()[NuidInvocation]
            entered.complete(Unit)
            release.await()
            owner.prepare { effects++ }
        }
        try {
            val accepted = assertNotNull(fanout.dispatch(request).completion)
            entered.await()
            owner.stopPreparations()
            fanout.stopAdmission()
            withContext(NuidInvocation(checkNotNull(captured).value)) {
                assertFailsWith<BoardActionElement.AdmissionFailure> { owner.prepare { error("Metadata") } }
            }
            release.complete(Unit)
            accepted.await()
            assertEquals(1, effects)
            withContext(checkNotNull(captured).context) {
                assertFailsWith<BoardActionElement.AdmissionFailure> { owner.prepare { error("Expired") } }
            }
            fanout.drain()
            owner.awaitPreparations()
            owner.drain()
        } finally { release.complete(Unit); fanout.close(); owner.close() }
    }
}
