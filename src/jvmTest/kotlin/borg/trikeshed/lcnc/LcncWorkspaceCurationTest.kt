package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.graal.subvm.harness.DocumentModelFixture
import borg.trikeshed.job.*
import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.JvmBoardWal
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.*
import kotlin.test.*

class LcncWorkspaceCurationTest {
    @Test fun medicalAndLegalConstructionsUseRetainedCuratorAndRealStanfordSheets() = runBlocking<Unit> {
        val ctx = vmRuntimeTestContext(this)
        SubVmLegos.register(ctx)
        val cas = ctx.casStore
        val frames = linkedMapOf<Long, ByteArray>()
        val log = object : DurableAppendLog {
            override fun append(sequence: Long, payload: ByteArray): Long {
                frames[sequence] = payload.copyOf(); return sequence
            }
            override fun flush() {}
            override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
                for ((seq, bytes) in frames) onFrame(seq, bytes.copyOf())
                return frames.keys.lastOrNull() ?: 0
            }
            override fun injectCorruptionAfter(sequence: Long) = error("not used")
        }
        val bag = BeliefBagElement(cas = cas)
        bag.open()
        val boardDir = java.nio.file.Files.createTempDirectory("lcnc-document-review").toFile()
        val board = BoardStoreElement(JvmBoardWal(boardDir), cas)
        board.open()
        var modelCalls = 0
        // Deterministic provider response; NLP, retention, curation and sheet projection are real.
        val session = DocumentModelFixture.open { prompt ->
            modelCalls++
            val input = reifyMap(prompt.messages[1].content)
            assertNotNull(input["nlp"])
            val source = input["source"] as Map<*, *>
            val text = source["text"] as String
            val medical = source["name"] == "synthetic-care-team.txt"
            val subject = if (medical) "Delays" else "Disputes"
            val obj = if (medical) "cancellations" else "delays"
            // Initial response invents an adverb absent from the source; correction quotes the real sentence.
            // Wrong offsets alone are not an issue when the canonical curator recovers an exact quotation.
            val corrected = (source["metadata"] as Map<*, *>)["taskId"] != null
            val quote = if (corrected) "$subject cause $obj." else "$subject definitely cause $obj."
            val begin = if (corrected) text.indexOf(quote) else 0
            ModelResponse(jsonOf(mapOf("format" to "TRIPLET_JSON", "triplets" to listOf(mapOf(
                "subject" to subject, "predicate" to "cause", "object" to obj, "confidence" to 0.9,
                "quote" to quote, "begin" to begin, "end" to begin + quote.length,
                "polarity" to true, "modality" to "asserted")))), ModelUsage(0, 0, 0), "fixture", "fixture")
        }
        val nlp = CoreNlpRuntime()
        val curator = DocumentCuratorElement.create(CoroutineScope(coroutineContext + session.htx + session.reactor),
            nlp, session.model, "fixture", cas, log, bag)
        try {
            val curate = DocumentCurationLegos.curate(curator, DocumentCurationLegos::reference)
            ctx.lcncRunners.putAll(PureNodes.registry { 0L })
            ctx.lcncRunners.putAll(kanbanLcncRegistry())
            ctx.lcncRunners.putAll(DocumentCurationTasks(cas).registry(board))
            ctx.lcncRunners[DocumentCurationLegos.SHEET] = DocumentCurationLegos.sheet(curator)
            ctx.lcncRunners["display"] = LcncNodeRunner { _, inputs -> mapOf("rendered" to inputs["x"]) }
            ctx.lcncRunners["note"] = LcncNodeRunner { _, _ -> emptyMap() }
            ctx.lcncRunners[DocumentCurationLegos.CURATE] = LcncNodeRunner { node, inputs ->
                val source = DocumentCurationLegos.retainSource(cas, inputs["source"] ?: inputs["source?"])
                curate.run(node, inputs + ("source" to source))
            }
            val binding = ccekReactorBinding(coroutineContext + session.htx + session.reactor)
            suspend fun execute(program: LcncProgram): LcncRunner.ScopeResult {
                assertTrue(LcncTypeCheck.check(program).isEmpty(), LcncTypeCheck.check(program).joinToString { it.render() })
                val ledger = LcncInvocationLedger()
                val runner = LcncRunner(ctx.lcncRunners).also { it.invocations = ledger }
                val run = LcncCcekAssembly(binding, runner).launch(program.name, program)
                val result = withTimeout(120_000) { run.result.await() }
                assertTrue(run.result.isCompleted)
                val entries = ledger.snapshot()["entries"] as List<*>
                assertTrue(entries.isNotEmpty())
                assertTrue(entries.map { it as Map<*, *> }.all {
                    it["status"] == "completed" || it["status"] == "skipped" && it["reason"] == "annotation"
                })
                return result
            }
            val sheets = linkedMapOf<String, Any?>()
            val outputs = linkedMapOf<String, Any?>()
            val reviewReceipts = mutableListOf<ContentId>()
            for ((name, doc) in LcncWorkspacePresets.documents()) {
                if (name !in setOf(LcncWorkspacePresets.MEDICAL, LcncWorkspacePresets.LEGAL)) continue
                val program = LcncProgramConfix.fromJson(name, doc)
                val result = execute(program)
                val output = result.nodeOutputs.getValue("curate")
                val cid = ContentId(output["receiptCid"] as String)
                val record = assertNotNull(curator.record(cid))
                reviewReceipts.add(cid)
                assertEquals("AVAILABLE", output["nlpStatus"])
                assertTrue(assertNotNull(record.nlp).sentences.size >= 4)
                assertEquals(record.source.originalCid, ContentId.of(record.source.text.encodeToByteArray()))
                assertEquals(output["sheet"], result.nodeOutputs.getValue("evidence")["rendered"])
                val candidates = DocumentCurationFacts.ruleCandidates(record).view.toList()
                assertTrue(candidates.size >= 2, "$name must produce real Stanford positive and negative rule candidates")
                val simulation = result.nodeOutputs.getValue("simulation")
                assertEquals(true, simulation["hypothetical"])
                val firings = (simulation["firings"] as List<*>).map { it as Map<*, *> }
                assertEquals(6, firings.size, "Both conflicting source claims are evaluated for each of three hypothetical parties")
                assertTrue(firings.any { (it["supportNegative"] as Long) > 0 })
                assertTrue(firings.any { (it["supportPositive"] as Long) > 0 })
                assertTrue(firings.all { it["provenanceCid"] == record.source.extractedTextCid.value })
                for (axiom in record.nlpAxioms.view) {
                    assertEquals(axiom.rule, RuleNodes.rule(candidates.single { it["ruleCid"] == axiom.rule.ruleCid.value }))
                }
                val before = DocumentCuratorCodec.encode(record)
                val calls = modelCalls
                val framesBefore = frames.size
                val rerun = KanbanCausalNodes.runner().run(program.nodes.view.single { it.id == "simulation" },
                    mapOf("rules" to candidates, "assertions" to simulation["assertions"]))
                assertEquals(simulation, rerun)
                assertEquals(calls, modelCalls)
                assertEquals(framesBefore, frames.size)
                assertContentEquals(before, DocumentCuratorCodec.encode(record))
                val review = LcncWorkspacePresets.review(cid.value)
                val reviewed = execute(review)
                val createdTasks = reviewed.nodeOutputs.getValue("tasks")["tasks"] as List<*>
                assertEquals(1, createdTasks.size, "Review must create the quotation issue; calls=$modelCalls; " +
                    "model=${record.model?.content}; proposals=${record.proposals.view.toList()}; result=${reviewed.nodeOutputs["tasks"]}")
                val created = createdTasks.single() as Map<*, *>
                val taskId = created["jobId"] as String
                assertEquals("quotation", (created["issue"] as Map<*, *>)["kind"])
                assertEquals(BoardCol.TODO, board.card(taskId)?.col)
                val sequence = board.lastSequence
                execute(review)
                assertEquals(sequence, board.lastSequence, "Repeated review must not duplicate board commands")
                assertEquals(calls, modelCalls, "Retained review never invokes the model")
                assertEquals(framesBefore, frames.size, "Retained review never invokes the parser/curator")

                val inspected = assertNotNull(DocumentCurationTasks(cas).inspect(board, taskId))
                assertEquals(sequence, board.lastSequence, "Opening a board task does not repeat review")
                assertEquals(calls, modelCalls, "Opening a board task does not repeat curation")
                assertEquals(record.instructions, inspected["instructions"])
                val correction = LcncWorkspacePresets.correction(taskId, inspected["revision"].toString(), inspected["receiptCid"] as String,
                    mapOf("text" to record.source.text + "\n"))
                val corrected = execute(correction)
                val nextCid = ContentId(corrected.nodeOutputs.getValue("curate")["receiptCid"] as String)
                val next = assertNotNull(curator.record(nextCid))
                assertEquals(record.source.originalCid, next.source.originalCid)
                assertNotEquals(record.source.extractedTextCid, next.source.extractedTextCid)
                assertEquals(record.instructions, next.instructions)
                assertEquals(listOf(taskId), next.source.metadata["taskId"])
                assertEquals(listOf(cid.value), next.source.metadata["priorReceiptCid"])
                assertEquals(1, (corrected.nodeOutputs.getValue("tasks")["completed"] as List<*>).size)
                assertEquals(BoardCol.DONE, board.card(taskId)?.col)
                assertEquals(calls + 1, modelCalls)
                val doneSequence = board.lastSequence
                execute(LcncWorkspacePresets.review(nextCid.value))
                assertEquals(doneSequence, board.lastSequence)
                assertContentEquals(before, DocumentCuratorCodec.encode(assertNotNull(curator.record(cid))))
                // An old revision cannot admit another correction or spend a model request.
                val stale = runCatching { execute(correction) }.exceptionOrNull()
                assertNotNull(stale)
                assertTrue(stale.message.orEmpty().contains("receipt changed"))
                assertEquals(doneSequence, board.lastSequence)
                assertEquals(calls + 1, modelCalls)
                val family = DocumentCurationLegos.output(cid, record)["sheets"] as List<*>
                assertTrue(family.size > 2)
                assertEquals("document/${cid.value}", (family.first() as Map<*, *>)["id"])
                outputs[name] = output
                sheets[cid.value] = family
            }
            assertEquals(4, modelCalls)
            assertTrue(frames.isNotEmpty())
            board.drain()
            val recovered = BoardStoreElement(JvmBoardWal(boardDir), cas)
            recovered.open()
            try {
                assertEquals(2, recovered.cards().size)
                assertTrue(recovered.cards().all { it.col == BoardCol.DONE && it.spec.contains("DOCUMENT: sha256:") })
            } finally { recovered.drain(); recovered.close() }
            // Optional browser fixture is a real recovered board and CAS, not intercepted HTTP.
            System.getenv("LCNC_UI_REVIEW_HOME")?.let { destination ->
                val root = java.io.File(destination)
                require(!java.io.File(root, ".kanban").exists() && !java.io.File(root, "cas").exists()) {
                    "Review fixture requires an unused state directory"
                }
                PackCasStore(borg.trikeshed.userspace.nio.file.spi.JvmFileOperations(),
                    java.io.File(root, "cas").absolutePath).use { retained ->
                    for (cid in reviewReceipts) {
                        val record = assertNotNull(curator.record(cid))
                        for (content in s_[cid, record.source.originalCid, record.source.extractedTextCid].view)
                            assertEquals(content, retained.put(assertNotNull(cas.get(content))))
                    }
                    val reviewBoard = BoardStoreElement(JvmBoardWal(java.io.File(root, ".kanban")), retained)
                    reviewBoard.open()
                    try {
                        val tasks = DocumentCurationTasks(retained)
                        for (cid in reviewReceipts) tasks.tasks(reviewBoard, cid)
                        val card = reviewBoard.cards().first()
                        val task = assertNotNull(tasks.inspect(reviewBoard, card.jobId))
                        val prior = assertNotNull(curator.record(ContentId(task["receiptCid"] as String)))
                        tasks.correct(reviewBoard, card.jobId, card.revision, ContentId(task["receiptCid"] as String),
                            mapOf("text" to prior.source.text + "\nPending reviewed correction."))
                    } finally { reviewBoard.drain(); reviewBoard.close() }
                    retained.sync()
                }
            }
            // Optional browser probe consumes these actual projections, not a second UI vocabulary.
            System.getenv("LCNC_UI_FIXTURE")?.let { destination ->
                val publisher = LcncPublisher(ctx.blackboard, { ctx.lcncRunners }, null)
                val workspace = borg.trikeshed.kanban.module.WorkspaceService(ctx)
                java.io.File(destination).writeText(jsonOf(mapOf(
                    "contracts" to publisher.vocabularyPayload(), "outputs" to outputs, "sheets" to sheets,
                    "presets" to LcncWorkspacePresets.catalog.map { info -> mapOf(
                        "name" to info.name, "title" to info.title, "does" to info.does, "needs" to info.needs,
                        "see" to info.see, "tweakFirst" to info.tweakFirst,
                        "document" to reify(LcncWorkspacePresets.documents().getValue(info.name))) },
                    "workspace" to workspace.catalog(),
                    "inspections" to LcncWorkspacePresets.documents().mapValues { (name, doc) ->
                        workspace.inspect(mapOf("name" to name, "document" to reify(doc)))
                    },
                )))
            }
        } finally {
            try { board.drain(); board.close() } finally {
                try { curator.drain() } finally { try { bag.drain() } finally { nlp.close(); session.close() } }
            }
        }
    }
}
