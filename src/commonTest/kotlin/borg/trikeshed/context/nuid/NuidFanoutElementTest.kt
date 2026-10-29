package borg.trikeshed.context.nuid

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NuidFanoutElementTest {

    @Test
    fun acceptedClaimsRetainIdentityBeyondTheChannelCapacity() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        val request = nuid(Capability.Process("test_process"), Nonce.RandomBytes(), Subnet.core)
        fanout.register(Workgroup("worker", Subnet.core,
            TraitSpace { 1 j { Capability.Process("test_process") } }))
        val slot = assertNotNull(fanout.slotOf("worker"))
        val accepted = mutableListOf<NuidFanoutElement.Claim>()
        // Start with a waiting production consumer, then send more claims than
        // the bounded accepted channel can hold without a reader.
        val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
            repeat(256) { accepted += slot.consumeAccepted() }
        }
        try {
            val dispatched = mutableListOf<NuidFanoutElement.DispatchResult>()
            repeat(256) { ordinal -> dispatched += fanout.dispatch(request, ordinal) }
            consumer.join()
            assertEquals(256, accepted.size)
            dispatched.forEachIndexed { ordinal, result ->
                assertEquals("worker", result.winner)
                assertEquals(result.claimId, accepted[ordinal].claimId)
                assertEquals(ordinal, accepted[ordinal].payload)
                assertEquals(request, accepted[ordinal].nuid)
            }
        } finally {
            consumer.cancelAndJoin()
            fanout.close()
        }
    }

    @Test
    fun containingPrefixesRouteNearestFirstWithinTheEscalationBudget() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        val traits = traitSpaceOf(Capability.ProcessAll)
        val request = nuid(Capability.Process("execute"), Nonce.RandomBytes(), Subnet.parse("global.local.process.task"))
        try {
            fanout.register(Workgroup("outer", Subnet.parse("global"), traits))
            fanout.register(Workgroup("unrelated", Subnet.parse("global.other.process.task"), traits))
            fanout.register(Workgroup("capability", request.subnet, traitSpaceOf(Capability.CasAll)))
            fanout.register(Workgroup("inner", Subnet.parse("global.local.process"), traits))
            val result = fanout.dispatch(request, "work")
            assertEquals("inner", result.winner)
            assertEquals("global.local.process", result.claimedAtSubnet.toString())
            assertEquals(1, result.escalatedLevels)
            assertNull(result.completion, "An admission notification is not completed work")
            assertEquals(result.claimId, assertNotNull(fanout.slotOf("inner")).consumeAccepted().claimId)
        } finally { fanout.close() }

        val limited = NuidFanoutElement(escalationBudget = 1)
        limited.open()
        try {
            limited.register(Workgroup("outer", Subnet.parse("global"), traits))
            assertNull(limited.dispatch(request).winner, "Missing intermediate workers do not erase subnet distance")
        } finally { limited.close() }
    }

    @Test
    fun concurrentDispatchRetainsEveryClaimAndDrainWaitsForAdmission() = runTest {
        val fanout = NuidFanoutElement(capacity = 32)
        fanout.open()
        val request = nuid(Capability.Process("execute"), Nonce.RandomBytes(), Subnet.core)
        fanout.register(Workgroup("worker", Subnet.core, traitSpaceOf(Capability.ProcessAll)))
        val slot = assertNotNull(fanout.slotOf("worker"))
        val accepted = mutableListOf<NuidFanoutElement.Claim>()
        val seeded = (0 until 32).map { fanout.dispatch(request, it) }
        val waiting = (32 until 56).map { ordinal -> async { fanout.dispatch(request, ordinal, 1_000) } }
        runCurrent()
        val draining = async { fanout.drain() }
        runCurrent()
        assertTrue(fanout.stopped.isCompleted && !fanout.supervisor.isCompleted)
        assertFalse(draining.isCompleted, "A full accepted channel still owns admitted dispatches")
        assertFailsWith<IllegalStateException> { fanout.dispatch(request, "late") }
        val consumer = launch { repeat(56) { accepted += slot.consumeAccepted() } }
        try {
            val results = seeded + waiting.awaitAll()
            consumer.join()
            draining.await()
            assertEquals(List<Any?>(56) { it }, accepted.map { it.payload })
            assertEquals(results.map { it.claimId }, accepted.map { it.claimId })
            assertEquals(56, results.map { it.claimId }.distinct().size)
            assertTrue(results.all { it.winner == "worker" })
            assertTrue(fanout.supervisor.isCompleted)
            assertFalse(fanout.supervisor.children.any())
        } finally {
            consumer.cancelAndJoin()
            fanout.close()
        }
    }

    @Test
    fun handlerCompletionAndFailureRemainDistinctFromAdmissionAndDrain() = runTest {
        val fanout = NuidFanoutElement(parentJob = currentCoroutineContext().job, capacity = 2)
        fanout.open()
        val request = nuid(Capability.Process("execute"), Nonce.RandomBytes(), Subnet.core)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = IllegalArgumentException("declined work")
        val processed = mutableListOf<Any?>()
        fanout.register(Workgroup("worker", Subnet.core, traitSpaceOf(Capability.ProcessAll))) { claim ->
            assertSame(fanout, currentCoroutineContext()[NuidFanoutElement])
            entered.complete(Unit)
            release.await()
            processed += claim.payload
            if (claim.payload == "failure") throw failure
            "result:${claim.payload}"
        }
        try {
            val first = fanout.dispatch(request, "first")
            entered.await()
            val completion = assertNotNull(first.completion)
            assertEquals("worker", first.winner)
            assertFalse(completion.isCompleted, "Admission must not report suspended execution as completed")
            assertFailsWith<IllegalStateException> { assertNotNull(fanout.slotOf("worker")).consumeAccepted() }
            val failed = assertNotNull(fanout.dispatch(request, "failure").completion)
            val third = assertNotNull(fanout.dispatch(request, "third").completion)
            val draining = async { fanout.drain() }
            runCurrent()
            assertFalse(draining.isCompleted)
            assertFailsWith<IllegalStateException> { fanout.register(Workgroup("late", Subnet.core, TraitSpace.EMPTY)) }
            release.complete(Unit)
            assertEquals("result:first", completion.await())
            val reported = assertIs<IllegalArgumentException>(runCatching { failed.await() }.exceptionOrNull())
            assertEquals(failure.message, reported.message)
            assertEquals("result:third", third.await(), "One failed claim must not stop the owned worker")
            draining.await()
            assertEquals(listOf<Any?>("first", "failure", "third"), processed)
            assertTrue(fanout.supervisor.isCompleted)
            assertFalse(fanout.supervisor.children.any())
            assertTrue(currentCoroutineContext().job.isActive)
        } finally { release.complete(Unit); fanout.close() }
    }

    @Test
    fun closedNearestSlotDeclinesBeforeAnAncestorExecutes() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        val request = nuid(Capability.Process("execute"), Nonce.RandomBytes(), Subnet.parse("local.process"))
        var calls = 0
        try {
            fanout.register(Workgroup("nearest", request.subnet, traitSpaceOf(Capability.ProcessAll)))
            assertNotNull(fanout.slotOf("nearest")).close()
            fanout.register(Workgroup("ancestor", Subnet.local, traitSpaceOf(Capability.ProcessAll))) { claim ->
                calls++
                claim.payload
            }
            val result = fanout.dispatch(request, "evidence")
            assertEquals("ancestor", result.winner)
            assertEquals(1, result.escalatedLevels)
            assertEquals("evidence", assertNotNull(result.completion).await())
            assertEquals(1, calls)
        } finally { fanout.close() }
    }

    @Test
    fun duplicateHandlerRegistrationCannotMisidentifyTheExecutedBinding() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        val workgroup = Workgroup("worker", Subnet.core, traitSpaceOf(Capability.ProcessAll))
        val request = nuid(Capability.Process("execute"), Nonce.RandomBytes(), Subnet.core)
        var replacementCalls = 0
        try {
            fanout.register(workgroup) { "original" }
            assertFailsWith<IllegalStateException> {
                fanout.register(workgroup) { replacementCalls++; "replacement" }
            }
            fanout.register(workgroup)
            assertEquals("original", assertNotNull(fanout.dispatch(request).completion).await())
            assertEquals(0, replacementCalls)
        } finally { fanout.close() }
    }

    @Test
    fun parentCancellationFinishesRunningAndQueuedCompletionWaiters() = runTest {
        val parent = Job()
        val fanout = NuidFanoutElement(parentJob = parent)
        fanout.open()
        val entered = CompletableDeferred<Unit>()
        val request = nuid(Capability.Process("execute"), Nonce.RandomBytes(), Subnet.core)
        fanout.register(Workgroup("worker", Subnet.core, traitSpaceOf(Capability.ProcessAll))) {
            entered.complete(Unit)
            awaitCancellation()
        }
        try {
            val running = assertNotNull(fanout.dispatch(request, "running").completion)
            entered.await()
            val queued = assertNotNull(fanout.dispatch(request, "queued").completion)
            parent.cancel()
            runCurrent()
            assertIs<CancellationException>(runCatching { running.await() }.exceptionOrNull())
            assertIs<CancellationException>(runCatching { queued.await() }.exceptionOrNull())
            fanout.close()
            assertTrue(fanout.supervisor.isCompleted)
            assertFalse(fanout.supervisor.children.any())
        } finally { parent.cancel(); fanout.close() }
    }

    @Test
    fun selectedInvocationAndInventoryIdentifyTheActualWorkgroup() = runTest {
        val fanout = NuidFanoutElement()
        val request = nuid(Capability.Process("execute"), Nonce.RandomBytes(), Subnet.parse("local.review.worker"))
        val inherited = Workgroup("inherited", Subnet.core, TraitSpace.EMPTY)
        withContext(NuidInvocation(inherited j NuidFanoutElement.Claim(-1, request, null))) {
            fanout.open()
            assertNull(fanout.currentInvocation(), "Public caller metadata has no owning invocation")
        }
        val outer = Workgroup("outer", Subnet.local, traitSpaceOf(Capability.ProcessAll))
        val inner = Workgroup("inner", Subnet.parse("local.review"), traitSpaceOf(Capability.ProcessAll))
        suspend fun register(group: Workgroup) {
            fanout.register(group) { claim ->
                val invocation = NuidInvocation.require()
                assertSame(group, invocation.a, "The handler must observe its selected slot, not inherited caller metadata")
                assertSame(claim, invocation.b)
                assertSame(invocation, NuidInvocation.current())
                assertSame(invocation, fanout.currentInvocation())
                invocation.a.name
            }
        }
        try {
            register(outer)
            register(inner)
            val snapshot = fanout.workgroups()
            assertEquals(listOf("outer", "inner"), snapshot.view.map { it.name })
            val nearest = fanout.dispatch(request)
            assertEquals("inner", nearest.winner)
            assertEquals("inner", assertNotNull(nearest.completion).await())
            val ancestor = fanout.dispatch(nuid(Capability.Process("execute"), Nonce.RandomBytes(), Subnet.parse("local.other")))
            assertEquals("outer", ancestor.winner)
            assertEquals("outer", assertNotNull(ancestor.completion).await())
            assertNull(NuidInvocation.current())
            assertNull(fanout.currentInvocation())
            fanout.unregister("inner")
            assertEquals(listOf("outer"), fanout.workgroups().view.map { it.name })
            assertEquals(listOf("outer", "inner"), snapshot.view.map { it.name }, "Retained inventory keeps its registry snapshot")
        } finally { fanout.close() }
        assertEquals(0, fanout.workgroups().size)
    }

    @Test
    fun aRetiringNameCannotBindAnotherHandlerBeforeAcceptedWorkCompletes() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        val group = Workgroup("worker", Subnet.core, traitSpaceOf(Capability.ProcessAll))
        val request = nuid(Capability.Process("execute"), Nonce.RandomBytes(), Subnet.core)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fanout.register(group) {
            entered.complete(Unit)
            release.await()
            "original"
        }
        try {
            val original = assertNotNull(fanout.dispatch(request).completion)
            entered.await()
            val removing = async(start = CoroutineStart.UNDISPATCHED) { fanout.unregister(group.name) }
            assertFalse(removing.isCompleted)
            assertNull(fanout.slotOf(group.name))
            assertEquals(0, fanout.workgroups().size)
            assertFailsWith<IllegalStateException> { fanout.register(group) { "replacement" } }
            assertFailsWith<IllegalStateException> { fanout.register(group) }
            release.complete(Unit)
            assertEquals("original", original.await())
            removing.await()
            fanout.register(group) { "replacement" }
            assertEquals("replacement", assertNotNull(fanout.dispatch(request).completion).await())
        } finally { release.complete(Unit); fanout.close() }
    }

    @Test
    fun drainJoinsRemovedWorkgroupHandlersAndConcurrentUnregisterCallers() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        val group = Workgroup("worker", Subnet.core, traitSpaceOf(Capability.ProcessAll))
        val request = nuid(Capability.Process("execute"), Nonce.RandomBytes(), Subnet.core)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fanout.register(group) { entered.complete(Unit); release.await(); "retired" }
        try {
            val result = assertNotNull(fanout.dispatch(request).completion)
            entered.await()
            val first = async(start = CoroutineStart.UNDISPATCHED) { fanout.unregister(group.name) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { fanout.unregister(group.name) }
            val draining = async(start = CoroutineStart.UNDISPATCHED) { fanout.drain() }
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertFalse(draining.isCompleted, "A removed slot still owns its admitted handler work")
            first.cancel()
            assertFalse(first.isCompleted, "Cancellation cannot detach retirement cleanup")
            release.complete(Unit)
            assertEquals("retired", result.await())
            first.join()
            second.await()
            draining.await()
            assertTrue(fanout.supervisor.isCompleted)
            assertFalse(fanout.supervisor.children.any())
        } finally { release.complete(Unit); fanout.close() }
    }

    @Test
    fun nestedClaimsRetainActualParentsAndRootToWorkerLineage() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val requests = listOf("a", "b", "c").associateWith { nuid(Capability.Process(it), Nonce.RandomBytes(), Subnet.core) }
        val groups = requests.mapValues { (name, request) -> Workgroup(name, Subnet.core, traitSpaceOf(request.capability)) }
        val seen = mutableMapOf<String, Join<Workgroup, NuidFanoutElement.Claim>>()
        val paths = mutableMapOf<String, NuidLineage>()
        suspend fun observe(name: String, claim: NuidFanoutElement.Claim, path: List<String>) {
            val invocation = assertNotNull(currentCoroutineContext()[NuidInvocation])
            val selected = NuidInvocation.require()
            assertSame(groups[name], selected.a)
            assertSame(claim, selected.b)
            assertSame(requests[name], claim.nuid)
            assertEquals(path, invocation.lineage.view.map { it.a.name })
            invocation.lineage.view.forEach { descriptor ->
                assertSame(groups[descriptor.a.name], descriptor.a)
                if (descriptor.a.name == name) assertSame(claim, descriptor.b)
                else assertSame(seen[descriptor.a.name]?.b, descriptor.b)
            }
            seen[name] = selected
            paths[name] = invocation.lineage
        }
        try {
            fanout.register(groups.getValue("c")) { claim -> observe("c", claim, listOf("a", "b", "c")); "complete" }
            fanout.register(groups.getValue("b")) { claim ->
                observe("b", claim, listOf("a", "b"))
                assertNotNull(fanout.dispatch(requests.getValue("c"), "third").completion).await()
            }
            fanout.register(groups.getValue("a")) { claim ->
                observe("a", claim, listOf("a"))
                assertNotNull(fanout.dispatch(requests.getValue("b"), "second").completion).await()
            }
            val forged = Workgroup("caller", Subnet.core, TraitSpace.EMPTY)
            val root = withContext(NuidInvocation(forged j NuidFanoutElement.Claim(-1, requests.getValue("a"), null))) {
                fanout.dispatch(requests.getValue("a"), "first")
            }
            assertEquals("complete", withTimeout(1_000) { assertNotNull(root.completion).await() })
            assertEquals(root.claimId, seen.getValue("a").b.claimId)
            assertNull(seen.getValue("a").b.parent, "Caller metadata cannot manufacture a causal parent")
            for ((child, parent) in listOf("b" to "a", "c" to "b")) {
                val descriptor = assertNotNull(seen.getValue(child).b.parent)
                assertSame(seen.getValue(parent).a, descriptor.a)
                assertSame(seen.getValue(parent).b, descriptor.b)
            }
            assertEquals(listOf("first", "second", "third"), listOf("a", "b", "c").map { seen.getValue(it).b.payload })
            assertEquals(3, seen.values.map { it.b.claimId }.distinct().size)
            assertEquals(listOf(listOf("a"), listOf("a", "b"), listOf("a", "b", "c")),
                listOf("a", "b", "c").map { paths.getValue(it).view.map { descriptor -> descriptor.a.name } })
            val draining = async { fanout.drain() }
            withTimeout(1_000) { draining.await() }
            assertFalse(fanout.supervisor.children.any())
        } finally { owner.cancel(); fanout.close() }
    }

    @Test
    fun selfDispatchRefusesBeforeAnotherInvocationCanQueue() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val request = nuid(Capability.Process("self"), Nonce.RandomBytes(), Subnet.core)
        var calls = 0
        fanout.register(Workgroup("self", Subnet.core, traitSpaceOf(request.capability))) { claim ->
            calls++
            if (claim.payload == "cycle") {
                assertFailsWith<NuidDispatchCycle> { fanout.dispatch(request, "unexpected") }
                val fake = Workgroup("caller", Subnet.core, TraitSpace.EMPTY)
                withContext(NuidInvocation(fake j NuidFanoutElement.Claim(-1, request, null))) {
                    assertSame(claim, assertNotNull(fanout.currentInvocation()).b,
                        "Replacing public metadata cannot replace the executing owner's identity")
                    assertFailsWith<NuidDispatchCycle> { fanout.dispatch(request, "unexpected") }
                }
                "refused"
            } else "independent"
        }
        try {
            assertEquals("refused", withTimeout(1_000) { assertNotNull(fanout.dispatch(request, "cycle").completion).await() })
            assertEquals(1, calls)
            assertEquals("independent", assertNotNull(fanout.dispatch(request, "probe").completion).await())
            assertEquals(2, calls, "The refused cycle must never execute later as queued work")
            val draining = async { fanout.drain() }
            withTimeout(1_000) { draining.await() }
            assertFalse(fanout.supervisor.children.any())
        } finally { owner.cancel(); fanout.close() }
    }

    @Test
    fun descendantDispatchCannotQueueAnAncestorWorker() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val a = nuid(Capability.Process("a"), Nonce.RandomBytes(), Subnet.core)
        val b = nuid(Capability.Process("b"), Nonce.RandomBytes(), Subnet.core)
        var aCalls = 0
        var bCalls = 0
        fanout.register(Workgroup("a", Subnet.core, traitSpaceOf(a.capability))) { claim ->
            aCalls++
            if (claim.payload == "root") assertNotNull(fanout.dispatch(b).completion).await() else "probe"
        }
        fanout.register(Workgroup("b", Subnet.core, traitSpaceOf(b.capability))) {
            bCalls++
            assertFailsWith<NuidDispatchCycle> { fanout.dispatch(a, "unexpected") }
            "refused"
        }
        try {
            assertEquals("refused", withTimeout(1_000) { assertNotNull(fanout.dispatch(a, "root").completion).await() })
            assertEquals(1, aCalls)
            assertEquals(1, bCalls)
            assertEquals("probe", assertNotNull(fanout.dispatch(a, "probe").completion).await())
            assertEquals(2, aCalls)
            val draining = async { fanout.drain() }
            withTimeout(1_000) { draining.await() }
            assertFalse(fanout.supervisor.children.any())
        } finally { owner.cancel(); fanout.close() }
    }

    @Test
    fun independentRootsCannotWaitOnEachOthersSerialWorkers() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val a = nuid(Capability.Process("a"), Nonce.RandomBytes(), Subnet.core)
        val b = nuid(Capability.Process("b"), Nonce.RandomBytes(), Subnet.core)
        val aEntered = CompletableDeferred<Unit>()
        val bEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val children = mutableListOf<String>()
        suspend fun execute(name: String, claim: NuidFanoutElement.Claim, target: Nuid, entered: CompletableDeferred<Unit>): String {
            if (claim.payload != "root") { children += name; return "child" }
            entered.complete(Unit)
            release.await()
            return try {
                assertEquals("child", assertNotNull(fanout.dispatch(target, "dependency").completion).await())
                "finished"
            } catch (_: NuidDispatchCycle) { "refused" }
        }
        fanout.register(Workgroup("a", Subnet.core, traitSpaceOf(a.capability))) { execute("a", it, b, aEntered) }
        fanout.register(Workgroup("b", Subnet.core, traitSpaceOf(b.capability))) { execute("b", it, a, bEntered) }
        try {
            val first = assertNotNull(fanout.dispatch(a, "root").completion)
            val second = assertNotNull(fanout.dispatch(b, "root").completion)
            aEntered.await()
            bEntered.await()
            release.complete(Unit)
            val outcomes = withTimeout(1_000) { listOf(first.await(), second.await()) }
            assertEquals(setOf("finished", "refused"), outcomes.toSet())
            assertEquals(1, children.size, "Only the admitted dependency executes after its worker becomes free")
            val draining = async { fanout.drain() }
            withTimeout(1_000) { draining.await() }
            assertFalse(fanout.supervisor.children.any())
        } finally { release.complete(Unit); owner.cancel(); fanout.close() }
    }

    @Test
    fun anAcceptedParentCanIssueItsRequiredChildAfterAdmissionStops() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val a = nuid(Capability.Process("a"), Nonce.RandomBytes(), Subnet.core)
        val b = nuid(Capability.Process("b"), Nonce.RandomBytes(), Subnet.core)
        val parentEntered = CompletableDeferred<Unit>()
        val childEntered = CompletableDeferred<Unit>()
        val releaseParent = CompletableDeferred<Unit>()
        val releaseChild = CompletableDeferred<Unit>()
        fanout.register(Workgroup("a", Subnet.core, traitSpaceOf(a.capability))) {
            parentEntered.complete(Unit)
            releaseParent.await()
            assertNotNull(fanout.dispatch(b, "required").completion).await()
        }
        fanout.register(Workgroup("b", Subnet.core, traitSpaceOf(b.capability))) {
            childEntered.complete(Unit)
            releaseChild.await()
            "child completed"
        }
        try {
            val result = assertNotNull(fanout.dispatch(a).completion)
            parentEntered.await()
            fanout.stopAdmission()
            val draining = async(start = CoroutineStart.UNDISPATCHED) { fanout.drain() }
            releaseParent.complete(Unit)
            withTimeout(1_000) { childEntered.await() }
            assertFalse(result.isCompleted)
            assertFalse(draining.isCompleted, "Drain must own the descendant issued by its accepted parent")
            releaseChild.complete(Unit)
            assertEquals("child completed", withTimeout(1_000) { result.await() })
            withTimeout(1_000) { draining.await() }
            assertTrue(fanout.supervisor.isCompleted)
            assertFalse(fanout.supervisor.children.any())
        } finally { releaseParent.complete(Unit); releaseChild.complete(Unit); owner.cancel(); fanout.close() }
    }

    @Test
    fun callerCarriersCannotManufacturePostStopChildAdmission() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val a = nuid(Capability.Process("a"), Nonce.RandomBytes(), Subnet.core)
        val b = nuid(Capability.Process("b"), Nonce.RandomBytes(), Subnet.core)
        val entered = CompletableDeferred<Join<Workgroup, NuidFanoutElement.Claim>>()
        val carrier = CompletableDeferred<NuidInvocation>()
        val release = CompletableDeferred<Unit>()
        var children = 0
        fanout.register(Workgroup("a", Subnet.core, traitSpaceOf(a.capability))) {
            assertSame(NuidInvocation.require(), fanout.currentInvocation())
            entered.complete(NuidInvocation.require())
            carrier.complete(assertNotNull(currentCoroutineContext()[NuidInvocation]))
            release.await()
            "parent"
        }
        fanout.register(Workgroup("b", Subnet.core, traitSpaceOf(b.capability))) { children++; "child" }
        try {
            val result = assertNotNull(fanout.dispatch(a).completion)
            val selected = entered.await()
            fanout.stopAdmission()
            assertNull(fanout.currentInvocation())
            assertFailsWith<IllegalStateException> { fanout.dispatch(b) }
            withContext(NuidInvocation(selected)) {
                assertNull(fanout.currentInvocation(), "Reconstructed selected metadata is not an owner lease")
                assertFailsWith<IllegalStateException> { fanout.dispatch(b) }
            }
            assertEquals(0, children)
            release.complete(Unit)
            assertEquals("parent", result.await())
            withContext(carrier.await().context) {
                assertNull(fanout.currentInvocation(), "The actual owner lease expires before reporting completion")
                assertFailsWith<IllegalStateException> { fanout.dispatch(b) }
            }
            assertEquals(0, children, "An expired owner-issued carrier cannot reopen admission")
            val draining = async { fanout.drain() }
            withTimeout(1_000) { draining.await() }
            assertFalse(fanout.supervisor.children.any())
        } finally { release.complete(Unit); owner.cancel(); fanout.close() }
    }

    @Test
    fun anUnawaitedChildCanReuseItsCompletedParentsWorkerDuringDrain() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val a = nuid(Capability.Process("a"), Nonce.RandomBytes(), Subnet.core)
        val b = nuid(Capability.Process("b"), Nonce.RandomBytes(), Subnet.core)
        val childAdmitted = CompletableDeferred<NuidFanoutElement.DispatchResult>()
        val childEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reused = 0
        fanout.register(Workgroup("a", Subnet.core, traitSpaceOf(a.capability))) { claim ->
            if (claim.payload == "root") {
                childAdmitted.complete(fanout.dispatch(b, "child"))
                "parent returned"
            } else {
                reused++
                assertEquals(listOf("a", "b", "a"),
                    assertNotNull(currentCoroutineContext()[NuidInvocation]).lineage.view.map { it.a.name })
                assertEquals("child", assertNotNull(claim.parent).b.payload)
                "reused"
            }
        }
        fanout.register(Workgroup("b", Subnet.core, traitSpaceOf(b.capability))) {
            childEntered.complete(Unit)
            release.await()
            assertNotNull(fanout.dispatch(a, "reuse").completion).await()
        }
        try {
            val parent = assertNotNull(fanout.dispatch(a, "root").completion)
            assertEquals("parent returned", withTimeout(1_000) { parent.await() })
            val child = assertNotNull(childAdmitted.await().completion)
            childEntered.await()
            assertFalse(child.isCompleted)
            fanout.stopAdmission()
            val draining = async(start = CoroutineStart.UNDISPATCHED) { fanout.drain() }
            assertFalse(draining.isCompleted, "Returning a parent cannot discard its unawaited accepted child")
            release.complete(Unit)
            assertEquals("reused", withTimeout(1_000) { child.await() })
            withTimeout(1_000) { draining.await() }
            assertEquals(1, reused)
            assertTrue(fanout.supervisor.isCompleted)
            assertFalse(fanout.supervisor.children.any())
        } finally { release.complete(Unit); owner.cancel(); fanout.close() }
    }

    @Test
    fun aHandlerCannotDrainItsOwnOwnerOrHideBehindCallerMetadata() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val request = nuid(Capability.Process("self"), Nonce.RandomBytes(), Subnet.core)
        val group = Workgroup("self", Subnet.core, traitSpaceOf(request.capability))
        fanout.register(group) {
            assertFailsWith<IllegalStateException> { fanout.drain() }
            withContext(NuidInvocation(Workgroup("caller", Subnet.core, TraitSpace.EMPTY) j
                NuidFanoutElement.Claim(-1, request, null))) {
                assertFailsWith<IllegalStateException> { fanout.close() }
            }
            assertFalse(fanout.stopped.isCompleted, "A refused self-join cannot stop external admission")
            "usable"
        }
        try {
            repeat(2) {
                assertEquals("usable", withTimeout(1_000) { assertNotNull(fanout.dispatch(request).completion).await() })
                assertSame(group, fanout.slotOf(group.name)?.workgroup)
            }
            val draining = async { fanout.drain() }
            withTimeout(1_000) { draining.await() }
            assertTrue(fanout.supervisor.isCompleted)
            assertFalse(fanout.supervisor.children.any())
        } finally { owner.cancel(); fanout.close() }
    }

    @Test
    fun aHandlerCannotUnregisterItsOwnExecutingSlot() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val request = nuid(Capability.Process("self"), Nonce.RandomBytes(), Subnet.core)
        val group = Workgroup("self", Subnet.core, traitSpaceOf(request.capability))
        fanout.register(group) {
            assertFailsWith<IllegalStateException> { fanout.unregister(group.name) }
            withContext(NuidInvocation(Workgroup("caller", Subnet.core, TraitSpace.EMPTY) j
                NuidFanoutElement.Claim(-1, request, null))) {
                assertFailsWith<IllegalStateException> { fanout.unregister(group.name) }
            }
            assertSame(group, fanout.slotOf(group.name)?.workgroup, "Refusal must precede registry removal")
            "registered"
        }
        try {
            repeat(2) {
                assertEquals("registered", withTimeout(1_000) { assertNotNull(fanout.dispatch(request).completion).await() })
            }
            withTimeout(1_000) { fanout.unregister(group.name) }
            assertNull(fanout.slotOf(group.name))
            val draining = async { fanout.drain() }
            withTimeout(1_000) { draining.await() }
            assertTrue(fanout.supervisor.isCompleted)
            assertFalse(fanout.supervisor.children.any())
        } finally { owner.cancel(); fanout.close() }
    }

    @Test
    fun childAndParentFailuresSettleTheirWaitersWithoutPoisoningLaterRoots() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val a = nuid(Capability.Process("a"), Nonce.RandomBytes(), Subnet.core)
        val b = nuid(Capability.Process("b"), Nonce.RandomBytes(), Subnet.core)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val childFailure = IllegalArgumentException("child refused")
        val parentFailure = IllegalStateException("parent refused")
        val children = mutableListOf<Any?>()
        fanout.register(Workgroup("b", Subnet.core, traitSpaceOf(b.capability))) { claim ->
            children += claim.payload
            if (claim.payload == "child") { entered.complete(Unit); release.await(); throw childFailure }
            "child:${claim.payload}"
        }
        fanout.register(Workgroup("a", Subnet.core, traitSpaceOf(a.capability))) { claim ->
            val result = assertNotNull(fanout.dispatch(b, claim.payload).completion).await()
            if (claim.payload == "parent") throw parentFailure
            result
        }
        try {
            val first = assertNotNull(fanout.dispatch(a, "child").completion)
            entered.await()
            val second = assertNotNull(fanout.dispatch(a, "parent").completion)
            val third = async { assertNotNull(fanout.dispatch(a, "recovery", 1_000).completion).await() }
            runCurrent()
            release.complete(Unit)
            val childReported = assertIs<IllegalArgumentException>(withTimeout(1_000) { runCatching { first.await() }.exceptionOrNull() })
            val parentReported = assertIs<IllegalStateException>(withTimeout(1_000) { runCatching { second.await() }.exceptionOrNull() })
            assertEquals(childFailure.message, childReported.message)
            assertEquals(parentFailure.message, parentReported.message)
            assertEquals("child:recovery", withTimeout(1_000) { third.await() })
            assertEquals(listOf<Any?>("child", "parent", "recovery"), children)
            val draining = async { fanout.drain() }
            withTimeout(1_000) { draining.await() }
            assertFalse(fanout.supervisor.children.any())
        } finally { release.complete(Unit); owner.cancel(); fanout.close() }
    }

    @Test
    fun retirementCutsNewDependenciesAndRetainsAcceptedNestedOwnership() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val a = nuid(Capability.Process("a"), Nonce.RandomBytes(), Subnet.core)
        val b = nuid(Capability.Process("b"), Nonce.RandomBytes(), Subnet.core)
        val c = nuid(Capability.Process("c"), Nonce.RandomBytes(), Subnet.core)
        val groupA = Workgroup("a", Subnet.core, traitSpaceOf(a.capability))
        val groupB = Workgroup("b", Subnet.core, traitSpaceOf(b.capability))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var children = 0
        fanout.register(groupA) { assertNotNull(fanout.dispatch(b).completion).await() }
        fanout.register(groupB) { children++; entered.complete(Unit); release.await(); "retired child" }
        fanout.register(Workgroup("c", Subnet.core, traitSpaceOf(c.capability))) { fanout.dispatch(b).winner }
        try {
            val result = assertNotNull(fanout.dispatch(a).completion)
            entered.await()
            val removingChild = async(start = CoroutineStart.UNDISPATCHED) { fanout.unregister(groupB.name) }
            assertFalse(removingChild.isCompleted)
            assertNull(fanout.slotOf(groupB.name))
            assertFailsWith<IllegalStateException> { fanout.register(groupB) { "replacement" } }
            assertNull(withTimeout(1_000) { assertNotNull(fanout.dispatch(c).completion).await() })
            val removingParent = async(start = CoroutineStart.UNDISPATCHED) { fanout.unregister(groupA.name) }
            assertFalse(removingParent.isCompleted)
            val draining = async(start = CoroutineStart.UNDISPATCHED) { fanout.drain() }
            assertFalse(draining.isCompleted)
            release.complete(Unit)
            assertEquals("retired child", withTimeout(1_000) { result.await() })
            withTimeout(1_000) { removingChild.await(); removingParent.await(); draining.await() }
            assertEquals(1, children)
            assertTrue(fanout.supervisor.isCompleted)
            assertFalse(fanout.supervisor.children.any())
        } finally { release.complete(Unit); owner.cancel(); fanout.close() }
    }

    @Test
    fun claimCleanupFinishesBeforeReportingExecutionAndRetirement() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        val request = nuid(Capability.Process("cleanup"), Nonce.RandomBytes(), Subnet.core)
        val group = Workgroup("cleanup", Subnet.core, traitSpaceOf(request.capability))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val phases = mutableListOf<String>()
        fanout.register(group, cleanup = { claim ->
            assertSame(claim, assertNotNull(fanout.currentInvocation()).b)
            phases += "cleanup started"
            entered.complete(Unit)
            release.await()
            phases += "cleanup finished"
        }) { phases += "handler"; "result" }
        try {
            val completion = assertNotNull(fanout.dispatch(request).completion)
            entered.await()
            assertFalse(completion.isCompleted)
            val retiring = async(start = CoroutineStart.UNDISPATCHED) { fanout.unregister(group.name) }
            assertFalse(retiring.isCompleted, "A slot retains ownership until claim cleanup completes")
            assertFailsWith<IllegalStateException> { fanout.register(group) { "replacement" } }
            release.complete(Unit)
            assertEquals("result", completion.await())
            retiring.await()
            assertEquals(listOf("handler", "cleanup started", "cleanup finished"), phases)
            fanout.drain()
            assertFalse(fanout.supervisor.children.any())
        } finally { release.complete(Unit); fanout.close() }
    }

    @Test
    fun handlerAndCleanupFailuresRetainTheirCausesWithoutStoppingTheWorker() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        val request = nuid(Capability.Process("cleanup"), Nonce.RandomBytes(), Subnet.core)
        val handlerFailure = IllegalArgumentException("handler failed")
        val cleanupFailure = IllegalStateException("cleanup failed")
        val cleaned = mutableListOf<Any?>()
        fanout.register(Workgroup("cleanup", Subnet.core, traitSpaceOf(request.capability)), cleanup = { claim ->
            cleaned += claim.payload
            if (claim.payload != "recovery") throw cleanupFailure
        }) { claim ->
            if (claim.payload == "both") { yield(); throw handlerFailure }
            claim.payload
        }
        try {
            val both = assertNotNull(fanout.dispatch(request, "both").completion)
            val primary = assertIs<IllegalArgumentException>(runCatching { both.await() }.exceptionOrNull())
            assertEquals(handlerFailure.message, primary.message)
            assertTrue(handlerFailure.suppressedExceptions.any { it.message == cleanupFailure.message },
                "Cleanup failure attaches before the handler failure crosses the completion boundary")
            assertTrue(generateSequence<Throwable>(primary) { it.cause }.any { cause ->
                cause.suppressedExceptions.any { it.message == cleanupFailure.message }
            }, "Cleanup failure remains attached to the reported handler failure")
            val cleanup = assertNotNull(fanout.dispatch(request, "cleanup").completion)
            assertEquals(cleanupFailure.message,
                assertIs<IllegalStateException>(runCatching { cleanup.await() }.exceptionOrNull()).message)
            assertEquals("recovery", assertNotNull(fanout.dispatch(request, "recovery").completion).await())
            assertEquals(listOf<Any?>("both", "cleanup", "recovery"), cleaned)
            fanout.drain()
            assertFalse(fanout.supervisor.children.any())
        } finally { fanout.close() }
    }

    @Test
    fun ownerCancellationCleansExecutedAndPendingAcceptedClaimsExactlyOnce() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 2)
        fanout.open()
        val request = nuid(Capability.Process("cleanup"), Nonce.RandomBytes(), Subnet.core)
        val entered = CompletableDeferred<Unit>()
        val executed = mutableListOf<Any?>()
        val cleaned = mutableListOf<Any?>()
        fanout.register(Workgroup("cleanup", Subnet.core, traitSpaceOf(request.capability)), cleanup = { claim ->
            assertTrue(currentCoroutineContext().job.isActive, "Cleanup runs under NonCancellable")
            cleaned += claim.payload
            if (claim.payload == "pending") error("pending cleanup failed")
        }) { claim ->
            executed += claim.payload
            entered.complete(Unit)
            awaitCancellation()
        }
        try {
            val running = assertNotNull(fanout.dispatch(request, "running").completion)
            entered.await()
            val pending = assertNotNull(fanout.dispatch(request, "pending").completion)
            val last = assertNotNull(fanout.dispatch(request, "last").completion)
            owner.cancel()
            assertIs<CancellationException>(runCatching { running.await() }.exceptionOrNull())
            val cancelled = assertIs<CancellationException>(runCatching { pending.await() }.exceptionOrNull())
            assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any { cause ->
                cause.suppressedExceptions.any { it.message == "pending cleanup failed" }
            })
            assertIs<CancellationException>(runCatching { last.await() }.exceptionOrNull())
            fanout.drain()
            assertEquals(listOf<Any?>("running"), executed)
            assertEquals(listOf<Any?>("running", "pending", "last"), cleaned)
            assertFalse(fanout.supervisor.children.any())
        } finally { owner.cancel(); fanout.close() }
    }

    @Test
    fun onlyAnAuthenticLiveInvocationCanRegisterRequiredHandlersDuringDrain() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner)
        fanout.open()
        val parentRequest = nuid(Capability.Process("parent"), Nonce.RandomBytes(), Subnet.core)
        val childRequest = nuid(Capability.Process("child"), Nonce.RandomBytes(), Subnet.core)
        val child = Workgroup("child", Subnet.core, traitSpaceOf(childRequest.capability))
        val entered = CompletableDeferred<NuidInvocation>()
        val release = CompletableDeferred<Unit>()
        var cleanups = 0
        fanout.register(Workgroup("parent", Subnet.core, traitSpaceOf(parentRequest.capability))) { parent ->
            entered.complete(assertNotNull(currentCoroutineContext()[NuidInvocation]))
            release.await()
            withContext(NuidInvocation(Workgroup("fake", Subnet.core, TraitSpace.EMPTY) j
                NuidFanoutElement.Claim(-1, parentRequest, null))) {
                fanout.register(child, cleanup = { cleanups++ }) { claim ->
                    assertSame(parent, assertNotNull(claim.parent).b)
                    "child"
                }
            }
            assertNotNull(fanout.dispatch(childRequest).completion).await()
        }
        try {
            val completion = assertNotNull(fanout.dispatch(parentRequest).completion)
            val actual = entered.await()
            fanout.stopAdmission()
            assertFailsWith<IllegalStateException> { fanout.register(child) { "external" } }
            withContext(NuidInvocation(actual.value).context) {
                assertFailsWith<IllegalStateException> { fanout.register(child, cleanup = {}) { "fake" } }
            }
            val draining = async(start = CoroutineStart.UNDISPATCHED) { fanout.drain() }
            release.complete(Unit)
            assertEquals("child", withTimeout(1_000) { completion.await() })
            withTimeout(1_000) { draining.await() }
            withContext(actual.context) {
                assertFailsWith<IllegalStateException> { fanout.register(child) { "expired" } }
            }
            assertEquals(1, cleanups)
            assertFalse(fanout.supervisor.children.any())
        } finally { release.complete(Unit); owner.cancel(); fanout.close() }
    }

    @Test
    fun drainJoinsBoundedNestedFanInAfterAnAcceptedCallerCancels() = runTest {
        val owner = Job()
        val fanout = NuidFanoutElement(parentJob = owner, capacity = 1)
        fanout.open()
        val a = nuid(Capability.Process("a"), Nonce.RandomBytes(), Subnet.core)
        val b = nuid(Capability.Process("b"), Nonce.RandomBytes(), Subnet.core)
        val parentEntered = CompletableDeferred<Unit>()
        val childEntered = CompletableDeferred<Unit>()
        val releaseParent = CompletableDeferred<Unit>()
        val releaseChildren = CompletableDeferred<Unit>()
        val processed = mutableListOf<Any?>()
        fanout.register(Workgroup("a", Subnet.core, traitSpaceOf(a.capability))) { claim ->
            parentEntered.complete(Unit)
            releaseParent.await()
            coroutineScope {
                (0 until 4).map { ordinal -> async {
                    assertNotNull(fanout.dispatch(b, "${claim.payload}:$ordinal", 1_000).completion).await()
                } }.awaitAll()
            }
        }
        fanout.register(Workgroup("b", Subnet.core, traitSpaceOf(b.capability))) { claim ->
            childEntered.complete(Unit)
            releaseChildren.await()
            processed += claim.payload
            claim.payload
        }
        try {
            val first = assertNotNull(fanout.dispatch(a, 1).completion)
            parentEntered.await()
            val admitted = CompletableDeferred<NuidFanoutElement.DispatchResult>()
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                val result = fanout.dispatch(a, 2)
                admitted.complete(result)
                assertNotNull(result.completion).await()
            }
            val second = assertNotNull(admitted.await().completion)
            caller.cancelAndJoin()
            assertFalse(second.isCompleted, "Cancelling a waiter cannot discard its already accepted parent")
            releaseParent.complete(Unit)
            childEntered.await()
            fanout.stopAdmission()
            val draining = async(start = CoroutineStart.UNDISPATCHED) { fanout.drain() }
            assertFalse(draining.isCompleted)
            releaseChildren.complete(Unit)
            assertEquals(List(4) { "1:$it" }, withTimeout(1_000) { first.await() })
            assertEquals(List(4) { "2:$it" }, withTimeout(1_000) { second.await() })
            withTimeout(1_000) { draining.await() }
            assertEquals((List(4) { "1:$it" } + List(4) { "2:$it" }).sorted(), processed.map { it.toString() }.sorted())
            assertTrue(fanout.supervisor.isCompleted)
            assertFalse(fanout.supervisor.children.any())
        } finally { releaseParent.complete(Unit); releaseChildren.complete(Unit); owner.cancel(); fanout.close() }
    }
}
