package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

/** Resource admission and awaited completion of the existing per-case owner, without HTTP orchestration. */
@OptIn(ExperimentalCoroutinesApi::class)
class CouncilCaseOwnershipTest {
    private val plan get() = TribunalInstance.schemaPlan()

    private suspend fun <T> owned(cap: Int = 2,
                                 body: suspend (CouncilCaseRegistry, CompletableJob, CasStore, MutableMap<String, JobLog>) -> T): T = coroutineScope {
        val owner = SupervisorJob(currentCoroutineContext()[Job])
        val scope = CoroutineScope(currentCoroutineContext() + owner)
        val cas = CasStore.inMemory()
        val logs = mutableMapOf<String, JobLog>()
        val cases = CouncilCaseRegistry(scope, plan, cap, componentFactories = { id ->
            JobNexusComponentFactories(casStoreFactory = CasStoreFactory { cas },
                walFactory = JobLogFactory { logs.getOrPut(id) { JobLog.inMemory() } })
        })
        try { body(cases, owner, cas, logs) }
        finally {
            try { cases.drain() }
            finally { owner.complete(); owner.join() }
        }
    }

    private suspend fun seed(case: CouncilCaseRegistry.Case) = case.seed("seed-${case.caseId}", "start-${case.caseId}")

    @Test fun parentAndCapacityAreValidatedBeforeCaseAllocation() = runTest {
        assertFailsWith<IllegalArgumentException> { CouncilCaseRegistry(object : CoroutineScope { override val coroutineContext = EmptyCoroutineContext }, plan) }
        val owner = SupervisorJob(coroutineContext[Job])
        try {
            assertFailsWith<IllegalArgumentException> { CouncilCaseRegistry(CoroutineScope(coroutineContext + owner), plan, 0) }
            assertTrue(owner.children.none())
        } finally { owner.complete(); owner.join() }
    }

    @Test fun activeOwnersRefuseTheNextCaseBeforeOpeningItsFactory() = runTest {
        owned { cases, _, _, logs ->
            for (id in listOf("a", "b")) cases.withCase(id, create = true) { case ->
                seed(case)
                assertEquals("active", case.snapshot()?.lifecycle)
            }
            assertEquals(2, cases.liveCases())
            val capacity = assertFailsWith<CouncilCaseRegistry.Capacity> {
                cases.withCase("c", create = true) { error("capacity refusal must precede callback") }
            }
            assertEquals(2, capacity.limit)
            assertEquals(setOf("a", "b"), logs.keys, "No third nexus/log factory was opened")
            assertEquals(2, cases.liveCases())
        }
    }

