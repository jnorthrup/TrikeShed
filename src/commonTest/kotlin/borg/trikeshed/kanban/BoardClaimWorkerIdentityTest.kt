package borg.trikeshed.kanban

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.test.*

class BoardClaimWorkerIdentityTest {
    private suspend fun commit(store: BoardStoreElement, raw: Map<String, Any?>): BoardApply.Committed {
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(BoardIntake(raw, reply))
        return assertIs<BoardApply.Committed>(reply.await())
    }

    private suspend fun claim(mismatch: String?): Join<Map<String, Any?>, CardRow> {
        val board = ConfixBlackboard.empty()
        val store = BoardStoreElement(null, CasStore.inMemory(), parentJob = currentCoroutineContext().job)
        store.open()
        try {
            commit(store, mapOf("type" to "submit", "jobId" to "task", "idempotencyKey" to "submit",
                "title" to "Inspect output.txt", "spec" to "GOAL: inspect output.txt\nMUST: output.txt is present\nAGENT: stub"))
            commit(store, mapOf("type" to "move", "jobId" to "task", "idempotencyKey" to "ready",
                "expectedRevision" to 1, "toColumn" to "ready"))
            val lane = InMemoryAgentRuns(listOf(AgentCliInfo("stub", "fixture", "1", true))) { request ->
                AgentRunResult(runId = if (mismatch == "runId") "unrelated-run" else request.runId,
                    jobId = if (mismatch == "jobId") "unrelated-task" else request.jobId,
                    agent = if (mismatch == "agent") "unrelated-agent" else request.agent,
                    exit = 0, patchCid = ContentId.of("patch".encodeToByteArray()).value,
                    transcriptCid = ContentId.of("transcript".encodeToByteArray()).value,
                    patchBytes = 10, filesChanged = 1, files = listOf("output.txt"),
                    summary = "VERDICT: MET\nMUST-1: MET evidence: blackboard/agent/run/${request.runId}\nACTION: done")
            }
            val worker = BoardClaimWorker(store, board, { null }, { 1L }, agents = { lane }, mintRunId = { "agent-run" })
            // The store consumes on Default; its real commit must not race a virtual timeout.
            val receipt = withContext(Dispatchers.Default) { worker.claim("task", 2L, "claim:agent:stub") }
            assertEquals(1, lane.requests.size)
            assertEquals("agent-run", lane.requests.single().runId)
            assertEquals("task", lane.requests.single().jobId)
            return receipt j assertNotNull(store.card("task"))
        } finally { store.drain() }
    }

    @Test fun mismatchedAgentResultsCannotBecomeAnotherCardsEvidence() = runTest {
        for (field in s_["runId", "jobId", "agent"].view) {
            val (receipt, card) = claim(field)
            assertEquals(false, receipt["ok"], field)
            assertEquals("agent claim: result identity does not match the dispatched request", receipt["error"])
            assertEquals("RETRY", receipt["decision"])
            for (name in s_["agent", "runId", "patchCid", "transcriptCid", "patchBytes"].view)
                assertFalse(receipt.containsKey(name), "$field leaked $name")
            assertEquals(BoardCol.READY, card.col)
            assertEquals(1, card.strikes)
        }
    }

    @Test fun anExactlyCorrelatedResultRetainsTheExistingJudgedEvidence() = runTest {
        val (receipt, card) = claim(null)
        assertEquals(true, receipt["ok"])
        assertEquals("DONE", receipt["decision"])
        assertEquals("agent-run", receipt["runId"])
        assertEquals("stub", receipt["agent"])
        assertEquals(ContentId.of("patch".encodeToByteArray()).value, receipt["patchCid"])
        assertEquals(BoardCol.DONE, card.col)
    }
}
