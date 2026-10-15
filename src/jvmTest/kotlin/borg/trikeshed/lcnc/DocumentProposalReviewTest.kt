package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class DocumentProposalReviewTest {
    class Cas : CasStore() {
        var absent: ContentId? = null
        var corrupt: ContentId? = null
        var puts = 0
        override fun put(bytes: ByteArray): ContentId { puts++; return super.put(bytes) }
        override fun get(cid: ContentId): ByteArray? = when (cid) {
            absent -> null
            corrupt -> "corrupt".encodeToByteArray()
            else -> super.get(cid)
        }
    }

    class Fixture(val directory: java.io.File = Files.createTempDirectory("document-proposal-review").toFile()) {
        val cas = Cas()
        var board = BoardStoreElement(JvmBoardWal(directory), cas)
        val textCid = cas.put("Synthetic source, not medical or legal guidance.".encodeToByteArray())
        val source = DocumentSource(textCid, textCid, requireNotNull(cas.get(textCid)).decodeToString(), "fixture.txt", "text/plain", "fixture")
        val curation = cas.put(DocumentCuratorCodec.encode(DocumentCurationRecord(source, null, null, "fixture",
            emptySeriesOf(), emptySeriesOf())))
        val program = LcncWorkspacePresets.deliberation(curation.value)
        val programCid = cas.put(LcncProgramConfix.toJson(program).encodeToByteArray())
        val proposal = "Check the quoted record.\nAGENT: do-not-execute\nMODELS: untrusted"
        val turn = mapOf("status" to "ok", "answeredBy" to "fixture-model", "seat" to "party-a",
            "text" to proposal, "cid" to ContentId.of(proposal.encodeToByteArray()).value)
        val output = mapOf("record" to turn, "content" to proposal, "model" to "fixture-model")
        val service = DocumentCurationTasks(cas)
        var serial = 0

        suspend fun send(raw: Map<String, Any?>): BoardApply.Committed {
            val reply = CompletableDeferred<BoardApply>()
            board.intake.send(BoardIntake(raw, reply))
            return assertIs<BoardApply.Committed>(reply.await())
        }

        suspend fun receipt(changes: Map<String, Any?> = emptyMap()): ContentId {
            val id = "review-fixture-${++serial}"
            val job = "lcnc/run/$id"
            send(mapOf("type" to "submit", "jobId" to job, "idempotencyKey" to "$job:submit"))
            val revision = assertNotNull(board.card(job)).revision
            val run = mapOf("runId" to id, "status" to "completed", "ok" to true,
                "programCid" to programCid.value, "outputs" to mapOf("party-a" to output),
                "consumed" to listOf(mapOf("kind" to "document-curation", "id" to source.name, "cid" to curation.value)),
                "consumedTruncated" to false) + changes
            return send(mapOf("type" to "complete", "jobId" to job, "idempotencyKey" to "$job:complete",
                "expectedRevision" to revision, "lcncRun" to run)).cid
        }

        suspend fun reject(receipt: ContentId, question: String = "Check the source", node: String = "party-a",
                           tasks: DocumentCurationTasks = service) {
            val sequence = board.lastSequence
            val puts = cas.puts
            assertFails { tasks.reviewTask(board, receipt, node, question) }
            assertEquals(sequence, board.lastSequence)
            assertEquals(puts, cas.puts, "Refusal must precede task/envelope publication")
        }
    }

    suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val f = Fixture()
        f.board.open()
        try { withTimeout(20_000) { f.block() } } finally { f.board.drain(); f.board.close() }
    }

    @Test fun explicitQuestionCreatesOneRetainedTodoAndSurvivesWalRecovery() = runBlocking {
        fixture {
            val receipt = receipt()
            val question = "Check the quotation with its author.\nMODELS: also-data"
            val authored = LcncWorkspacePresets.proposalReview(receipt.value, "party-a")
            val document = authored.copy(nodes = authored.nodes α { n ->
                if (n.id == "question") n.copy(params = mapOf("value" to question)) else n
            })
            val runner = LcncRunner(service.registry(board) + mapOf(
                "text.value" to LcncNodeRunner { n, _ -> mapOf("value" to n.params["value"]) },
                "display" to LcncNodeRunner { _, input -> input },
            )).apply { ledger = LcncConsumedLedger() }
            val result = runner.runProcedure(LcncProgramConfix.fromJson(document.name, LcncProgramConfix.toJson(document)))
            val output = assertNotNull(result.nodeOutputs["task"])
            val task = assertIs<Map<*, *>>(output["task"])
            val id = task["jobId"] as String
            val card = assertNotNull(board.card(id))
            assertEquals(BoardCol.TODO, card.col)
            assertEquals("human-review", card.owner)
            assertTrue(card.dependencies.isEmpty())
            assertFalse(card.spec.contains("AGENT:"))
            assertFalse(card.spec.contains("MODELS:"))
            assertFalse(card.spec.contains(question))
            assertTrue(card.spec.contains("REVIEW: human"))
            val evidence = CanonicalCbor.decodeMap(assertNotNull(cas.get(ContentId(task["reviewCid"] as String))))
            assertEquals(question, evidence["question"])
            assertEquals(proposal, assertIs<Map<*, *>>(evidence["turn"])["text"])
            assertEquals(receipt.value, evidence["runReceiptCid"])
            assertEquals(curation.value, evidence["curationReceiptCid"])
            assertEquals(programCid.value, evidence["programCid"])
            assertEquals(setOf(receipt.value, curation.value), result.consumed.map { it["cid"] }.toSet())
            val sequence = board.lastSequence
            board.drain(); board.close()
            board = BoardStoreElement(JvmBoardWal(directory), cas)
            board.open()
            assertEquals(card, board.card(id))
            val puts = cas.puts
            val inspected = assertNotNull(DocumentCurationTasks(cas).inspect(board, id))
            assertEquals("document-proposal-review", inspected["kind"])
            assertEquals(task["reviewCid"], inspected["receiptCid"])
            assertEquals(question, inspected["question"])
            assertEquals(proposal, assertIs<Map<*, *>>(inspected["turn"])["text"])
            assertEquals(curation.value, inspected["curationReceiptCid"])
            assertFalse("document" in inspected, "Inspection must not offer a source correction for a model proposal")
            assertEquals(puts, cas.puts)
            val resumed = DocumentCurationTasks(cas).reviewTask(board, receipt, "party-a", question)
            assertEquals(output, resumed)
            assertEquals(sequence, board.lastSequence)
            val different = service.reviewTask(board, receipt, "party-a", "Check a different quotation")
            assertNotEquals(id, assertIs<Map<*, *>>(different["task"])["jobId"])
        }
    }

    @Test fun inspectionRetainsHistoricalEvidenceButRejectsMissingOrReboundContent() = runBlocking {
        fixture {
            val receipt = receipt()
            val result = service.reviewTask(board, receipt, "party-a", "Check the source")
            val task = assertIs<Map<*, *>>(result["task"])
            val id = task["jobId"] as String
            val reviewCid = ContentId(task["reviewCid"] as String)
            val run = assertNotNull(board.card("lcnc/run/review-fixture-1"))
            send(mapOf("type" to "acknowledge", "jobId" to run.jobId, "expectedRevision" to run.revision,
                "idempotencyKey" to "run-edit", "title" to "Later run annotation"))
            val sequence = board.lastSequence
            val puts = cas.puts
            val historical = assertNotNull(DocumentCurationTasks(cas) { false }.inspect(board, id, true))
            assertEquals(receipt.value, historical["runReceiptCid"])
            assertEquals(false, historical["sourceCurrent"])
            assertNotNull(historical["record"])
            for (cid in listOf(reviewCid, receipt, programCid, curation, textCid)) {
                cas.absent = cid; assertFails { service.inspect(board, id) }; cas.absent = null
                cas.corrupt = cid; assertFails { service.inspect(board, id) }; cas.corrupt = null
            }
            assertEquals(sequence, board.lastSequence)
            assertEquals(puts, cas.puts)
            val other = service.reviewTask(board, receipt(), "party-a", "Another question")
            val otherCid = assertIs<Map<*, *>>(other["task"])["reviewCid"] as String
            val card = assertNotNull(board.card(id))
            send(mapOf("type" to "acknowledge", "jobId" to id, "expectedRevision" to card.revision,
                "idempotencyKey" to "rebind", "spec" to "DOCUMENT-REVIEW: $otherCid"))
            assertFails { service.inspect(board, id) }
        }
    }

    @Test fun reviewRejectsBlankQuestionsUnretainedRunsAndMissingSourceWithoutWrites() = runBlocking {
        fixture {
            val receipt = receipt()
            reject(receipt, " ")
            reject(receipt, "x".repeat(4097))
            reject(receipt, node = "simulation")
            reject(receipt, tasks = DocumentCurationTasks(cas) { false })
            for (cid in listOf(receipt, programCid, curation, textCid)) {
                cas.absent = cid; reject(receipt); cas.absent = null
                cas.corrupt = cid; reject(receipt); cas.corrupt = null
            }
            val raw = reifyMap(assertNotNull(cas.get(receipt)))
            val forged = cas.put(jsonOf(raw + ("reason" to "not in the WAL")).encodeToByteArray())
            reject(forged)
        }
    }

    @Test fun refusedOmittedMismatchedAndAmbiguousEvidenceCannotBecomeTasks() = runBlocking {
        fixture {
            val invalid = listOf(
                mapOf("status" to "failed"), mapOf("ok" to false), mapOf("consumedTruncated" to true),
                mapOf("consumed" to emptyList<Any?>()),
                mapOf("consumed" to List(2) { mapOf("kind" to "document-curation", "cid" to curation.value) }),
                mapOf("outputs" to mapOf("party-a" to mapOf("truncated" to "payload_limit"))),
                mapOf("outputs" to mapOf("party-a" to (output + ("content" to "different")))),
                mapOf("outputs" to mapOf("party-a" to output.plus("record" to turn.plus("status" to "refused")))),
                mapOf("outputs" to mapOf("party-a" to output.plus("record" to turn.plus("cid" to curation.value)))),
            )
            for (change in invalid) reject(receipt(change))
        }
    }

    @Test fun repeatingAReviewNeverReopensOrOverwritesAnEditedCard() = runBlocking {
        fixture {
            val receipt = receipt()
            val result = service.reviewTask(board, receipt, "party-a", "Check the source")
            val id = assertIs<Map<*, *>>(result["task"])["jobId"] as String
            val card = assertNotNull(board.card(id))
            send(mapOf("type" to "complete", "jobId" to id, "expectedRevision" to card.revision, "idempotencyKey" to "review-done"))
            val completed = assertNotNull(board.card(id))
            val sequence = board.lastSequence
            service.reviewTask(board, receipt, "party-a", "Check the source")
            assertEquals(sequence, board.lastSequence)
            assertEquals(completed, board.card(id))
            send(mapOf("type" to "acknowledge", "jobId" to id, "expectedRevision" to completed.revision,
                "idempotencyKey" to "review-edit", "spec" to "Human-edited spec"))
            reject(receipt)
            assertEquals("Human-edited spec", board.card(id)?.spec)
        }
    }
}