    @Test fun terminalEvictionWaitsForRunningAndQueuedOperationPins() = runTest {
        owned(cap = 1) { cases, owner, _, _ ->
            val recorded = cases.recordRuling("old", "verdict", "transcript")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var oldOwner: Job? = null
            val first = async {
                cases.withCase("old", create = false) { case ->
                    oldOwner = owner.children.single().children.single()
                    entered.complete(Unit)
                    release.await() // The admitted caller still owes its outer receipt.
                }
            }
            entered.await()
            val second = async { cases.withCase("old", create = false) { case -> assertEquals("closed", case.snapshot()?.lifecycle) } }
            try {
                runCurrent()
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted, "The second operation is admitted but waiting on the same mutex")
                assertFailsWith<CouncilCaseRegistry.Capacity> {
                    cases.withCase("new", create = true) { error("Pinned terminal owner cannot be evicted") }
                }
                assertTrue(assertNotNull(oldOwner).isActive)
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) { first.join(); second.join() }
            }
            first.await(); second.await()
            cases.withCase("new", create = true) { seed(it) }
            assertTrue(assertNotNull(oldOwner).isCompleted, "Victim nexus actually drained before the slot was reused")
            assertEquals(1, cases.liveCases())
            assertEquals(recorded, cases.recordRuling("old", "verdict", "transcript"), "Evicted terminal cache keeps its original receipt")
        }
    }

    @Test fun drainKeepsTheAdmittedCallbackAliveThroughItsReceiptBoundary() = runTest {
        owned { cases, owner, _, _ ->
            val entered = CompletableDeferred<Job>()
            val receipt = CompletableDeferred<Unit>()
            var finalReceipt: ContentId? = null
            val operation = async {
                cases.withCase("active", create = true) { case ->
                    seed(case)
                    entered.complete(owner.children.single().children.single())
                    receipt.await()
                    val revision = assertNotNull(case.snapshot()).revision
                    finalReceipt = case.advance(JobCommand.Update(case.jobId, "outer-receipt", revision)).cid
                }
            }
            val caseOwner = entered.await()
            val drain = async { cases.drain() }
            try {
                runCurrent()
                assertFalse(drain.isCompleted)
                assertTrue(caseOwner.isActive, "Stopping admission does not cancel already admitted work")
                assertFailsWith<IllegalStateException> {
                    cases.withCase("late", create = true) { error("draining owner admitted new work") }
                }
            } finally {
                receipt.complete(Unit)
                withContext(NonCancellable) { operation.join(); drain.join() }
            }
            operation.await(); drain.await()
            assertNotNull(finalReceipt)
            assertTrue(caseOwner.isCompleted)
            assertEquals(0, cases.liveCases())
            assertTrue(owner.children.none(), "Every per-case child completed and joined")
        }
    }

    @Test fun cancellationKeepsTheAdmittedCommandOwnedThroughAcknowledgementAndDrain() = runTest {
        owned { cases, owner, _, logs ->
            cases.withCase("queued", create = true) { seed(it) }
            val initial = assertNotNull(cases.revision("queued"))
            val caseOwner = owner.children.single().children.single()
            val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                cases.withCase("queued", create = false) { case ->
                    case.advance(JobCommand.Update(case.jobId, "queued-update", initial))
                }
            }
            operation.cancel() // Standard dispatcher has not run the nexus consumer yet.
            val drain = async(start = CoroutineStart.UNDISPATCHED) { cases.drain() }
            assertFalse(operation.isCompleted, "Cancellation cannot abandon an admitted command acknowledgement")
            assertFalse(drain.isCompleted, "Drain still owns the cancelled caller's admitted command")
            operation.join(); drain.await()
            assertEquals(1, assertNotNull(logs["queued"]).replay().count { frame ->
                CanonicalCbor.decodeJobCommand(frame.payload).idempotencyKey == "queued-update"
            })
            assertEquals(initial + 1, cases.revision("queued"))
            assertTrue(caseOwner.isCompleted)
            assertTrue(owner.children.none())
        }
    }

    @Test fun cancelledLegacyRecordSettlesItsSeedTerminalCommandAndOriginalReceipt() = runTest {
        owned { cases, _, _, logs ->
            val original = launch(start = CoroutineStart.UNDISPATCHED) {
                cases.recordRuling("legacy", "verdict", "transcript")
            }
            original.cancel()
            assertFalse(original.isCompleted, "Legacy terminal admission owns the seed acknowledgement too")
            val retry = async { cases.recordRuling("legacy", "verdict", "transcript") }
            original.join()
            val cid = retry.await()
            assertEquals(cid, cases.recordedCid("legacy"))
            assertEquals("closed", cases.lifecycle("legacy"))
            val commands = assertNotNull(logs["legacy"]).replay().map { CanonicalCbor.decodeJobCommand(it.payload) }.toList()
            assertEquals(1, commands.count { it is JobCommand.Submit })
            assertEquals(1, commands.count { it is JobCommand.Complete })
        }
    }

    @Test fun unknownReadLeavesNoHeadlessOwnerAndCancelledParentCannotAdmit() = runTest {
        owned { cases, owner, _, _ ->
            assertFailsWith<CouncilCaseRegistry.Missing> { cases.withCase("unknown", create = false) { error("unknown read") } }
            assertEquals(0, cases.liveCases())
            assertTrue(owner.children.single().children.none())
        }
        val owner = SupervisorJob(coroutineContext[Job])
        val cases = CouncilCaseRegistry(CoroutineScope(coroutineContext + owner), plan)
        owner.cancelAndJoin()
        assertFailsWith<IllegalStateException> { cases.withCase("late", create = true) { error("cancelled parent admitted") } }
        cases.drain()
    }

    @Test fun parentCancellationDuringPreparationOrReplayCannotDeliverAnInactiveOwner() = runTest {
        for (duringReplay in listOf(false, true)) {
            val owner = SupervisorJob(coroutineContext[Job])
            val log = JobLog.inMemory()
            if (duringReplay) log.append(1L, CanonicalCbor.encode(JobCommand.Submit(JobId.of("case/stopped"), "seed")))
            val cas = object : CasStore() {
                override fun sync() { super.sync(); if (duringReplay) owner.cancel() }
            }
            val cases = CouncilCaseRegistry(CoroutineScope(coroutineContext + owner), plan,
                componentFactories = {
                    if (!duringReplay) owner.cancel()
                    JobNexusComponentFactories(casStoreFactory = CasStoreFactory { cas }, walFactory = JobLogFactory { log })
                })
            var delivered = false
            try {
                assertFailsWith<IllegalStateException> {
                    cases.withCase("stopped", create = true) { delivered = true; error("inactive owner delivered") }
                }
                assertFalse(delivered)
                assertEquals(0, cases.liveCases())
                assertTrue(owner.children.none(), "Inactive construction children were joined before refusal")
            } finally { cases.drain(); owner.cancelAndJoin() }
        }
    }

    @Test fun explicitAbortDuringFactoryOrReplayCannotDeliverAnotherLiveCase() = runTest {
        for (duringReplay in listOf(false, true)) {
            val owner = SupervisorJob(coroutineContext[Job])
            val log = JobLog.inMemory()
            if (duringReplay) log.append(1L, CanonicalCbor.encode(JobCommand.Submit(JobId.of("case/aborted"), "seed")))
            lateinit var cases: CouncilCaseRegistry
            val cas = object : CasStore() {
                override fun sync() { super.sync(); if (duringReplay) cases.close() }
            }
            cases = CouncilCaseRegistry(CoroutineScope(coroutineContext + owner), plan,
                componentFactories = {
                    if (!duringReplay) cases.close()
                    JobNexusComponentFactories(casStoreFactory = CasStoreFactory { cas }, walFactory = JobLogFactory { log })
                })
            try {
                assertFailsWith<IllegalStateException> {
                    cases.withCase("aborted", create = true) { error("abort admitted a callback") }
                }
                assertTrue(owner.isActive, "Registry abort does not cancel its caller's owner")
                assertEquals(0, cases.liveCases())
                cases.drain()
                assertTrue(owner.children.none(), "Aborted concurrent construction was cancelled and joined")
            } finally { cases.drain(); owner.complete(); owner.join() }
        }
    }

    @Test fun abortOrParentCancellationWakesAnAdmittedWaiterBeforeTheReactorStarts() = runTest {
        for (stopParent in listOf(false, true)) owned { cases, owner, _, _ ->
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    cases.withCase("unstarted", create = true) { case ->
                        case.advance(JobCommand.Submit(case.jobId, "admitted"))
                    }
                }
            }
            assertFalse(result.isCompleted)
            if (stopParent) owner.cancel() else cases.close()
            assertTrue(result.await().isFailure, "Stopped unstarted reactor closes its acknowledgement channel")
            cases.drain()
            assertTrue(owner.children.none())
            assertEquals(!stopParent, owner.isActive)
        }
    }

    @Test fun sameCaseReentrancyAndSelfDrainRefuseBeforeWaiting() = runTest {
        owned { cases, _, _, _ ->
            cases.withCase("a", create = true) { case ->
                seed(case)
                assertFailsWith<IllegalStateException> { cases.withCase("a", create = false) { error("nested same case") } }
                assertFailsWith<IllegalStateException> { cases.recordMistrial("a", "nested") }
                assertFailsWith<IllegalStateException> { cases.drain() }
            }
            cases.withCase("b", create = true) { seed(it) }
            assertEquals(2, cases.liveCases(), "Rejected self-drain did not stop external admission")
        }
    }

    @Test fun ancestorCasesAndRegistriesCannotReenterOrDrainTheirOwnOutstandingCallback() = runTest {
        owned { outer, _, _, _ ->
            outer.withCase("a", create = true) { seed(it)
                outer.withCase("b", create = true) { seed(it)
                    assertFailsWith<IllegalStateException> { outer.withCase("a", create = false) { error("ancestor case entered") } }
                }
                owned { inner, _, _, _ ->
                    inner.withCase("c", create = true) { seed(it)
                        assertFailsWith<IllegalStateException> { outer.drain() }
                        assertFailsWith<IllegalStateException> { outer.withCase("a", create = false) { error("cross-registry ancestor entered") } }
                    }
                }
            }
        }
    }

    @Test fun anEscapedViewCannotSeedOrAdvanceAfterItsPinEnds() = runTest {
        owned { cases, _, _, logs ->
            val leaked = cases.withCase("escaped", create = true) { it }
            assertFailsWith<IllegalStateException> { leaked.seed("seed", "start") }
            assertFailsWith<IllegalStateException> { leaked.advance(JobCommand.Submit(leaked.jobId, "escaped")) }
            assertFailsWith<IllegalStateException> { leaked.snapshot() }
            assertTrue(assertNotNull(logs["escaped"]).replay().none())
            cases.drain()
            assertFailsWith<IllegalStateException> { leaked.seed("after-drain", "start") }
            assertFailsWith<IllegalStateException> { leaked.advance(JobCommand.Submit(leaked.jobId, "after-drain")) }
            assertTrue(assertNotNull(logs["escaped"]).replay().none())
        }
    }

    @Test fun callbackChildrenSerializeSeedAndSettleTheirOwnRequestKeys() = runTest {
        owned { cases, _, _, logs ->
            cases.withCase("parallel", create = true) { case ->
                coroutineScope { List(2) { async { seed(case) } }.awaitAll() }
                assertEquals(1, assertNotNull(logs["parallel"]).replay().count {
                    CanonicalCbor.decodeJobCommand(it.payload) is JobCommand.Submit
                })
                val revision = assertNotNull(case.snapshot()).revision
                val results = supervisorScope {
                    listOf("one", "two").map { key -> async {
                        key to runCatching { case.advance(JobCommand.Update(case.jobId, key, revision)) }
                    } }.awaitAll()
                }
                val accepted = results.single { it.second.isSuccess }
                val rejected = results.single { it.second.isFailure }
                val commit = accepted.second.getOrThrow()
                assertEquals(commit.cid, case.receipt(accepted.first))
                assertEquals(revision + 1, commit.snapshot.revision)
                assertNull(case.receipt(rejected.first))
                assertContains(rejected.second.exceptionOrNull()?.message.orEmpty(), rejected.first)
                assertEquals(revision + 1, case.snapshot()?.revision)
            }
        }
    }

    @Test fun cancelledRequestCannotLendItsAcknowledgementToAChangedRequestWithTheSameKey() = runTest {
        owned { cases, _, _, logs ->
            cases.withCase("identity", create = true) { seed(it) }
            val revision = assertNotNull(cases.revision("identity"))
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                cases.withCase("identity", create = false) { case ->
                    case.advance(JobCommand.Update(case.jobId, "same-key", revision, title = "original"))
                }
            }
            first.cancel()
            val changed = async {
                assertFailsWith<IllegalStateException> {
                    cases.withCase("identity", create = false) { case ->
                        case.advance(JobCommand.Update(case.jobId, "same-key", revision + 1, title = "changed"))
                    }
                }
            }
            first.join(); changed.await()
            assertEquals(revision + 1, cases.revision("identity"))
            assertEquals(1, assertNotNull(logs["identity"]).replay().count {
                CanonicalCbor.decodeJobCommand(it.payload).idempotencyKey == "same-key"
            }, "A settled accepted identity is refused before another payload is queued")
        }
    }

    @Test fun terminalViewsCannotResurrectAnOwnerBeforeCapacityEviction() = runTest {
        owned(cap = 1) { cases, owner, _, _ ->
            cases.withCase("terminal", create = true) { case ->
                seed(case)
                val terminal = case.advance(JobCommand.Complete(case.jobId, "complete", assertNotNull(case.snapshot()).revision))
                assertEquals("closed", terminal.snapshot.lifecycle)
                assertFailsWith<IllegalStateException> {
                    case.advance(JobCommand.Start(case.jobId, "same-callback-resurrect", terminal.snapshot.revision))
                }
                assertFailsWith<IllegalStateException> { case.seed("seed-again", "start-again") }
            }
            val oldOwner = owner.children.single().children.single()
            val revision = assertNotNull(cases.revision("terminal"))
            cases.withCase("terminal", create = false) { case ->
                assertEquals("closed", case.snapshot()?.lifecycle)
                assertFailsWith<IllegalStateException> {
                    case.advance(JobCommand.Start(case.jobId, "later-resurrect", revision))
                }
            }
            cases.withCase("new", create = true) { seed(it) }
            assertTrue(oldOwner.isCompleted)
            assertEquals("closed", cases.lifecycle("terminal"))
            assertEquals(revision, cases.revision("terminal"))
            assertEquals("active", cases.lifecycle("new"))
        }
    }

    @Test fun terminalOnlyRecordCannotCloseAStateBoundActiveCase() = runTest {
        owned { cases, _, cas, _ ->
            cases.withCase("fsm", create = true) { case ->
                seed(case)
                val head = cas.put(CanonicalCbor.encodeMap(mapOf("phase" to "idle")))
                val revision = assertNotNull(case.snapshot()).revision
                val key = ContentId.of(borg.trikeshed.parse.jsonOf(listOf("council-ruling", "fsm", "verdict", "transcript"))
                    .encodeToByteArray()).value
                val committed = case.advance(JobCommand.State(case.jobId, key, revision, null, head.value))
                assertEquals(head.value, committed.snapshot.stateCid)
                assertEquals(committed.cid, case.receipt(key))
            }
            val revision = cases.revision("fsm")
            assertFailsWith<IllegalStateException> { cases.recordRuling("fsm", "verdict", "transcript") }
            assertFailsWith<IllegalStateException> { cases.recordMistrial("fsm", "mistrial") }
            assertEquals(revision, cases.revision("fsm"))
            assertEquals("active", cases.lifecycle("fsm"))
        }
    }
}
