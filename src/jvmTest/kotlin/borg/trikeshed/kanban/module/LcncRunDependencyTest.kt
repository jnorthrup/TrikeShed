package borg.trikeshed.kanban.module

import borg.trikeshed.context.nuid.*
import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lcnc.ccek.ccekReactorBinding
import borg.trikeshed.lcnc.ccek.LcncRunQueueEntry
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.*
import borg.trikeshed.parse.reify
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class LcncRunDependencyTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root: Path

    private fun json(response: HttpResponse): Map<*, *> = assertIs<Map<*, *>>(reify(response.body))

    private class Fixture(val ctx: ModuleContext, val store: BoardStoreElement,
                          val runs: LcncRunService, val fanout: NuidFanoutElement) {
        fun program(name: String) = LcncProgram(name, s_[LcncNode("work", "test.$name")], emptySeriesOf())
        fun request(name: String) = nuid(Capability.Custom("lcnc", name), Nonce.RandomBytes(), Subnet.local)

        suspend fun register(name: String, service: LcncRunService = runs, run: suspend () -> Map<String, Any?>) {
            ctx.lcncRunners["test.$name"] = LcncNodeRunner { _, _ -> run() }
            val program = program(name)
            fanout.register(Workgroup(name, Subnet.local, traitSpaceOf(request(name).capability)), cleanup = { claim ->
                queuedEntry(claim.payload)?.let { service.releaseQueued(it) }
            }) { claim ->
                val selected = checkNotNull(currentCoroutineContext()[NuidInvocation])
                val queued = queuedEntry(claim.payload)
                val caller = queued?.b ?: claim.payload as CoroutineContext
                withContext(caller + currentCoroutineContext().job + fanout + selected.context) {
                    if (queued == null) service.execute(name, program, false, emptyMap(), emptyMap<String, Any?>(), selected.value)
                    else service.executeQueued(queued, selected.value)
                }
            }
        }

        private fun queuedEntry(payload: Any?): LcncRunQueueEntry? {
            val value = payload as? Join<*, *> ?: return null
            if (value.a !is BoardApply.Committed || value.b !is CoroutineContext) return null
            @Suppress("UNCHECKED_CAST")
            return value as LcncRunQueueEntry
        }

        suspend fun enqueue(name: String, program: LcncProgram = program(name), inputs: Map<String, Any?> = emptyMap(),
                            request: Map<String, Any?> = emptyMap()): LcncRunQueueEntry {
            val result = runs.enqueue(name, program, false, inputs, request)
            assertNull(result.a, result.a?.body)
            return assertNotNull(result.b)
        }

        suspend fun dispatchQueued(name: String, entry: LcncRunQueueEntry): NuidFanoutElement.DispatchResult {
            val reserved = runs.reserveQueued(entry)
            try {
                return withContext(NonCancellable) { fanout.dispatch(request(name), reserved, 1_000) }.also {
                    if (it.winner == null) runs.releaseQueued(reserved)
                }
            } catch (failure: Throwable) { runs.releaseQueued(reserved); throw failure }
        }

        suspend fun dispatch(name: String): NuidFanoutElement.DispatchResult =
            fanout.dispatch(request(name), currentCoroutineContext().minusKey(Job), timeoutMillis = 1_000)

        suspend fun run(name: String): HttpResponse {
            val dispatched = dispatch(name)
            assertEquals(name, dispatched.winner)
            return assertIs<HttpResponse>(assertNotNull(dispatched.completion).await())
        }

        suspend fun probe(): HttpResponse = runs.execute("probe", program("probe"), false,
            emptyMap(), emptyMap<String, Any?>())

        suspend fun receipt(jobId: String): Map<*, *> = assertIs<Map<*, *>>(store.command(jobId)?.get("lcncRun"))
    }

    private class PinCas : CasStore() {
        var hidden: ContentId? = null
        var replacement: Join<ContentId, ByteArray>? = null
        override fun get(cid: ContentId): ByteArray? = when {
            cid == hidden -> null
            cid == replacement?.a -> replacement?.b?.copyOf()
            else -> super.get(cid)
        }
    }

    private suspend fun fixture(cas: CasStore = CasStore.inMemory(), body: suspend Fixture.() -> Unit) {
        val owner = SupervisorJob(currentCoroutineContext().job)
        val scope = CoroutineScope(currentCoroutineContext() + owner)
        val reactor = MuxReactorElement(parentJob = owner)
        reactor.open()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("dependencies", couchStore, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(couchStore, cas),
            ModuleRouteRegistry(), scope, { 1L }, root.toFile(), ccekBinding = ccekReactorBinding(scope.coroutineContext + reactor))
        val store = BoardStoreElement(null, cas, parentJob = owner)
        store.open()
        val runs = LcncRunService(ctx, store) { LcncContracts.all().associateBy { it.type } }
        val fanout = NuidFanoutElement(parentJob = owner)
        fanout.open()
        ctx.lcncRunners["test.probe"] = LcncNodeRunner { _, _ -> emptyMap() }
        var completed = false
        try { Fixture(ctx, store, runs, fanout).body(); completed = true }
        finally {
            withContext(NonCancellable) {
                // An assertion failure must not leave deliberately held test runners alive.
                if (!completed) owner.cancel()
                fanout.stopAdmission()
                runs.stopAdmission()
                fanout.drain()
                runs.drain()
                store.drain()
                reactor.drain()
                owner.complete()
                owner.join()
            }
        }
    }

    @Test fun threeRootFamiliesCanAwaitChildrenAfterAdmissionStops() = runBlocking {
        fixture {
            val entered = List(3) { CompletableDeferred<Unit>() }
            val release = CompletableDeferred<Unit>()
            val children = mutableSetOf<Int>()
            repeat(3) { index ->
                register("child$index") { children.add(index); emptyMap() }
                register("root$index") {
                    entered[index].complete(Unit)
                    release.await()
                    val child = run("child$index")
                    assertEquals(200, child.status, child.body)
                    emptyMap()
                }
            }
            coroutineScope {
                val roots = (0..2).map { index -> async { run("root$index") } }
                withTimeout(5_000) { entered.forEach { it.await() } }
                assertEquals(429, probe().status)
                val forgedGroup = Workgroup("forged", Subnet.local, traitSpaceOf(request("forged").capability))
                val forged = NuidInvocation(forgedGroup j NuidFanoutElement.Claim(1L, request("forged"), null))
                assertEquals(429, withContext(forged.context) { probe() }.status)
                fanout.stopAdmission()
                runs.stopAdmission()
                release.complete(Unit)
                withTimeout(5_000) { roots.awaitAll() }.forEach { assertEquals(200, it.status, it.body) }
            }
            assertEquals(setOf(0, 1, 2), children)
            assertEquals(6, store.cards().size)
            for (row in store.cards()) {
                val receipt = receipt(row.jobId)
                assertEquals("completed", receipt["status"])
                if ((receipt["program"] as String).startsWith("child")) {
                    assertTrue((receipt["parentWorkgroup"] as String).startsWith("root"))
                    assertEquals(2, assertIs<List<*>>(receipt["claimLineage"]).size)
                    val parent = this.receipt("lcnc/run/${assertIs<String>(receipt["parentRunId"])}")
                    assertEquals(parent["executionFamily"], receipt["executionFamily"])
                } else {
                    assertNull(receipt["parentRunId"])
                    assertEquals(row.jobId, receipt["executionFamily"])
                }
            }
            assertEquals(429, probe().status)
        }
    }

    @Test fun anAcyclicChainLongerThanCapacityRetainsEveryClaimAndReceipt() = runBlocking {
        fixture {
            val depth = 7
            repeat(depth) { index ->
                register("chain$index") {
                    if (index + 1 < depth) {
                        val child = run("chain${index + 1}")
                        assertEquals(200, child.status, child.body)
                    }
                    emptyMap()
                }
            }
            val result = withTimeout(5_000) { run("chain0") }
            assertEquals(200, result.status, result.body)
            assertEquals(depth, store.cards().size)
            val family = json(result)["executionFamily"]
            for (row in store.cards()) {
                val receipt = receipt(row.jobId)
                val index = (receipt["program"] as String).removePrefix("chain").toInt()
                assertEquals("completed", receipt["status"])
                assertEquals(index + 1, assertIs<List<*>>(receipt["claimLineage"]).size)
                assertEquals(family, receipt["executionFamily"])
            }
            assertEquals(200, probe().status)
        }
    }

    @Test fun anUnrelatedRootRefusesCapacityWithoutBlockingTheWorkerItsDependantsNeed() = runBlocking {
        fixture {
            val entered = List(3) { CompletableDeferred<Unit>() }
            val release = CompletableDeferred<Unit>()
            var children = 0
            register("shared") { children++; emptyMap() }
            repeat(3) { index ->
                register("root$index") {
                    entered[index].complete(Unit)
                    release.await()
                    val child = run("shared")
                    assertEquals(200, child.status, child.body)
                    emptyMap()
                }
            }
            coroutineScope {
                val roots = (0..2).map { index -> async { run("root$index") } }
                withTimeout(5_000) { entered.forEach { it.await() } }
                // This independent root enters B before any parent's dependency can enter B.
                val unrelated = dispatch("shared")
                assertEquals("shared", unrelated.winner)
                release.complete(Unit)
                withTimeout(5_000) {
                    val refused = assertIs<HttpResponse>(assertNotNull(unrelated.completion).await())
                    assertEquals(429, refused.status, refused.body)
                    assertEquals("execution_capacity", json(refused)["error"])
                    roots.awaitAll().forEach { assertEquals(200, it.status, it.body) }
                }
            }
            assertEquals(3, children)
            assertEquals(6, store.cards().size, "The refused independent root must not acquire a run receipt")
        }
    }

    @Test fun anUnawaitedChildRetainsCapacityButItsRetiredParentsCarrierCannotBorrow() = runBlocking {
        fixture {
            val childEntered = CompletableDeferred<Unit>()
            val childRelease = CompletableDeferred<Unit>()
            val childCompletion = CompletableDeferred<Deferred<Any?>>()
            lateinit var retired: CoroutineContext
            register("child") { childEntered.complete(Unit); childRelease.await(); emptyMap() }
            register("parent") {
                retired = currentCoroutineContext().minusKey(Job)
                childCompletion.complete(assertNotNull(dispatch("child").completion))
                childEntered.await()
                emptyMap()
            }
            val held = List(2) { CompletableDeferred<Unit>() }
            val release = CompletableDeferred<Unit>()
            repeat(2) { index -> register("held$index") { held[index].complete(Unit); release.await(); emptyMap() } }
            assertEquals(200, withTimeout(5_000) { run("parent") }.status)
            coroutineScope {
                val roots = (0..1).map { index -> async { run("held$index") } }
                withTimeout(5_000) { held.forEach { it.await() } }
                assertEquals(429, probe().status, "The surviving child still owns its root family's capacity")
                assertEquals(429, withContext(retired) { probe() }.status,
                    "A retired parent carrier cannot borrow a family's surviving child reference")
                childRelease.complete(Unit)
                val child = withTimeout(5_000) { childCompletion.await().await() }
                assertEquals(200, assertIs<HttpResponse>(child).status)
                val fresh = withContext(retired) { probe() }
                assertEquals(200, fresh.status, "The final family member releases exactly one slot")
                assertEquals(json(fresh)["jobId"], json(fresh)["executionFamily"])
                assertNull(json(fresh)["parentRunId"])
                val draining = async { runs.drain() }
                yield()
                assertFalse(draining.isCompleted)
                release.complete(Unit)
                withTimeout(5_000) { roots.awaitAll(); draining.await() }
            }
        }
    }

    @Test fun anotherServicesLiveCarrierCannotBypassRootCapacity() = runBlocking {
        fixture {
            val first = this
            val firstEntered = CompletableDeferred<CoroutineContext>()
            val firstRelease = CompletableDeferred<Unit>()
            register("foreign") {
                firstEntered.complete(currentCoroutineContext().minusKey(Job))
                firstRelease.await()
                emptyMap()
            }
            coroutineScope {
                val source = async { first.run("foreign") }
                val foreign = withTimeout(5_000) { firstEntered.await() }
                try {
                    fixture {
                        val entered = List(3) { CompletableDeferred<Unit>() }
                        val release = CompletableDeferred<Unit>()
                        repeat(3) { index -> register("held$index") { entered[index].complete(Unit); release.await(); emptyMap() } }
                        coroutineScope {
                            val roots = (0..2).map { index -> async { run("held$index") } }
                            try {
                                withTimeout(5_000) { entered.forEach { it.await() } }
                                assertEquals(429, withContext(foreign) { probe() }.status)
                            } finally { release.complete(Unit) }
                            withTimeout(5_000) { roots.awaitAll() }
                        }
                        val fresh = withContext(foreign) { probe() }
                        assertEquals(200, fresh.status, fresh.body)
                        assertEquals(json(fresh)["jobId"], json(fresh)["executionFamily"])
                        assertNull(json(fresh)["parentRunId"])
                    }
                } finally { firstRelease.complete(Unit) }
                assertEquals(200, withTimeout(5_000) { source.await() }.status)
            }
        }
    }

    @Test fun queuedFrozenRootKeepsItsIdentityAndHonestDurability() = runBlocking {
        fixture {
            register("frozen") { emptyMap() }
            ctx.lcncRunners["test.version"] = LcncNodeRunner { node, _ -> mapOf("value" to node.params["value"]) }
            val original = LcncProgram("frozen", s_[LcncNode("work", "test.version", mapOf("value" to "v1"))], emptySeriesOf())
            val entry = enqueue("frozen", original)
            val queued = runs.queuedReceipt(entry)
            assertEquals("VOLATILE", queued["queueDurability"])
            assertEquals(1L, store.card(entry.a.jobId)?.revision)
            val replacement = original.copy(nodes = s_[original.nodes[0].copy(params = mapOf("value" to "v2"))])
            assertNotEquals(LcncBlackboard.cidOf(replacement), queued["programCid"])
            val admitted = dispatchQueued("frozen", entry)
            val result = assertIs<HttpResponse>(withTimeout(5_000) { assertNotNull(admitted.completion).await() })
            assertEquals(200, result.status, result.body)
            val terminal = json(result)
            assertEquals(entry.a.jobId, terminal["jobId"])
            assertEquals(queued["programCid"], terminal["programCid"])
            assertEquals("v1", (((terminal["outputs"] as Map<*, *>)["work"] as Map<*, *>)["value"]))
            assertEquals(3L, store.card(entry.a.jobId)?.revision)
            runs.releaseQueued(entry)
            assertEquals(200, probe().status)
        }
    }

    @Test fun queuedEvidenceUsesRetainedInputsWithoutRevalidatingOrRerunningItsProducer() = runBlocking {
        fixture {
            var produced = 0
            ctx.lcncRunners["test.produce"] = LcncNodeRunner { _, _ ->
                produced++
                mapOf("value" to mapOf("summary" to "Retained summary",
                    "ballots" to listOf(mapOf("seat" to "reviewer", "approve" to false, "weight" to 2)),
                    "agent" to mapOf("runId" to "agent-run", "jobId" to "agent-task", "ok" to true)))
            }
            val producer = LcncProgram("producer", s_[LcncNode("emit", "test.produce"),
                LcncNode("out", "scope.out", mapOf("name" to "evidence"))], s_[LcncWire("emit", "value", "out", "value")])
            val source = runs.execute("producer", producer, false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, source.status, source.body)
            val sourceReceipt = json(source)
            val sourceCid = sourceReceipt["receiptCid"] as String
            register("consumer") { emptyMap() }
            val entry = enqueue("consumer", request = mapOf("sourceReceiptCid" to sourceCid))
            val frozen = runs.queuedReceipt(entry)
            val acknowledgment = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(mapOf("type" to "acknowledge", "jobId" to sourceReceipt["jobId"],
                "owner" to "lcnc-runner", "expectedRevision" to sourceReceipt["timelineRevision"],
                "idempotencyKey" to "source-after-queue", "lcncRun" to sourceReceipt), acknowledgment))
            assertIs<BoardApply.Committed>(acknowledgment.await())
            val admitted = dispatchQueued("consumer", entry)
            val result = assertIs<HttpResponse>(withTimeout(5_000) { assertNotNull(admitted.completion).await() })
            assertEquals(200, result.status, result.body)
            val terminal = json(result)
            assertEquals(frozen["inputs"], terminal["inputs"])
            assertEquals(sourceCid, terminal["sourceReceiptCid"])
            assertEquals(listOf(mapOf("kind" to "lcnc-run", "id" to sourceReceipt["jobId"], "cid" to sourceCid)), terminal["consumed"])
            assertEquals(LcncConsumedLedger().fingerprintOf(listOf(sourceCid)), terminal["inputFingerprint"])
            assertEquals(1, produced)
        }
    }

    @Test fun duplicateQueuedStartsCannotRemoveTheWinningActiveRun() = runBlocking {
        fixture {
            register("one") { emptyMap() }
            register("two") { emptyMap() }
            val entered = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            var effects = 0
            ctx.lcncRunners["test.task"] = LcncNodeRunner { _, _ -> effects++; entered.complete(Unit); hold.await(); emptyMap() }
            val entry = enqueue("task")
            val firstEntry = runs.reserveQueued(entry)
            val secondEntry = runs.reserveQueued(entry)
            coroutineScope {
                val first = async { assertIs<HttpResponse>(assertNotNull(fanout.dispatch(request("one"), firstEntry).completion).await()) }
                val second = async { assertIs<HttpResponse>(assertNotNull(fanout.dispatch(request("two"), secondEntry).completion).await()) }
                withTimeout(5_000) { entered.await() }
                val refused = withTimeout(5_000) { kotlinx.coroutines.selects.select {
                    first.onAwait { it }; second.onAwait { it }
                } }
                assertEquals(409, refused.status, refused.body)
                val runId = entry.a.jobId.removePrefix("lcnc/run/")
                assertTrue(runs.cancel(runId), "A duplicate loser must leave the winner's active Job installed")
                val completed = withTimeout(5_000) { awaitAll(first, second) }
                assertEquals(listOf(409, 499), completed.map { it.status }.sorted())
                assertEquals(1, effects)
            }
            runs.releaseQueued(entry)
            assertEquals(200, probe().status)
        }
    }

    @Test fun duplicateClaimCleanupCannotRetireAClaimWaitingToEnterTheService() = runBlocking {
        fixture {
            val beforeExecution = CompletableDeferred<Unit>()
            val continueExecution = CompletableDeferred<Unit>()
            var effects = 0
            ctx.lcncRunners["test.task"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
            suspend fun release(claim: NuidFanoutElement.Claim) {
                @Suppress("UNCHECKED_CAST")
                runs.releaseQueued(claim.payload as LcncRunQueueEntry)
            }
            fanout.register(Workgroup("gated", Subnet.local, traitSpaceOf(request("gated").capability)), cleanup = ::release) { claim ->
                @Suppress("UNCHECKED_CAST")
                val entry = claim.payload as LcncRunQueueEntry
                beforeExecution.complete(Unit)
                continueExecution.await()
                val selected = checkNotNull(currentCoroutineContext()[NuidInvocation])
                withContext(entry.b + currentCoroutineContext().job + fanout + selected.context) {
                    runs.executeQueued(entry, selected.value)
                }
            }
            fanout.register(Workgroup("refused", Subnet.local, traitSpaceOf(request("refused").capability)), cleanup = ::release) {
                throw IllegalStateException("claim refused before service admission")
            }
            val original = enqueue("task")
            val winnerEntry = runs.reserveQueued(original)
            val loserEntry = runs.reserveQueued(original)
            val winner = assertNotNull(fanout.dispatch(request("gated"), winnerEntry).completion)
            withTimeout(5_000) { beforeExecution.await() }
            val loser = assertNotNull(fanout.dispatch(request("refused"), loserEntry).completion)
            assertEquals("claim refused before service admission", assertFailsWith<IllegalStateException> {
                withTimeout(5_000) { loser.await() }
            }.message)
            runs.releaseQueued(loserEntry)
            continueExecution.complete(Unit)
            val completed = assertIs<HttpResponse>(withTimeout(5_000) { winner.await() })
            assertEquals(200, completed.status, completed.body)
            assertEquals(1, effects)
            assertEquals(3L, store.card(original.a.jobId)?.revision)
            assertEquals(200, probe().status)
        }
    }

    @Test fun explicitPinsRejectInvalidVersionsBeforeQueuePublicationOrLegacyEffects() = runBlocking {
        val cas = PinCas()
        fixture(cas) {
            var effects = 0
            register("task") { effects++; emptyMap() }
            val rootProgram = program("task")
            val rootCid = ctx.casStore.put(LcncProgramConfix.toJson(rootProgram).encodeToByteArray())
            val malformed = ctx.casStore.put("[]".encodeToByteArray())
            val invalid = ctx.casStore.put(LcncProgramConfix.toJson(LcncProgram("child", emptySeriesOf(),
                s_[LcncWire("absent", "value", "missing", "value")])).encodeToByteArray())
            val missing = ContentId.of("not stored".encodeToByteArray())
            val cases: List<Pair<Any?, Int>> = listOf(
                null to 400, listOf("child") to 400, mapOf(1 to rootCid.value) to 400,
                mapOf("child" to 1) to 400, mapOf("child" to "invalid") to 400,
                mapOf("child" to missing.value) to 404, mapOf("child" to malformed.value) to 400,
                mapOf("child" to invalid.value) to 400, mapOf("task" to missing.value) to 409,
            )
            for ((pins, status) in cases) {
                val request = mapOf("pinnedVersions" to pins)
                val queued = runs.enqueue("task", rootProgram, false, emptyMap(), request)
                assertEquals(status, assertNotNull(queued.a).status, "queue pins=$pins")
                assertNull(queued.b)
                val legacy = runs.execute("task", rootProgram, false, emptyMap(), request)
                assertEquals(status, legacy.status, legacy.body)
            }
            val pinned = ctx.casStore.put(LcncProgramConfix.toJson(program("child")).encodeToByteArray())
            cas.replacement = pinned j LcncProgramConfix.toJson(program("different")).encodeToByteArray()
            val corruptRequest = mapOf("pinnedVersions" to mapOf("child" to pinned.value))
            val corruptQueue = runs.enqueue("task", rootProgram, false, emptyMap(), corruptRequest)
            assertEquals(409, assertNotNull(corruptQueue.a).status)
            assertNull(corruptQueue.b)
            val corruptLegacy = runs.execute("task", rootProgram, false, emptyMap(), corruptRequest)
            assertEquals(409, corruptLegacy.status, corruptLegacy.body)
            assertEquals(0, effects)
            assertTrue(store.cards().isEmpty())
            cas.replacement = null
            val accepted = runs.execute("task", rootProgram, false, emptyMap(),
                mapOf("pinnedVersions" to mapOf("task" to rootCid.value, "child" to pinned.value)))
            assertEquals(200, accepted.status, accepted.body)
            assertEquals(pinned.value, (json(accepted)["programVersions"] as Map<*, *>)["child"])
            assertEquals(1, effects)
        }
    }

    @Test fun missingQueuedPinCanBeTerminallyRefusedUsingItsOriginalReceipt() = runBlocking {
        val cas = PinCas()
        fixture(cas) {
            var effects = 0
            register("task") { effects++; emptyMap() }
            val pin = cas.put(LcncProgramConfix.toJson(program("child")).encodeToByteArray())
            val original = enqueue("task", request = mapOf("pinnedVersions" to mapOf("child" to pin.value)))
            cas.hidden = pin
            val failure = assertFailsWith<LcncRunService.QueueFailure> { runs.reserveQueued(original) }
            assertEquals(404, failure.status)
            assertEquals("pinned_version_not_found", failure.code)
            val refused = runs.failQueued(original, failure.status, failure.code)
            assertEquals(404, refused.status, refused.body)
            assertEquals("refused", receipt(original.a.jobId)["status"])
            assertEquals(2L, store.card(original.a.jobId)?.revision)
            assertEquals(0, effects)
            val restarted = LcncRunService(ctx, store) { LcncContracts.all().associateBy { it.type } }
            restarted.recover()
            assertEquals(0, restarted.queuedRecovery().size)
            restarted.drain()
        }
    }

    @Test fun recoveryRefusesCorruptPinnedBodiesAndStillOffersHealthyOriginalQueues() = runBlocking {
        val cas = PinCas()
        fixture(cas) {
            var effects = 0
            ctx.lcncRunners["test.task"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
            val pin = cas.put(LcncProgramConfix.toJson(program("child")).encodeToByteArray())
            val corrupt = enqueue("corrupt", program("task"),
                request = mapOf("pinnedVersions" to mapOf("child" to pin.value)))
            val healthy = enqueue("healthy", program("task"))
            cas.replacement = pin j LcncProgramConfix.toJson(program("different")).encodeToByteArray()
            val restarted = LcncRunService(ctx, store) { LcncContracts.all().associateBy { it.type } }
            restarted.recover()
            assertEquals("refused", receipt(corrupt.a.jobId)["status"])
            assertEquals("pinned_version_identity_mismatch", receipt(corrupt.a.jobId)["error"])
            assertEquals(listOf(healthy.a.cid), restarted.queuedRecovery().view.map { it.a.cid })
            assertEquals(0, effects)
            register("restored", restarted) { emptyMap() }
            val reserved = restarted.reserveQueued(restarted.queuedRecovery()[0])
            val result = assertIs<HttpResponse>(assertNotNull(fanout.dispatch(request("restored"), reserved).completion).await())
            assertEquals(200, result.status, result.body)
            assertEquals(1, effects)
            restarted.drain()
        }
    }

    @Test fun anAdmittedQueuedChildKeepsItsFamilyWhileItsWorkerIsBusyAndParentRetires() = runBlocking {
        fixture {
            val busyEntered = CompletableDeferred<Unit>()
            val busyRelease = CompletableDeferred<Unit>()
            val childEntered = CompletableDeferred<Unit>()
            val childRelease = CompletableDeferred<Unit>()
            val heldEntered = CompletableDeferred<Unit>()
            val heldRelease = CompletableDeferred<Unit>()
            val childResult = CompletableDeferred<Deferred<Any?>>()
            lateinit var queued: LcncRunQueueEntry
            ctx.lcncRunners["test.child"] = LcncNodeRunner { _, _ -> childEntered.complete(Unit); childRelease.await(); emptyMap() }
            register("shared") { busyEntered.complete(Unit); busyRelease.await(); emptyMap() }
            register("parent") {
                queued = enqueue("child")
                childResult.complete(assertNotNull(dispatchQueued("shared", queued).completion))
                emptyMap()
            }
            register("held") { heldEntered.complete(Unit); heldRelease.await(); emptyMap() }
            coroutineScope {
                val busy = async { run("shared") }
                withTimeout(5_000) { busyEntered.await() }
                val parent = withTimeout(5_000) { run("parent") }
                assertEquals(200, parent.status, parent.body)
                assertFalse(childEntered.isCompleted)
                val abandoned = async { childResult.await().await() }
                abandoned.cancelAndJoin()
                val held = async { run("held") }
                withTimeout(5_000) { heldEntered.await() }
                assertEquals(429, probe().status, "The queued child retains its completed parent's family slot")
                busyRelease.complete(Unit)
                assertEquals(200, withTimeout(5_000) { busy.await() }.status)
                withTimeout(5_000) { childEntered.await() }
                val running = receipt(queued.a.jobId)
                assertEquals(json(parent)["executionFamily"], running["executionFamily"])
                assertEquals(json(parent)["runId"], running["parentRunId"])
                childRelease.complete(Unit)
                assertEquals(200, assertIs<HttpResponse>(withTimeout(5_000) { childResult.await().await() }).status)
                heldRelease.complete(Unit)
                assertEquals(200, withTimeout(5_000) { held.await() }.status)
            }
        }
    }

    @Test fun recoveryOffersOnlyOriginalUnstartedQueuesAndRefusesStartedWork() = runBlocking {
        fixture {
            val pending = enqueue("pending")
            val started = enqueue("started")
            val initial = runs.queuedReceipt(started)
            val reply = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(mapOf("type" to "start", "jobId" to started.a.jobId,
                "owner" to "lcnc-runner", "expectedRevision" to 1, "idempotencyKey" to "started-before-crash",
                "lcncRun" to (initial + mapOf("status" to "running"))), reply))
            assertIs<BoardApply.Committed>(reply.await())
            val restarted = LcncRunService(ctx, store) { LcncContracts.all().associateBy { it.type } }
            restarted.recover()
            assertEquals(listOf(pending.a.cid), restarted.queuedRecovery().view.map { it.a.cid })
            assertEquals("interrupted", receipt(started.a.jobId)["status"])
            assertEquals("VOLATILE", receipt(pending.a.jobId)["queueDurability"])
            register("pending", service = restarted) { emptyMap() }
            val reserved = restarted.reserveQueued(restarted.queuedRecovery()[0])
            val result = assertIs<HttpResponse>(withTimeout(5_000) {
                assertNotNull(fanout.dispatch(request("pending"), reserved).completion).await()
            })
            assertEquals(200, result.status, result.body)
            assertEquals(pending.a.jobId, json(result)["jobId"])
            restarted.releaseQueued(reserved)
            restarted.drain()
        }
    }

    @Test fun ordinaryWipRefusalSettlesTheOriginalQueueWithoutEffects() = runBlocking {
        fixture {
            repeat(3) { index ->
                for ((revision, operation) in listOf("submit", "start").withIndex()) {
                    val reply = CompletableDeferred<BoardApply>()
                    store.intake.send(BoardIntake(mapOf("type" to operation, "jobId" to "ordinary$index",
                        "idempotencyKey" to "ordinary$index/$operation", "expectedRevision" to revision), reply))
                    assertIs<BoardApply.Committed>(reply.await())
                }
            }
            var effects = 0
            register("refused") { effects++; emptyMap() }
            val queued = enqueue("refused")
            val admitted = dispatchQueued("refused", queued)
            val result = assertIs<HttpResponse>(withTimeout(5_000) { assertNotNull(admitted.completion).await() })
            assertEquals(409, result.status, result.body)
            assertEquals(0, effects)
            assertEquals("refused", receipt(queued.a.jobId)["status"])
            assertEquals(2L, store.card(queued.a.jobId)?.revision)
            runs.recover()
            assertEquals(0, runs.queuedRecovery().size)
        }
    }

    @Test fun dependencyFailuresAndCancellationReleaseAllFamilyCapacity() = runBlocking {
        fixture {
            register("failure") { error("dependency failed") }
            register("parent") {
                val child = run("failure")
                assertEquals(400, child.status, child.body)
                error("parent failed")
            }
            repeat(3) {
                val result = withTimeout(5_000) { run("parent") }
                assertEquals(400, result.status, result.body)
            }
            val entered = List(3) { CompletableDeferred<String>() }
            val release = CompletableDeferred<Unit>()
            repeat(3) { index ->
                register("cancel$index") {
                    val row = ctx.blackboard.snapshot().values.values.filterIsInstance<Map<*, *>>()
                        .single { it["program"] == "cancel$index" && it["status"] == "running" }
                    entered[index].complete(row["runId"] as String)
                    release.await()
                    emptyMap()
                }
            }
            coroutineScope {
                val roots = (0..2).map { index -> async { run("cancel$index") } }
                val ids = withTimeout(5_000) { entered.map { it.await() } }
                assertEquals(429, probe().status)
                ids.forEach { assertTrue(runs.cancel(it)) }
                withTimeout(5_000) { roots.awaitAll() }.forEach { assertEquals(499, it.status, it.body) }
            }
            assertEquals(200, probe().status)
            val recovered = List(3) { CompletableDeferred<Unit>() }
            val recoveredRelease = CompletableDeferred<Unit>()
            repeat(3) { index -> register("recovered$index") {
                recovered[index].complete(Unit)
                recoveredRelease.await()
                emptyMap()
            } }
            coroutineScope {
                val roots = (0..2).map { index -> async { run("recovered$index") } }
                try {
                    withTimeout(5_000) { recovered.forEach { it.await() } }
                    assertEquals(429, probe().status)
                } finally { recoveredRelease.complete(Unit) }
                withTimeout(5_000) { roots.awaitAll() }.forEach { assertEquals(200, it.status, it.body) }
            }
        }
    }
}
