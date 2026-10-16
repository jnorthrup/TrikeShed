package borg.trikeshed.lcnc

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.BoardWalPort
import borg.trikeshed.kanban.JvmBoardWal
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic retained receipts exercise the actual board; no NLP or model is invoked by these nodes. */
class DocumentCurationTasksTest {
    private class Cas : CasStore() {
        var puts = 0
        var missing: ContentId? = null
        override fun put(bytes: ByteArray): ContentId {
            puts++
            return super.put(bytes)
        }
        override fun get(cid: ContentId): ByteArray? = if (cid == missing) null else super.get(cid)
    }

    private class Wal : BoardWalPort {
        val frames = mutableListOf<ByteArray>()
        var flushed = 0
        override fun append(record: ByteArray): Long { frames.add(record.copyOf()); return frames.size.toLong() }
        override fun flush() { flushed = frames.size }
        override suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit) {
            repeat(flushed) { onRecord(it + 1L, frames[it].copyOf()) }
        }
    }

    private fun source(cas: CasStore, text: String = "Alex uses Java.", original: ContentId? = null): DocumentSource {
        val textCid = cas.put(text.encodeToByteArray())
        return DocumentSource(original ?: textCid, textCid, text, "synthetic.txt", "text/plain", "synthetic")
    }

    private fun proposal(quote: String = "Alex uses Kotlin.", reason: String? = "quote does not match UTF-16 span"): DocumentProposal =
        DocumentProposal("synthetic", "Alex", "use", "Kotlin", 0.9, quote, 0, quote.length, true, "asserted",
            reason?.let { s_[it] } ?: emptySeriesOf())

    private fun retained(
        cas: CasStore, source: DocumentSource, proposals: Series<DocumentProposal> = s_[proposal()],
        model: Boolean = true, reasons: Series<String> = emptySeriesOf(),
        instructions: String = "Retain exact source quotations",
    ): ContentId = cas.put(DocumentCuratorCodec.encode(DocumentCurationRecord(source,
        NlpDocument(source.text, s_[NlpSentence(0, 0, source.text.length,
            s_[NlpToken(1, 0, source.text.length, source.text, source.text, "NN", "O")],
            s_[NlpDependency(0, 1, "root")])]),
        if (model) ModelResponse("synthetic", ModelUsage(0, 0, 0), "fixture", "fixture") else null,
        "fixture", proposals, reasons, instructions = instructions)))

    private fun sourceReference(source: DocumentSource): Map<String, Any?> = mapOf(
        "originalCid" to source.originalCid.value, "extractedTextCid" to source.extractedTextCid.value,
        "name" to source.name, "mediaType" to source.mediaType, "correlation" to source.correlation,
        "metadata" to source.metadata,
    )

    private fun returnedSource(cas: CasStore, output: Map<String, Any?>): DocumentSource {
        val fields = output.getValue("source") as Map<*, *>
        val textCid = ContentId(fields["extractedTextCid"] as String)
        @Suppress("UNCHECKED_CAST")
        return DocumentSource(ContentId(fields["originalCid"] as String), textCid,
            checkNotNull(cas.get(textCid)).decodeToString(), fields["name"] as String,
            fields["mediaType"] as String, fields["correlation"] as String,
            fields["metadata"] as Map<String, List<String>>)
    }

    @Test
    fun inspectionAfterWalRestartRetainsSourceReceiptAndRecipeWithoutTaskReplay() = runBlocking {
        withTimeout(10_000) {
            val cas = Cas()
            val dir = Files.createTempDirectory("document-task-inspection").toFile()
            var board = BoardStoreElement(JvmBoardWal(dir), cas)
            board.open()
            try {
                val source = source(cas, original = cas.put("Original attachment bytes".encodeToByteArray()))
                    .copy(metadata = mapOf("pages" to listOf("1", "2")))
                val instructions = "Retain the author's exact wording.\nPreserve uncertainty and attribution."
                val receipt = retained(cas, source, instructions = instructions)
                val tasks = DocumentCurationTasks(cas)
                tasks.tasks(board, receipt)
                val card = board.cards().single()
                val evidence = listOf(source.originalCid, source.extractedTextCid, receipt)
                    .associateWith { assertNotNull(cas.get(it)) }
                board.drain(); board.close()
                board = BoardStoreElement(JvmBoardWal(dir), cas)
                board.open()

                val sequence = board.lastSequence
                val puts = cas.puts
                val inspector = DocumentCurationTasks(cas)
                val inspected = assertNotNull(inspector.inspect(board, card.jobId))
                assertEquals(card.jobId, inspected["jobId"])
                assertEquals(card.revision, (inspected["revision"] as Number).toLong())
                assertEquals(card.col.wire, inspected["column"])
                assertEquals(card.title, inspected["title"])
                assertEquals(receipt.value, inspected["receiptCid"])
                assertEquals(sourceReference(source), inspected["source"])
                assertEquals(instructions, inspected["instructions"])
                assertEquals(false, inspected["resolved"])
                assertEquals(emptyMap<String, Any?>(), inspected["correctionSource"])
                val issue = assertIs<Map<*, *>>(inspected["issue"])
                assertEquals("quotation", issue["kind"])
                assertEquals(listOf("Alex uses Kotlin."), issue["quotes"])
                assertEquals(listOf(mapOf("subject" to "Alex", "predicate" to "use", "object" to "Kotlin")), issue["terms"])
                assertEquals(listOf("quote does not match UTF-16 span"), issue["reasons"])
                assertEquals(inspected, inspector.inspect(board, card.jobId))
                assertTrue("record" !in inspected)
                val receiptView = assertNotNull(inspector.inspect(board, card.jobId, includeReceipt = true))
                assertEquals(inspected, receiptView - "record")
                val receiptRecord = assertIs<Map<*, *>>(receiptView["record"])
                assertEquals(instructions, receiptRecord["instructions"])
                assertEquals("fixture", receiptRecord["modelId"])
                assertEquals(sourceReference(source) + ("text" to source.text), receiptRecord["source"])
                assertNull(inspector.inspect(board, "absent/document-task"))
                assertEquals(sequence, board.lastSequence)
                assertEquals(card, board.card(card.jobId))
                assertEquals(puts, cas.puts, "inspection must not write even an already-retained CAS object")
                for ((cid, bytes) in evidence) assertContentEquals(bytes, cas.get(cid))
                val record = DocumentCuratorCodec.decode(assertNotNull(cas.get(receipt)))
                assertEquals(source, record.source)
                assertEquals(instructions, record.instructions)
                assertEquals("fixture", record.modelId)
            } finally { board.drain(); board.close() }
        }
    }

    @Test
    fun taskReasonsContainOnlyTheMatchingRetainedStrings() = runBlocking {
        val cas = Cas()
        val board = BoardStoreElement(Wal(), cas)
        board.open()
        try {
            val receipt = retained(cas, source(cas), reasons = s_[
                "model: transport unavailable", "quote does not match UTF-16 span", "admission disabled",
            ])
            val tasks = DocumentCurationTasks(cas)
            tasks.tasks(board, receipt)
            val issues = board.cards().map { card ->
                assertIs<Map<*, *>>(assertNotNull(tasks.inspect(board, card.jobId))["issue"])
            }.associate { issue -> assertIs<String>(issue["kind"]) to
                assertIs<List<*>>(issue["reasons"]).map { assertIs<String>(it) } }
            assertEquals(mapOf(
                "quotation" to listOf("quote does not match UTF-16 span"),
                "model" to listOf("model: transport unavailable"),
            ), issues)
        } finally { board.drain(); board.close() }
    }

    @Test
    fun inspectionResumesPendingCorrectionAfterRestartAndReportsResolution() = runBlocking {
        withTimeout(10_000) {
            val cas = Cas()
            val wal = Wal()
            var board = BoardStoreElement(wal, cas)
            board.open()
            try {
                val source = source(cas)
                val receipt = retained(cas, source)
                val tasks = DocumentCurationTasks(cas)
                tasks.tasks(board, receipt)
                val id = board.cards().single().jobId
                val correction = tasks.correct(board, id, board.card(id)!!.revision, receipt,
                    mapOf("text" to "Alex uses Kotlin."))
                val corrected = returnedSource(cas, correction).copy(metadata = source.metadata)
                assertNotEquals(source.extractedTextCid, corrected.extractedTextCid)
                val receiptBytes = assertNotNull(cas.get(receipt))
                board.drain(); board.close()
                board = BoardStoreElement(wal, cas)
                board.open()

                val inspector = DocumentCurationTasks(cas)
                val sequence = board.lastSequence
                val puts = cas.puts
                val frames = wal.frames.size
                val inspected = assertNotNull(inspector.inspect(board, id))
                assertEquals(sourceReference(source), inspected["source"], "the receipt still describes the prior source")
                assertEquals(receipt.value, inspected["receiptCid"])
                assertEquals(correction["revision"], inspected["revision"])
                assertEquals(sourceReference(corrected), inspected["correctionSource"])
                assertEquals(false, inspected["resolved"])
                assertEquals(BoardCol.TODO, board.card(id)?.col)
                assertEquals(sequence, board.lastSequence)
                assertEquals(frames, wal.frames.size)
                assertEquals(puts, cas.puts)

                @Suppress("UNCHECKED_CAST")
                val pending = inspected["correctionSource"] as Map<String, Any?>
                val resumed = inspector.correct(board, id, (inspected["revision"] as Number).toLong(), receipt, pending)
                val resumedSource = returnedSource(cas, resumed)
                assertEquals("Alex uses Kotlin.", resumedSource.text)
                assertEquals(source.originalCid, resumedSource.originalCid)
                assertEquals(corrected.extractedTextCid, resumedSource.extractedTextCid)
                assertEquals(listOf(id), resumedSource.metadata["taskId"])
                assertEquals(listOf(resumed["revision"].toString()), resumedSource.metadata["taskRevision"])
                assertEquals(listOf(receipt.value), resumedSource.metadata["priorReceiptCid"])
                assertEquals(inspected["instructions"], resumed["instructions"])
                val result = retained(cas, resumedSource, s_[proposal(reason = null)])
                inspector.tasks(board, result)
                val done = assertNotNull(inspector.inspect(board, id))
                assertEquals("done", done["column"])
                assertEquals(true, done["resolved"])
                assertEquals(result.value, done["receiptCid"])
                assertEquals(sourceReference(corrected), done["source"])
                assertEquals(emptyMap<String, Any?>(), done["correctionSource"])
                assertContentEquals(receiptBytes, cas.get(receipt))
                assertEquals("Alex uses Java.", assertNotNull(cas.get(source.extractedTextCid)).decodeToString())
            } finally { board.drain(); board.close() }
        }
    }

    @Test
    fun inspectedRevisionCannotCorrectAfterAnInterveningBoardMove() = runBlocking {
        withTimeout(10_000) {
            val cas = Cas()
            val wal = Wal()
            val board = BoardStoreElement(wal, cas)
            board.open()
            try {
                val tasks = DocumentCurationTasks(cas)
                val receipt = retained(cas, source(cas))
                tasks.tasks(board, receipt)
                val id = board.cards().single().jobId
                val inspected = assertNotNull(tasks.inspect(board, id))
                val reply = CompletableDeferred<BoardApply>()
                board.intake.send(BoardIntake(mapOf("type" to "move", "jobId" to id, "toColumn" to "blocked",
                    "expectedRevision" to inspected["revision"], "idempotencyKey" to "inspection-intervening-move"), reply))
                assertIs<BoardApply.Committed>(reply.await())
                val card = board.card(id)
                val sequence = board.lastSequence
                val frames = wal.frames.size
                val puts = cas.puts
                assertFailsWith<IllegalArgumentException> {
                    tasks.correct(board, id, (inspected["revision"] as Number).toLong(), receipt, emptyMap())
                }
                assertEquals(card, board.card(id))
                assertEquals(sequence, board.lastSequence)
                assertEquals(frames, wal.frames.size)
                assertEquals(puts, cas.puts)
                val current = assertNotNull(tasks.inspect(board, id))
                assertEquals("blocked", current["column"])
                assertEquals(card!!.revision, (current["revision"] as Number).toLong())
                assertEquals(emptyMap<String, Any?>(), current["correctionSource"])
            } finally { board.drain(); board.close() }
        }
    }

    @Test
    fun inspectionRefusesMissingOrCorruptRetainedContentWithoutMutation() = runBlocking {
        withTimeout(10_000) {
            for (target in listOf("receipt", "original", "text", "correction")) for (corrupt in listOf(false, true)) {
                val cas = Cas()
                val wal = Wal()
                val board = BoardStoreElement(wal, cas)
                board.open()
                try {
                    val source = source(cas, original = cas.put("Original attachment bytes".encodeToByteArray()))
                    val receipt = retained(cas, source)
                    val tasks = DocumentCurationTasks(cas)
                    tasks.tasks(board, receipt)
                    val id = board.cards().single().jobId
                    val cid = when (target) {
                        "receipt" -> receipt
                        "original" -> source.originalCid
                        "text" -> source.extractedTextCid
                        else -> returnedSource(cas, tasks.correct(board, id, board.card(id)!!.revision, receipt,
                            mapOf("text" to "Alex uses Kotlin."))).extractedTextCid
                    }
                    val card = board.card(id)
                    val sequence = board.lastSequence
                    val frames = wal.frames.size
                    val puts = cas.puts
                    if (corrupt) cas.corrupt(cid) else cas.missing = cid
                    assertFails("$target (corrupt=$corrupt) must be refused") { tasks.inspect(board, id) }
                    assertEquals(card, board.card(id))
                    assertEquals(sequence, board.lastSequence)
                    assertEquals(frames, wal.frames.size)
                    assertEquals(puts, cas.puts)
                } finally { board.drain(); board.close() }
            }
        }
    }

    @Test
    fun sourceCorrectionCompletesAndRecurrenceReopensTheSameDurableTask() = runBlocking {
        withTimeout(10_000) {
            val cas = CasStore.inMemory()
            val wal = Wal()
            var board = BoardStoreElement(wal, cas)
            board.open()
            try {
                var tasks = DocumentCurationTasks(cas)
                val source = source(cas)
                val first = retained(cas, source, s_[proposal(), proposal().copy(predicate = "know")])
                tasks.tasks(board, first)
                assertEquals(1, board.cards().size, "one bad quotation is one issue, even with several proposals")
                val id = board.cards().single().jobId
                assertEquals(BoardCol.TODO, board.card(id)?.col)
                val watermark = board.lastSequence
                tasks.tasks(board, first)
                assertEquals(watermark, board.lastSequence)

                // Replay reconstructs the existing spec's CAS pointer; the adapter keeps no second task table.
                board.drain(); board.close()
                board = BoardStoreElement(wal, cas)
                board.open()
                tasks = DocumentCurationTasks(cas)
                tasks.tasks(board, first)
                assertEquals(watermark, board.lastSequence)
                val correction = tasks.correct(board, id, board.card(id)!!.revision, first,
                    mapOf("text" to "Alex uses Kotlin."))
                assertEquals(source.originalCid.value, (correction["source"] as Map<*, *>)["originalCid"])
                assertTrue("text" !in (correction["source"] as Map<*, *>), "the canvas carries a reference, not a copied document")
                assertEquals(BoardCol.TODO, board.card(id)?.col, "retaining a correction does not perform curation")
                val corrected = returnedSource(cas, correction)
                val result = retained(cas, corrected, s_[proposal(reason = null), proposal(reason = null).copy(predicate = "know")])
                val projected = tasks.tasks(board, result)
                assertEquals(1, (projected["completed"] as List<*>).size)
                assertEquals(BoardCol.DONE, board.card(id)?.col)
                val doneSequence = board.lastSequence
                tasks.tasks(board, result)
                assertEquals(doneSequence, board.lastSequence)

                val recurrence = tasks.correct(board, id, board.card(id)!!.revision, result, mapOf("text" to "Alex uses Scala."))
                val recurred = retained(cas, returnedSource(cas, recurrence), s_[proposal(), proposal().copy(predicate = "know")])
                assertEquals(1, (tasks.tasks(board, recurred)["reopened"] as List<*>).size)
                assertEquals(BoardCol.TODO, board.card(id)?.col)
                assertEquals(1, board.cards().size)
            } finally { board.drain(); board.close() }
        }
    }

    @Test
    fun delayedOrEmptyResultCannotCloseAnIssueAndBoardMovesRetainItsLineage() = runBlocking {
        withTimeout(10_000) {
            val cas = CasStore.inMemory()
            val board = BoardStoreElement(Wal(), cas)
            board.open()
            try {
                val tasks = DocumentCurationTasks(cas)
                val first = retained(cas, source(cas))
                tasks.tasks(board, first)
                val id = board.cards().single().jobId
                val correction1 = tasks.correct(board, id, board.card(id)!!.revision, first, mapOf("text" to "Alex uses Kotlin."))
                val oldResult = retained(cas, returnedSource(cas, correction1), s_[proposal(reason = null)])
                val reply = CompletableDeferred<BoardApply>()
                board.intake.send(BoardIntake(mapOf("type" to "move", "jobId" to id, "toColumn" to "blocked",
                    "expectedRevision" to board.card(id)!!.revision, "idempotencyKey" to "human-change"), reply))
                assertIs<BoardApply.Committed>(reply.await())
                val sequence = board.lastSequence
                assertFailsWith<IllegalArgumentException> { tasks.tasks(board, oldResult) }
                assertEquals(sequence, board.lastSequence)
                assertEquals(BoardCol.BLOCKED, board.card(id)?.col)

                val correction2 = tasks.correct(board, id, board.card(id)!!.revision, first, mapOf("text" to "Alex uses Kotlin."))
                val emptyResult = retained(cas, returnedSource(cas, correction2), emptySeriesOf(), model = false, reasons = s_["model: unavailable"])
                tasks.tasks(board, emptyResult)
                assertEquals(BoardCol.BLOCKED, board.card(id)?.col)
                assertTrue(board.cards().none { it.col == BoardCol.DONE })
            } finally { board.drain(); board.close() }
        }
    }

    @Test
    fun admissionPolicyExclusionsDoNotAskTheAuthorToRewriteValidLanguage() = runBlocking {
        withTimeout(10_000) {
            val cas = CasStore.inMemory()
            val board = BoardStoreElement(Wal(), cas)
            board.open()
            try {
                val receipt = retained(cas, source(cas), s_[
                    proposal(reason = "unsupported polarity or modality").copy(polarity = false),
                    proposal(reason = "unsupported clause structure"),
                    proposal(reason = "quote is not one complete NLP sentence"),
                ])
                val output = DocumentCurationTasks(cas).tasks(board, receipt)
                assertEquals(0, board.cards().size)
                assertEquals(3, (output["ignored"] as List<*>).size)
            } finally { board.drain(); board.close() }
        }
    }
}
