package borg.trikeshed.lcnc

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.BoardWalPort
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Synthetic retained receipts exercise the actual board; no NLP or model is invoked by these nodes. */
class DocumentCurationTasksTest {
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
    ): ContentId = cas.put(DocumentCuratorCodec.encode(DocumentCurationRecord(source,
        NlpDocument(source.text, s_[NlpSentence(0, 0, source.text.length,
            s_[NlpToken(1, 0, source.text.length, source.text, source.text, "NN", "O")],
            s_[NlpDependency(0, 1, "root")])]),
        if (model) ModelResponse("synthetic", ModelUsage(0, 0, 0), "fixture", "fixture") else null,
        "fixture", proposals, reasons, instructions = "Retain exact source quotations")))

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
