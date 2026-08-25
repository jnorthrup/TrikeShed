package borg.trikeshed.kanban

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobCommand
import borg.trikeshed.job.JobId
import borg.trikeshed.job.JobSnapshot
import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.BeliefIntake
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.narsese.SemanticSignal
import borg.trikeshed.narsese.TurnReviewElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardNarsGarnishTest {

    private fun committed(
        jobId: String,
        cmd: JobCommand,
        col: BoardCol,
        seq: Long = 1L,
    ) = BoardCommitted(
        sequence = seq,
        jobId = jobId,
        snapshot = JobSnapshot(JobId(jobId), 1L, "", "active"),
        cid = ContentId.of("$jobId-$seq".encodeToByteArray()),
        command = cmd,
        col = col,
        previousCol = null,
        lastMoveMs = 0L,
    )

    private fun row(jobId: String, col: BoardCol, title: String = "Card $jobId", tags: List<String> = emptyList()) =
        CardRow(jobId, title, col, 1L, 1L, 0L, 2, 0, emptyList(), tags)

    @Test
    fun reviewWindowMintsBounded_andCounterMoveGoesNegative(): Unit = runBlocking {
        val bag = BeliefBagElement(capacity = 256)
        bag.open()
        val review = TurnReviewElement(bag)
        review.open()
        val cards = mapOf("a" to row("a", BoardCol.READY, tags = listOf("deploy")))
        var now = 0L
        val bridge = BoardReviewBridge(review, cardLookup = { cards[it] }, clock = { now })

        // rule moves the card to READY…
        bridge.onCommitted(committed("a", JobCommand.Move(JobId("a"), "a#dependency-ready#1", 1L, borg.trikeshed.job.KanbanColumnId("ready")), BoardCol.READY))
        // …and the human takes it back within the window: negative evidence on the rule
        now = 60_000L
        bridge.onCommitted(committed("a", JobCommand.Move(JobId("a"), "human-k2", 2L, borg.trikeshed.job.KanbanColumnId("todo")), BoardCol.TODO, seq = 2))
        for (i in 1..30) bridge.onCommitted(committed("j$i", JobCommand.Complete(JobId("j$i"), "c$i", 1L), BoardCol.DONE, seq = 10L + i))

        val landed = bridge.flush()
        assertTrue(landed.isNotEmpty(), "the window must mint")
        assertTrue(landed.size <= 16, "mints bounded by the review intake cap, got ${landed.size}")
        assertTrue(landed.any { it.second.contains("(failed)") }, "counter-move must land negative evidence: ${landed.map { it.second }}")
        assertEquals(0, bridge.pendingCount, "flush drains the window")
        review.drain()
        bag.drain()
    }

    @Test
    fun attentionGarnishRanksAndFlagsContested(): Unit = runBlocking {
        val bag = BeliefBagElement(capacity = 256)
        bag.open()
        val supported = row("s", BoardCol.TODO, tags = listOf("deploy"))
        val contested = row("c", BoardCol.TODO, tags = listOf("risky"))
        val cold = row("x", BoardCol.TODO, tags = listOf("nothing"))

        suspend fun mint(angular: Long, positive: Boolean, pri: Float) {
            bag.intake.send(
                BeliefIntake.Mint(
                    SemanticSignal(
                        angular = angular,
                        evidence = borg.trikeshed.narsese.Nal.observe(positive),
                        relation = RelationKind.CAUSALITY,
                        subjectCid = ContentId.of("s".encodeToByteArray()).value,
                        objectCid = ContentId.of("o".encodeToByteArray()).value,
                        provenanceCid = ContentId.of("p-$angular-$positive".encodeToByteArray()).value,
                    ),
                    BudgetCoord(pri, 0.5f, 0.5f),
                ),
            )
        }
        mint(BoardAttentionOrder.angularOf(supported), positive = true, pri = 0.9f)
        mint(BoardAttentionOrder.angularOf(contested), positive = true, pri = 0.6f)
        mint(BoardAttentionOrder.angularOf(contested) xor 1L, positive = false, pri = 0.6f) // near-coordinate refutation
        var waited = 0
        while (bag.size < 3 && waited++ < 100) delay(10)
        assertEquals(3, bag.size)

        val garnish = BoardAttentionOrder.garnish(bag, listOf(supported, contested, cold))
        assertTrue(garnish.getValue("s").score > 0f, "supported card scores positive")
        assertTrue(garnish.getValue("c").contested, "support+refutation fronts both lit = contested")
        assertTrue(!garnish.getValue("s").contested, "no refutation near the supported card")
        assertTrue(garnish.getValue("s").score > garnish.getValue("x").score, "backlog ranked by bag expectation")

        // drift is finite and non-negative over the kanban cohort
        val t2 = BoardAttentionOrder.driftT2(bag)
        assertTrue(t2 >= 0f && t2.isFinite(), "drift T² must be a usable statistic, got $t2")
        bag.drain()
    }
}
