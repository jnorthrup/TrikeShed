package borg.trikeshed.kanban.module

import borg.trikeshed.couch.*
import borg.trikeshed.context.nuid.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class LcncRunEvidenceTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root: Path
    private val files = JvmFileOperations()

    private fun cas() = FileCasStore(files, root.resolve("cas").toString())

    private suspend fun fixture(cas: CasStore, body: suspend (ModuleContext, BoardStoreElement, LcncRunService) -> Unit) {
        val owner = SupervisorJob(currentCoroutineContext()[Job])
        val scope = CoroutineScope(currentCoroutineContext() + owner)
        val couchStore = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("evidence", couchStore, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(couchStore, cas),
            ModuleRouteRegistry(), scope, { 1234L }, root.toFile())
        val store = BoardStoreElement(JvmBoardWal(root.resolve("board").toFile()), cas, parentJob = owner)
        store.open()
        val runs = LcncRunService(ctx, store) { LcncContracts.all().associateBy { it.type } }
        try { runs.recover(); body(ctx, store, runs) }
        finally { runs.drain(); store.drain(); store.close(); owner.cancelAndJoin() }
    }

    private fun json(text: String): Map<*, *> = assertIs<Map<*, *>>(reify(text))
    private fun command(cas: CasStore, cid: Any?): Map<*, *> =
        json(assertNotNull(cas.get(ContentId(cid as String))).decodeToString())

    private fun recipient() = LcncProgram("recipient", s_[
        LcncNode("input", "scope.in", params = mapOf("name" to "evidence")),
        LcncNode("receive", "evidence.receive"),
        LcncNode("output", "scope.out", params = mapOf("name" to "evidence")),
    ], s_[LcncWire("input", "value", "receive", "evidence"), LcncWire("receive", "evidence", "output", "value")])

    private suspend fun runQueued(ctx: ModuleContext, runs: LcncRunService, entry: LcncRunQueueEntry,
                                  latestAbsent: Boolean = false): Map<*, *> {
        val fanout = NuidFanoutElement(parentJob = currentCoroutineContext().job)
        fanout.open()
        val element = LcncWorkgroupElement(ctx.blackboard, fanout,
            { LcncContracts.all().associateBy { it.type } }, bind = { group, _, _ ->
                fanout.register(group, cleanup = { claim ->
                    @Suppress("UNCHECKED_CAST")
                    runs.releaseQueued(claim.payload as LcncRunQueueEntry)
                }) { claim ->
                    @Suppress("UNCHECKED_CAST")
                    val selectedEntry = claim.payload as LcncRunQueueEntry
                    val selected = checkNotNull(currentCoroutineContext()[NuidInvocation])
                    withContext(selectedEntry.b + currentCoroutineContext().job + fanout + selected.context) {
                        runs.executeQueued(selectedEntry, selected.value)
                    }
                }
            }, parentJob = currentCoroutineContext().job)
        var reserved = entry
        try {
            element.open()
            if (latestAbsent) assertEquals(0, element.registrations.size)
            reserved = runs.reserveQueued(entry)
            val receipt = runs.queuedReceipt(reserved)
            val cid = ContentId(receipt["programCid"] as String)
            val program = LcncProgramConfix.fromJson(receipt["program"] as String,
                assertNotNull(ctx.casStore.get(cid)).decodeToString())
            element.pin(program, cid, Subnet.parse(receipt["queueDeclaredSubnet"] as? String ?: "local"))
            val request = receipt["queueRequest"] as Map<*, *>
            val admitted = fanout.dispatch(nuid(Capability.Custom("lcnc", "program/${program.name}/${cid.value}"),
                Nonce.RandomBytes(), Subnet.parse(request["subnet"] as String)), reserved)
            val response = assertIs<borg.trikeshed.litebike.JvmKanbanServer.HttpResponse>(
                withTimeout(5_000) { assertNotNull(admitted.completion).await() })
            assertEquals(200, response.status, response.body)
            return json(response.body)
        } finally {
            withContext(NonCancellable) {
                element.stopAdmission()
                fanout.drain()
                element.drain()
                runs.releaseQueued(reserved)
            }
        }
    }

    private suspend fun enqueueRebuild(runs: LcncRunService, receiptCid: String): LcncRunQueueEntry {
        val prepared = runs.enqueueRebuild(ContentId(receiptCid))
        assertNull(prepared.a, prepared.a?.body)
        return assertNotNull(prepared.b)
    }

    @Test fun unresolvedCouncilDecisionRetainsFailedRunEvidenceWithoutPublishingARuling() = runBlocking {
        val retained = cas()
        fixture(retained) { ctx, _, runs ->
            val decisions = InMemoryRecordStore()
            ctx.lcncRunners.putAll(CouncilNodes.registry(
                CouncilDialog { error("recording must not call a model") }, RecordSeams.inMemory(decisions)))
            val program = LcncProgram("unresolved-council", s_[
                LcncNode("input", "scope.in", params = mapOf("name" to "verdict")),
                LcncNode("transcript", "scope.in", params = mapOf("name" to "transcript")),
                LcncNode("record", "council.record", params = mapOf("caseId" to "unresolved")),
                LcncNode("output", "scope.out", params = mapOf("name" to "ruling")),
            ], s_[LcncWire("input", "value", "record", "verdict"),
                LcncWire("transcript", "value", "record", "transcript"),
                LcncWire("record", "report", "output", "value")])
            val verdict = mapOf("disposition" to "pending", "needsClarification" to true,
                "clarificationQuestion" to "Missing evidence?", "mistrial" to false)
            val result = runs.execute(program.name, program, false,
                mapOf("verdict" to verdict, "transcript" to "synthetic testimony"), emptyMap<String, Any?>())
            assertEquals(400, result.status, result.body)
            val receipt = json(result.body)
            assertEquals("failed", receipt["status"])
            assertEquals("execution", receipt["phase"])
            assertTrue(receipt["error"].toString().contains("unresolved clarification"))
            val committed = command(retained, receipt["receiptCid"])["lcncRun"] as Map<*, *>
            assertEquals("failed", committed["status"])
            assertEquals(receipt["error"], committed["error"])
            val evidence = json(assertNotNull(retained.get(ContentId(receipt["invocationsCid"] as String))).decodeToString())
            val record = (evidence["entries"] as List<*>).map { it as Map<*, *> }.single { it["nodeId"] == "record" }
            assertEquals("failed", record["status"])
            assertTrue(decisions.cas.isEmpty())
            assertTrue(decisions.couch.isEmpty())
            assertTrue(decisions.blackboard.isEmpty())
            assertTrue(decisions.kif.isEmpty())
            assertTrue(decisions.rulings.isEmpty())
            assertTrue(decisions.mistrials.isEmpty())
        }
    }

    @Test fun trustedRebuildRetainsSourceEvidenceAndWithdrawnScopeAcrossRestart() = runBlocking {
        var producerCalls = 0
        var recipientCalls = 0
        lateinit var original: Map<*, *>
        lateinit var source: Map<*, *>
        lateinit var firstRebuild: Map<*, *>
        fixture(cas()) { ctx, store, runs ->
            ctx.lcncRunners["evidence.produce"] = LcncNodeRunner { _, _ ->
                producerCalls++
                mapOf("value" to mapOf("summary" to "Observed summary",
                    "ballots" to listOf(mapOf("seat" to "reviewer", "approve" to false, "weight" to 2)),
                    "agent" to AgentRunResult("agent", "task", "stub", exit = 0).receipt()))
            }
            val producer = LcncProgram("producer", s_[LcncNode("emit", "evidence.produce"),
                LcncNode("out", "scope.out", mapOf("name" to "evidence"))],
                s_[LcncWire("emit", "value", "out", "value")])
            val produced = runs.execute("producer", producer, false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, produced.status, produced.body)
            source = json(produced.body)
            ctx.lcncRunners["evidence.receive"] = LcncNodeRunner { _, inputs ->
                recipientCalls++; mapOf("evidence" to inputs["evidence"])
            }
            val program = recipient().copy(controls = LcncConfixControls(subnet = "local.review"))
            ctx.blackboard.put(LcncBlackboard.programKey(program.name), LcncBlackboard.programEntry(program.name,
                program, LcncContracts.all().associateBy { it.type }), "test")
            val prepared = runs.enqueue(program.name, program, true, emptyMap(),
                mapOf("sourceReceiptCid" to source["receiptCid"], "subnet" to "local.review.worker"))
            assertNull(prepared.a, prepared.a?.body)
            original = runQueued(ctx, runs, assertNotNull(prepared.b))
            val ack = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(mapOf("type" to "acknowledge", "jobId" to source["jobId"],
                "owner" to "lcnc-runner", "expectedRevision" to source["timelineRevision"],
                "idempotencyKey" to "source-after-recipient", "lcncRun" to source), ack))
            assertIs<BoardApply.Committed>(ack.await())
            assertNotEquals(source["receiptCid"], store.card(source["jobId"] as String)?.commandCid?.value)
            ctx.blackboard.remove(LcncBlackboard.programKey(program.name))
            val rebuilt = enqueueRebuild(runs, original["receiptCid"] as String)
            val pending = runs.queuedReceipt(rebuilt)
            assertEquals("local.review", pending["queueDeclaredSubnet"])
            assertEquals("local.review.worker", (pending["queueRequest"] as Map<*, *>)["subnet"])
            firstRebuild = runQueued(ctx, runs, rebuilt, latestAbsent = true)
        }
        fixture(cas()) { ctx, _, runs ->
            ctx.lcncRunners["evidence.receive"] = LcncNodeRunner { _, inputs ->
                recipientCalls++; mapOf("evidence" to inputs["evidence"])
            }
            assertNull(ctx.blackboard.get(LcncBlackboard.programKey("recipient")))
            val again = runQueued(ctx, runs, enqueueRebuild(runs, original["receiptCid"] as String), latestAbsent = true)
            assertEquals(3, setOf(original["runId"], firstRebuild["runId"], again["runId"]).size)
            for (rebuilt in listOf(firstRebuild, again)) {
                assertEquals(original["programCid"], rebuilt["programCid"])
                assertEquals(original["inputs"], rebuilt["inputs"])
                assertEquals(original["returns"], rebuilt["returns"])
                assertEquals(source["receiptCid"], rebuilt["sourceReceiptCid"])
                assertEquals(original["receiptCid"], rebuilt["rebuildOf"])
                assertEquals(original["runId"], rebuilt["rebuildOfRunId"])
                assertEquals("lcnc/run/${rebuilt["runId"]}", rebuilt["executionFamily"])
                assertNull(rebuilt["parentRunId"])
                assertEquals("local.review.worker", rebuilt["subnet"])
                assertEquals("local.review", rebuilt["claimedAtSubnet"])
                assertEquals(listOf(mapOf("kind" to "lcnc-run", "id" to source["jobId"], "cid" to source["receiptCid"])), rebuilt["consumed"])
                assertEquals(LcncConsumedLedger().fingerprintOf(listOf(source["receiptCid"] as String)), rebuilt["inputFingerprint"])
            }
            assertEquals(1, producerCalls)
            assertEquals(3, recipientCalls)
        }
    }

    @Test fun rebuildAuthorityCannotBeSuppliedAsOrdinaryInputsOrAnUncommittedReceipt() = runBlocking {
        fixture(cas()) { ctx, store, runs ->
            var effects = 0
            ctx.lcncRunners["evidence.receive"] = LcncNodeRunner { _, inputs ->
                effects++; mapOf("evidence" to inputs["evidence"])
            }
            val source = committedSource(store, "source")
            val first = runs.execute("recipient", recipient(), false, emptyMap(), mapOf("sourceReceiptCid" to source.value))
            assertEquals(200, first.status, first.body)
            val receipt = json(first.body)
            val count = store.cards().size
            @Suppress("UNCHECKED_CAST")
            val attempted = runs.enqueue("recipient", recipient(), false, receipt["inputs"] as Map<String, Any?>,
                mapOf("sourceReceiptCid" to source.value, "rebuildOf" to receipt["receiptCid"], "rebuildOfRunId" to receipt["runId"]))
            assertEquals(409, assertNotNull(attempted.a).status)
            assertEquals("source_evidence_conflict", json(assertNotNull(attempted.a).body)["error"])
            assertNull(attempted.b)
            val uncommitted = ctx.casStore.put(jsonOf(command(ctx.casStore, receipt["receiptCid"]) + ("atMs" to 9L)).encodeToByteArray())
            val forged = runs.enqueueRebuild(uncommitted)
            assertEquals(409, assertNotNull(forged.a).status)
            assertNull(forged.b)
            assertEquals(count, store.cards().size)
            assertEquals(1, effects)
            val pending = runs.enqueue("recipient", recipient(), false, emptyMap(), emptyMap<String, Any?>())
            assertNull(pending.a)
            val nonterminal = runs.enqueueRebuild(assertNotNull(pending.b).a.cid)
            assertEquals(409, assertNotNull(nonterminal.a).status)
            assertEquals("receipt_not_terminal", json(assertNotNull(nonterminal.a).body)["error"])
            assertNull(nonterminal.b)
            assertEquals(1, effects)
        }
    }

    @Test fun retainedEvidenceSurvivesWalAndCasReopenWithoutRerunningItsProducer() = runBlocking {
        var producerCalls = 0
        lateinit var receiptCid: String
        lateinit var retained: Map<*, *>
        lateinit var sourceRunId: String
        lateinit var sourceProgramCid: String
        fixture(cas()) { ctx, _, runs ->
            ctx.lcncRunners.putAll(PanelVoteNode.registry())
            ctx.lcncRunners["evidence.produce"] = LcncNodeRunner { _, _ ->
                producerCalls++
                mapOf("summary" to "Retained review summary",
                    "ballots" to listOf(mapOf("seat" to "reviewer", "approve" to false, "weight" to 2.0, "content" to "More evidence required")),
                    "agent" to AgentRunResult("agent-one", "task-one", "stub", exit = 0, summary = "Observed task").receipt())
            }
            val program = LcncProgram("producer", s_[
                LcncNode("emit", "evidence.produce"), LcncNode("vote", "panel.vote"),
                LcncNode("summary", "scope.out", params = mapOf("name" to "summary")),
                LcncNode("ballots", "scope.out", params = mapOf("name" to "ballots")),
                LcncNode("agent", "scope.out", params = mapOf("name" to "agent")),
            ], s_[
                LcncWire("emit", "ballots", "vote", "ballots"),
                LcncWire("emit", "summary", "summary", "value"),
                LcncWire("vote", "transcript", "ballots", "value"),
                LcncWire("emit", "agent", "agent", "value"),
            ])
            val response = runs.execute("producer", program, false, emptyMap(), emptyMap<String, Any?>())
            assertEquals(200, response.status, response.body)
            val source = json(response.body)
            receiptCid = source["receiptCid"] as String
            retained = source["returns"] as Map<*, *>
            sourceRunId = source["runId"] as String
            sourceProgramCid = source["programCid"] as String
        }
        fixture(cas()) { ctx, _, runs ->
            assertEquals("completed", (ctx.blackboard.get("lcnc/run/$sourceRunId") as Map<*, *>)["status"])
            var received = 0
            ctx.lcncRunners["evidence.receive"] = LcncNodeRunner { _, inputs ->
                received++
                mapOf("evidence" to inputs["evidence"])
            }
            val response = runs.execute("recipient", recipient(), false, emptyMap(), mapOf("sourceReceiptCid" to receiptCid))
            assertEquals(200, response.status, response.body)
            val result = json(response.body)
            val evidence = (result["returns"] as Map<*, *>)["evidence"] as Map<*, *>
            assertEquals(mapOf("receiptCid" to receiptCid, "runId" to sourceRunId,
                "programCid" to sourceProgramCid, "returns" to retained), evidence)
            assertEquals(false, (((evidence["returns"] as Map<*, *>)["ballots"] as List<*>).single() as Map<*, *>)["approve"])
            assertEquals("task-one", ((evidence["returns"] as Map<*, *>)["agent"] as Map<*, *>)["jobId"])
            assertEquals(1, received)
            assertEquals(1, producerCalls)
            assertEquals(receiptCid, result["sourceReceiptCid"])
            assertEquals(listOf(mapOf("kind" to "lcnc-run", "id" to "lcnc/run/$sourceRunId", "cid" to receiptCid)), result["consumed"])
            assertEquals(LcncConsumedLedger().fingerprintOf(listOf(receiptCid)), result["inputFingerprint"])
            val terminal = command(ctx.casStore, result["receiptCid"])["lcncRun"] as Map<*, *>
            assertEquals(evidence, (terminal["inputs"] as Map<*, *>)["evidence"])
            assertEquals(result["consumed"], terminal["consumed"])
            val running = command(ctx.casStore, terminal["previousReceiptCid"])["lcncRun"] as Map<*, *>
            val initial = command(ctx.casStore, running["previousReceiptCid"])["lcncRun"] as Map<*, *>
            assertEquals("validating", initial["status"])
            assertEquals(receiptCid, initial["sourceReceiptCid"])
            assertEquals(evidence, (initial["inputs"] as Map<*, *>)["evidence"])
        }
    }

    private suspend fun committedSource(store: BoardStoreElement, id: String, changes: Map<String, Any?> = emptyMap(),
                                        owner: String = "lcnc-runner", terminal: Boolean = true): ContentId {
        var committed: BoardApply.Committed? = null
        val operations = if (terminal) listOf("submit", "start", "complete") else listOf("submit", "start")
        for ((revision, operation) in operations.withIndex()) {
            val receipt = mapOf("runId" to id, "programCid" to ContentId.of("program".encodeToByteArray()).value,
                "status" to if (operation == "complete") "completed" else "running", "ok" to (operation == "complete"),
                "returns" to emptyMap<String, Any?>()) + changes
            val reply = CompletableDeferred<BoardApply>()
            store.intake.send(BoardIntake(mapOf("type" to operation, "jobId" to "lcnc/run/$id", "owner" to owner,
                "expectedRevision" to revision, "idempotencyKey" to "$id-$operation", "lcncRun" to receipt), reply))
            committed = assertIs<BoardApply.Committed>(reply.await())
        }
        return assertNotNull(committed).cid
    }

    @Test fun invalidSourcesAndEvidenceCollisionsAreRefusedBeforeRecipientEffects() = runBlocking {
        fixture(cas()) { ctx, store, runs ->
            var effects = 0
            ctx.lcncRunners["evidence.receive"] = LcncNodeRunner { _, inputs -> effects++; mapOf("evidence" to inputs["evidence"]) }
            suspend fun refuse(value: Any?, status: Int, error: String, inputs: Map<String, Any?> = emptyMap()) {
                val response = runs.execute("recipient", recipient(), false, inputs, mapOf("sourceReceiptCid" to value))
                assertEquals(status, response.status, response.body)
                assertEquals(error, json(response.body)["error"])
                assertEquals(0, effects)
            }
            refuse(null, 400, "invalid_source_receipt")
            refuse("malformed", 400, "invalid_source_receipt")
            refuse(ContentId.of("absent".encodeToByteArray()).value, 404, "source_receipt_not_found")
            refuse(ctx.casStore.put("[]".encodeToByteArray()).value, 400, "invalid_source_receipt")
            refuse(committedSource(store, "running", terminal = false).value, 409, "source_receipt_not_completed")
            refuse(committedSource(store, "failed", mapOf("status" to "failed", "ok" to false)).value, 409, "source_receipt_not_completed")
            refuse(committedSource(store, "identity", mapOf("runId" to "other")).value, 400, "invalid_source_receipt")
            refuse(committedSource(store, "owner", owner = "other").value, 400, "invalid_source_receipt")
            refuse(committedSource(store, "shape", mapOf("returns" to emptyList<Any?>())).value, 400, "invalid_source_receipt")
            val source = committedSource(store, "local")
            val raw = command(ctx.casStore, source.value)
            val uncommitted = ctx.casStore.put(jsonOf(raw + ("atMs" to 9999L)).encodeToByteArray())
            refuse(uncommitted.value, 409, "source_receipt_not_committed")
            refuse(source.value, 409, "source_evidence_conflict", mapOf("evidence" to null))
        }
    }

    @Test fun sourceDigestMustMatchBeforeItsRetainedReturnsAreBound() = runBlocking {
        val original = cas()
        var corrupt: ContentId? = null
        val altered = object : CasStore() {
            override val durability get() = original.durability
            override fun put(bytes: ByteArray) = original.put(bytes)
            override fun get(cid: ContentId): ByteArray? = if (cid == corrupt) "altered".encodeToByteArray() else original.get(cid)
        }
        fixture(altered) { ctx, store, runs ->
            val source = committedSource(store, "digest")
            corrupt = source
            var effects = 0
            ctx.lcncRunners["evidence.receive"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
            val response = runs.execute("recipient", recipient(), false, emptyMap(), mapOf("sourceReceiptCid" to source.value))
            assertEquals(409, response.status, response.body)
            assertEquals("source_receipt_identity_mismatch", json(response.body)["error"])
            assertEquals(0, effects)
        }
    }
}
