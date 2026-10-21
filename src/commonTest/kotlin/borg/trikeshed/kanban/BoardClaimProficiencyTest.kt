package borg.trikeshed.kanban

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import modelmux.*
import kotlin.test.*

class BoardClaimProficiencyTest {
    class ReplayWal : BoardWalPort {
        val records = mutableListOf<Join<Long, ByteArray>>()

        override fun append(record: ByteArray): Long =
            (records.size + 1L).also { records.add(it j record.copyOf()) }

        override fun flush() = Unit

        override suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit) {
            for ((sequence, record) in records) onRecord(sequence, record)
        }
    }

    val source = PlaneBrief.Row(
        "files", "report.txt", mapOf("path" to "report.txt", "content" to "The measured count is 2."),
        ContentId.of("report revision 1".encodeToByteArray()).value,
    )
    val response = "VERDICT: MET\nMUST-1: MET evidence: files/report.txt\nACTION: The count is 3."

    suspend fun commit(store: BoardStoreElement, raw: Map<String, Any?>): BoardApply.Committed {
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(BoardIntake(raw, reply))
        return assertIs<BoardApply.Committed>(reply.await())
    }

    suspend fun ready(store: BoardStoreElement, jobId: String): Long {
        val created = commit(store, mapOf(
            "type" to "submit", "jobId" to jobId, "idempotencyKey" to "$jobId/submit",
            "title" to "Summarize report.txt",
            "spec" to "TASK: summary\nGOAL: Summarize report.txt\nMUST: cite report.txt",
            "priority" to 1,
        ))
        return commit(store, mapOf(
            "type" to "move", "jobId" to jobId, "idempotencyKey" to "$jobId/ready",
            "expectedRevision" to created.revision, "toColumn" to BoardCol.READY.wire,
        )).revision
    }

    fun worker(
        store: BoardStoreElement,
        board: ConfixBlackboard,
        calls: MutableList<LcncNode>,
        beforeAnswer: suspend () -> Unit = {},
    ): BoardClaimWorker {
        val chat = LcncNodeRunner { node, _ ->
            calls.add(node)
            beforeAnswer()
            mapOf("model" to node.params["model"], "ok" to true, "content" to response)
        }
        val roster = LcncNodeRunner { _, _ ->
            mapOf("models" to listOf(
                mapOf("id" to "embedding", "modelVersion" to "v1", "caps" to listOf("embed")),
                mapOf("id" to "exhausted", "modelVersion" to "v1", "caps" to listOf("chat"), "quotaAvailable" to false),
                mapOf("id" to "alpha", "modelVersion" to "v1"),
                mapOf("id" to "beta", "modelVersion" to "v1"),
            ))
        }
        return BoardClaimWorker(
            store, board,
            runner = { when (it) { "prompt.chat" -> chat; "mux.models" -> roster; else -> null } },
            clock = { 7L },
            plane = { listOf(source) },
        )
    }

    @Test
    fun replayedHumanAssessmentChangesTheNextTaskSelectionAndRetainsItsSources() = runTest {
        val wal = ReplayWal()
        val cas = CasStore.inMemory()
        val calls = mutableListOf<LcncNode>()
        val original = BoardStoreElement(wal, cas, clock = { 7L }, parentJob = currentCoroutineContext().job)
        val refs: Series<BoardReceiptRef>
        val attemptId: String
        val admissionId: String
        val resultId: String
        val gradeId: String
        val prompt: String
        try {
            original.open()
            val revision = ready(original, "first")
            val result = withContext(Dispatchers.Default) {
                worker(original, ConfixBlackboard.empty(), calls).claim("first", revision)
            }
            assertEquals("alpha", result["model"])
            assertEquals(1.0, result["score"])
            assertEquals("UNKNOWN", result["correctness"])
            assertEquals(BoardCol.DONE, original.card("first")?.col)
            assertEquals(1, calls.size)
            prompt = assertNotNull(calls.single().params["prompt"])
            attemptId = assertIs<String>(result["attemptId"])
            admissionId = "$attemptId/admission"
            resultId = "$attemptId/result"
            gradeId = "$attemptId/human-1"
            val assessment = mapOf(
                "id" to gradeId, "kind" to "grade", "jobId" to "first", "attemptId" to attemptId,
                "model" to result["model"], "modelVersion" to result["modelVersion"],
                "harness" to result["harness"], "taskKind" to result["taskKind"],
                "basis" to ModelGradeBasis.CORRECTNESS, "score" to 0.0,
                "correctness" to ModelCorrectness.INACCURATE.name,
                "disposition" to ModelDisposition.EXPERIMENT_REJECTED.name,
                "actor" to "human:reviewer", "note" to "The retained source says 2; the answer says 3.",
                "evidenceIds" to listOf(admissionId, resultId),
            )
            assertIs<BoardApply.Committed>(original.recordReceipt("first", assessment))
            refs = original.receiptRefs()
            assertEquals(3, refs.size)
        } finally {
            original.drain()
        }

        val board = ConfixBlackboard.empty()
        val reopened = BoardStoreElement(wal, cas, clock = { 99L }, parentJob = currentCoroutineContext().job)
        try {
            reopened.open()
            assertNull(board.get(BoardClaimWorker.RECEIPT_PREFIX + "first"))
            assertEquals(refs.size, reopened.receiptRefs().size)
            for (ref in refs.view) {
                assertEquals(ref, reopened.receiptRefs().view.single { it.id == ref.id })
                assertNotNull(reopened.receipt(ref.id))
            }
            val admission = assertNotNull(reopened.receipt(admissionId))
            assertEquals(prompt, admission["brief"])
            assertEquals(attemptId, admission["attemptId"])
            assertEquals("summary", admission["taskKind"])
            val sources = assertNotNull(InvokeLowering.listishOf(admission["sources"]))
            val retainedSource = assertIs<Map<*, *>>(sources.single())
            assertEquals("files/report.txt", retainedSource["id"])
            assertEquals(source.contentId, retainedSource["cid"])
            assertEquals(source.fields, retainedSource["fields"])
            assertEquals(response, reopened.receipt(resultId)?.get("content"))
            val assessment = assertNotNull(reopened.receipt(gradeId))
            assertEquals("INACCURATE", assessment["correctness"])
            assertEquals("EXPERIMENT_REJECTED", assessment["disposition"])
            assertEquals(2, assertNotNull(InvokeLowering.listishOf(assessment["evidenceIds"])).size)

            val next = worker(reopened, board, calls)
            next.restoreReceipts()
            assertEquals(response, assertIs<Map<*, *>>(board.get(BoardClaimWorker.RECEIPT_PREFIX + "first"))["content"])
            val revision = ready(reopened, "second")
            val result = withContext(Dispatchers.Default) { next.claim("second", revision) }

            assertEquals(2, calls.size)
            assertEquals("beta", calls[1].params["model"])
            assertEquals("beta", result["model"])
            assertEquals("v1", result["modelVersion"])
            assertEquals(BoardCol.DONE, reopened.card("second")?.col)
            assertNotNull(reopened.receipt(admissionId))
            assertNotNull(reopened.receipt(resultId))
            assertNotNull(reopened.receipt(gradeId))
            assertEquals(5, reopened.receiptRefs().size)
        } finally {
            reopened.drain()
        }
    }

    @Test
    fun humanMoveDuringInferenceWinsWhileTheAnswerIsRetained() = runTest {
        val store = BoardStoreElement(ReplayWal(), CasStore.inMemory(), clock = { 7L }, parentJob = currentCoroutineContext().job)
        try {
            store.open()
            val revision = ready(store, "concurrent")
            val calls = mutableListOf<LcncNode>()
            var humanRevision = 0L
            val claimant = worker(store, ConfixBlackboard.empty(), calls) {
                val current = assertNotNull(store.card("concurrent"))
                assertEquals(BoardCol.RUNNING, current.col)
                humanRevision = commit(store, mapOf(
                    "type" to "move", "jobId" to "concurrent", "idempotencyKey" to "human/block",
                    "expectedRevision" to current.revision, "toColumn" to BoardCol.BLOCKED.wire,
                    "actor" to "human:reviewer",
                )).revision
            }
            val result = withContext(Dispatchers.Default) { claimant.claim("concurrent", revision) }
            val current = assertNotNull(store.card("concurrent"))

            assertEquals(1, calls.size)
            assertEquals(BoardCol.BLOCKED, current.col)
            assertEquals(humanRevision + 1, current.revision, "Only the retained result follows the human move")
            assertEquals(response, store.receipt("${result["attemptId"]}/result")?.get("content"))
            assertEquals(2, store.receiptRefs().size)
            assertEquals("card changed during claim; result retained",
                assertNotNull(InvokeLowering.listishOf(result["moves"])).single())
        } finally {
            store.drain()
        }
    }

    @Test
    fun interruptedAdmissionRecoversWithoutCallingTheModelOrInventingAGrade() = runTest {
        val wal = ReplayWal()
        val cas = CasStore.inMemory()
        val calls = mutableListOf<LcncNode>()
        val original = BoardStoreElement(wal, cas, clock = { 7L }, parentJob = currentCoroutineContext().job)
        val attemptId: String
        val admission: BoardReceiptRef
        try {
            original.open()
            val revision = ready(original, "interrupted")
            val claimant = worker(original, ConfixBlackboard.empty(), calls) {
                throw CancellationException("interrupted inference")
            }
            assertFailsWith<CancellationException> {
                withContext(Dispatchers.Default) { claimant.claim("interrupted", revision) }
            }
            assertEquals(BoardCol.RUNNING, original.card("interrupted")?.col)
            admission = original.receiptRefs().view.single()
            val retained = assertNotNull(original.receipt(admission.id))
            assertEquals("claim-admission", retained["kind"])
            attemptId = assertIs<String>(retained["attemptId"])
            assertNull(original.receipt("$attemptId/result"))
        } finally {
            original.drain()
        }

        val reopened = BoardStoreElement(wal, cas, clock = { 99L }, parentJob = currentCoroutineContext().job)
        try {
            reopened.open()
            val recovery = worker(reopened, ConfixBlackboard.empty(), calls)
            withContext(Dispatchers.Default) { recovery.recoverClaims() }
            val current = assertNotNull(reopened.card("interrupted"))
            val result = assertNotNull(reopened.receipt("$attemptId/result"))

            assertEquals(1, calls.size, "Recovery cannot redispatch the interrupted model call")
            assertEquals(BoardCol.READY, current.col)
            assertEquals(1, current.strikes)
            assertEquals(false, result["ok"])
            assertNull(result["score"])
            assertEquals("UNKNOWN", result["correctness"])
            assertEquals(admission, reopened.receiptRefs().view.single { it.id == admission.id })
            assertEquals(2, reopened.receiptRefs().size)

            withContext(Dispatchers.Default) { recovery.recoverClaims() }
            assertEquals(current, reopened.card("interrupted"))
            assertEquals(2, reopened.receiptRefs().size)
            assertEquals(1, calls.size)
        } finally {
            reopened.drain()
        }
    }

    @Test
    fun aSecondRestartCannotReclaimAnInterruptedHumanEdit() = runTest {
        val wal = ReplayWal()
        val cas = CasStore.inMemory()
        val calls = mutableListOf<LcncNode>()
        val original = BoardStoreElement(wal, cas, clock = { 7L }, parentJob = currentCoroutineContext().job)
        var humanRevision = 0L
        try {
            original.open()
            val revision = ready(original, "edited")
            val claimant = worker(original, ConfixBlackboard.empty(), calls) {
                val row = assertNotNull(original.card("edited"))
                humanRevision = commit(original, mapOf(
                    "type" to "update", "jobId" to "edited", "idempotencyKey" to "human/title",
                    "expectedRevision" to row.revision, "title" to "Human revised scope",
                )).revision
                throw CancellationException("interrupted after human edit")
            }
            assertFailsWith<CancellationException> {
                withContext(Dispatchers.Default) { claimant.claim("edited", revision) }
            }
        } finally {
            original.drain()
        }

        repeat(2) {
            val reopened = BoardStoreElement(wal, cas, clock = { 99L }, parentJob = currentCoroutineContext().job)
            try {
                reopened.open()
                withContext(Dispatchers.Default) { worker(reopened, ConfixBlackboard.empty(), calls).recoverClaims() }
                val row = assertNotNull(reopened.card("edited"))
                assertEquals(BoardCol.RUNNING, row.col)
                assertEquals("Human revised scope", row.title)
                assertEquals(0, row.strikes)
                assertEquals(humanRevision + 1, row.revision, "Only the interruption receipt may follow the human edit")
                assertEquals(2, reopened.receiptRefs().size)
                assertEquals(1, calls.size)
            } finally {
                reopened.drain()
            }
        }
    }
}
