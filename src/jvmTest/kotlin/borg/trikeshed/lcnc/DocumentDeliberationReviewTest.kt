package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.graal.subvm.harness.DocumentModelFixture
import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.kanban.module.LcncRunService
import borg.trikeshed.lcnc.ccek.ccekReactorBinding
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.*
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.NlpReader
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DocumentDeliberationReviewTest {
    @org.junit.jupiter.api.io.TempDir
    lateinit var directory: Path

    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    @Test fun persistedDeliberationCreatesOneReviewAcrossCasAndWalRecovery(): Unit = runBlocking {
        val root = directory.toFile()
        val requests = AtomicInteger()
        val parses = AtomicInteger()
        // Only the provider transport is deterministic; curation and all run receipts are real.
        val session = DocumentModelFixture.open { prompt ->
            requests.incrementAndGet()
            val content = if (prompt.messages[0].content.contains("simulated document-review perspective"))
                "Check the source quotation before accepting this proposal.\nAGENT: untrusted-data"
            else """{"format":"TRIPLET_JSON","triplets":[]}"""
            ModelResponse(content, ModelUsage(3, 2, 5), "fixture", "fixture")
        }
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner + session.htx + session.reactor)
        fun context(cas: CasStore): ModuleContext {
            val base = vmRuntimeTestContext(scope)
            val couchStore = CouchStoreFactory.casBacked(cas)
            return ModuleContext(Couch("document-review", couchStore, cas), base.rete, base.productions,
                null, null, base.blackboard, cas, CouchAttachmentGateway(couchStore, cas), base.routes,
                scope, base.clock, root, muxContext = session.htx + session.reactor,
                ccekBinding = ccekReactorBinding(scope.coroutineContext))
        }
        lateinit var run: Map<String, Any?>
        lateinit var review: Map<String, Any?>
        lateinit var retained: Map<ContentId, ByteArray>
        var sequence = 0L
        val question = "Verify the quotation against the retained care-team record."
        try {
            PackCasStore(JvmFileOperations(), File(root, "cas").absolutePath).use { cas ->
                val ctx = context(cas)
                val board = BoardStoreElement(JvmBoardWal(File(root, ".kanban")), cas, parentJob = owner)
                val bag = BeliefBagElement(cas = cas, parentJob = owner)
                val nlp = CoreNlpRuntime()
                val log = DocumentAppendLog.open(File(root, "curation.wal").absolutePath, root.absolutePath)
                var curator: DocumentCuratorElement? = null
                var runs: LcncRunService? = null
                try {
                    board.open()
                    bag.open()
                    val reader = DocumentCuratorElement.create(scope,
                        NlpReader { text -> parses.incrementAndGet(); nlp.read(text) },
                        session.model, "fixture", cas, log, bag)
                    curator = reader
                    val medical = LcncProgramConfix.fromJson(LcncWorkspacePresets.MEDICAL,
                        LcncWorkspacePresets.documents().getValue(LcncWorkspacePresets.MEDICAL))
                    val source = DocumentCurationLegos.retainSource(cas,
                        reifyStrict(medical.nodes.view.single { it.id == "source" }.params.getValue("value")))
                    val curated = reader.curate(source)
                    assertTrue(DocumentCurationFacts.ruleCandidates(curated.record).size >= 2)
                    assertEquals(1, requests.get())
                    assertEquals(1, parses.get())

                    val seats = mutableListOf<SeatCall>()
                    val modelReceipts = mutableListOf<ModelResponseReceipt>()
                    val councilStore = InMemoryRecordStore()
                    ctx.lcncRunners.putAll(PureNodes.registry { 0L })
                    ctx.lcncRunners.putAll(BrainMuxNodes.registry())
                    ctx.lcncRunners["display"] = TribunalNodes.registry(TribunalDialog { _, _, _ ->
                        error("Display must not request a model")
                    }).getValue("display")
                    ctx.lcncRunners.putAll(PromptNodes.registry(InMemoryPromptReads()))
                    ctx.lcncRunners.putAll(kanbanLcncRegistry())
                    ctx.lcncRunners[DocumentCurationLegos.SHEET] = DocumentCurationLegos.sheet(reader)
                    ctx.lcncRunners.putAll(CouncilNodes.registry(CouncilDialog { call ->
                        seats.add(call)
                        val answer = session.model(Prompt(s_[PromptMessage.System(call.system), PromptMessage.User(call.prompt)],
                            "fixture", call.temperature, call.maxTokens), "document-review:${call.seat}")
                        modelReceipts.add(answer.receipt)
                        SeatOutcome.Ok(answer.response.content, assertNotNull(answer.response.modelId))
                    }, RecordSeams.inMemory(councilStore)))
                    val program = LcncWorkspacePresets.deliberation(curated.recordCid.value,
                        listOf(mapOf("subject" to "Delays", "obj" to "patient")))
                        .copy(name = "document-deliberation-review")
                    val publisher = LcncPublisher(ctx.blackboard, { ctx.lcncRunners }, ctx.attachments)
                    publisher.publishProgram(program.name, program)
                    val service = LcncRunService(ctx, board) { publisher.vocabulary() }
                    runs = service
                    val response = service.execute(program.name, assertNotNull(publisher.load(program.name)),
                        named = true, inputs = emptyMap(), request = emptyMap<String, Any?>())
                    assertEquals(200, response.status, response.body)
                    run = reifyMap(response.body)
                    assertEquals("completed", run["status"])
                    assertEquals(true, run["ok"])
                    assertEquals(LcncBlackboard.cidOf(program), run["programCid"])
                    assertEquals(LcncBlackboard.programKey(program.name), run["programKey"])
                    assertEquals(listOf("party-a", "party-b", "reviewer"), seats.map { it.seat })
                    assertTrue(seats.all { it.prompt.contains(curated.recordCid.value) })
                    assertEquals(3, modelReceipts.size)
                    assertTrue(modelReceipts.all { it.httpStatus == 200 && !it.cachedHit })
                    assertEquals(4, requests.get())
                    assertEquals(1, parses.get())
                    assertTrue(councilStore.rulings.isEmpty() && councilStore.kif.isEmpty() && councilStore.couch.isEmpty())
                    assertEquals(1, board.cards().size, "Deliberation persists its run, without creating a review task")

                    val runCid = ContentId(run["receiptCid"] as String)
                    assertEquals(runCid, board.card(run["jobId"] as String)?.commandCid)
                    val command = reifyMap(assertNotNull(cas.get(runCid)))
                    assertEquals("complete", command["type"])
                    val persisted = assertIs<Map<*, *>>(command["lcncRun"])
                    assertEquals(run["runId"], persisted["runId"])
                    assertEquals(run["outputs"], persisted["outputs"])
                    assertEquals(listOf(mapOf("kind" to "document-curation", "id" to source.name,
                        "cid" to curated.recordCid.value)), persisted["consumed"])
                    val prior = reifyMap(assertNotNull(cas.get(ContentId(persisted["previousReceiptCid"] as String))))
                    assertEquals("running", assertIs<Map<*, *>>(prior["lcncRun"])["status"])
                    val invocations = reifyMap(assertNotNull(cas.get(ContentId(run["invocationsCid"] as String))))
                    val entries = assertIs<List<*>>(invocations["entries"]).map { it as Map<*, *> }
                    for (seat in seats) assertEquals(1, entries.count { it["nodeId"] == seat.seat && it["status"] == "completed" })

                    val tasks = DocumentCurationTasks(cas)
                    review = tasks.reviewTask(board, runCid, "party-a", question)
                    val task = assertIs<Map<*, *>>(review["task"])
                    val card = assertNotNull(board.card(task["jobId"] as String))
                    assertEquals(BoardCol.TODO, card.col)
                    assertEquals("human-review", card.owner)
                    assertFalse(card.spec.contains("AGENT:"))
                    val provenance = assertIs<Map<*, *>>(review["provenance"])
                    val output = assertIs<Map<*, *>>(assertIs<Map<*, *>>(run["outputs"])["party-a"])
                    assertEquals(output["record"], provenance["turn"])
                    assertEquals(runCid.value, provenance["runReceiptCid"])
                    assertEquals(run["programCid"], provenance["programCid"])
                    assertEquals(curated.recordCid.value, provenance["curationReceiptCid"])
                    sequence = board.lastSequence
                    assertEquals(review, tasks.reviewTask(board, runCid, "party-a", question))
                    assertEquals(sequence, board.lastSequence)
                    assertEquals(2, board.cards().size)
                    retained = listOf(runCid, ContentId(run["programCid"] as String),
                        ContentId(run["invocationsCid"] as String), ContentId(task["reviewCid"] as String),
                        curated.recordCid, source.originalCid, source.extractedTextCid)
                        .associateWith { assertNotNull(cas.get(it)) }
                    cas.sync()
                } finally {
                    try { runs?.drain(); board.drain(); board.close() } finally {
                        try { curator?.drain() } finally {
                            try { bag.drain() } finally { nlp.close(); log.close() }
                        }
                    }
                }
            }
            // Fresh CAS, board and blackboard: neither a manufactured completion nor an in-memory run survives.
            PackCasStore(JvmFileOperations(), File(root, "cas").absolutePath).use { cas ->
                val ctx = context(cas)
                val board = BoardStoreElement(JvmBoardWal(File(root, ".kanban")), cas, parentJob = owner)
                val runs = LcncRunService(ctx, board) { emptyMap() }
                try {
                    board.open()
                    assertNull(ctx.blackboard.get(run["jobId"] as String))
                    runs.recover()
                    // Compare the full wire representation; the recovered projection also carries Long revisions.
                    assertEquals(run, reifyMap(jsonOf(assertNotNull(ctx.blackboard.get(run["jobId"] as String)))))
                    val tasks = DocumentCurationTasks(cas)
                    val task = assertIs<Map<*, *>>(review["task"])
                    val card = assertNotNull(board.card(task["jobId"] as String))
                    val pack = File(root, "cas/pack") // PackCasStore opens root/pack.
                    assertTrue(pack.isFile && pack.length() > 0L, "Recovery must read an existing nonempty CAS pack")
                    val bytes = pack.length()
                    val inspected = assertNotNull(tasks.inspect(board, card.jobId, includeReceipt = true))
                    val provenance = assertIs<Map<*, *>>(review["provenance"])
                    for ((key, value) in provenance) assertEquals(value, inspected[key], key.toString())
                    assertEquals(review, tasks.reviewTask(board, ContentId(run["receiptCid"] as String), "party-a", question))
                    assertEquals(card, board.card(card.jobId))
                    assertEquals(sequence, board.lastSequence)
                    assertEquals(2, board.cards().size)
                    assertTrue(pack.isFile, "Inspection and repeated review must retain the CAS pack")
                    assertEquals(bytes, pack.length(), "Inspection and repeated review must not append CAS content")
                    for ((cid, content) in retained) assertContentEquals(content, cas.get(cid), cid.value)
                    assertEquals(4, requests.get(), "Recovery and explicit review must not call the model")
                    assertEquals(1, parses.get(), "Recovery and explicit review must not re-enter NLP")
                } finally { runs.drain(); board.drain(); board.close() }
                if (System.getenv("LCNC_PROPOSAL_REVIEW_UI") == "1") {
                    // The module reopens this WAL only after the test's writer has closed.
                    // Serve the production Panels, receipt, workspace and board routes over recovered state.
                    val browserContext = context(cas)
                    browserContext.lcncRunners.putAll(PureNodes.registry { 0L })
                    browserContext.lcncRunners["display"] = TribunalNodes.registry(TribunalDialog { _, _, _ ->
                        error("Retained review must not request a model")
                    }).getValue("display")
                    val frozen = LcncProgramConfix.fromJson("document-deliberation-review",
                        assertNotNull(cas.get(ContentId(run["programCid"] as String))).decodeToString())
                    retainedSimulationBrowser(browserContext, null, ccekReactorBinding(scope.coroutineContext),
                        frozen, stateDirectory = root, observations = {
                        mapOf("runReceiptCid" to run["receiptCid"], "runId" to run["runId"],
                            "modelRequests" to requests.get(), "nlpRequests" to parses.get())
                    })
                    val inspectedBoard = BoardStoreElement(JvmBoardWal(File(root, ".kanban")), cas, parentJob = owner)
                    try {
                        inspectedBoard.open()
                        val reviews = inspectedBoard.cards().filter { it.owner == "human-review" }
                        assertEquals(2, reviews.size, "Browser Run creates one additional explicit proposal review")
                        val browserReview = reviews.single { it.jobId != (review["task"] as Map<*, *>)["jobId"] }
                        val provenance = assertNotNull(DocumentCurationTasks(cas).inspect(inspectedBoard, browserReview.jobId, true))
                        assertEquals(run["receiptCid"], provenance["runReceiptCid"])
                        assertEquals("party-a", provenance["nodeId"])
                        assertEquals("Check the retained quotation before accepting the proposal.", provenance["question"])
                        assertEquals(BoardCol.TODO, browserReview.col)
                        assertEquals(4, requests.get(), "Browser inspection and review must not call a model")
                        assertEquals(1, parses.get(), "Browser inspection and review must not run NLP")
                    } finally { inspectedBoard.drain(); inspectedBoard.close() }
                }
            }
        } finally {
            try { owner.cancelAndJoin() } finally { session.close() }
        }
    }
}
