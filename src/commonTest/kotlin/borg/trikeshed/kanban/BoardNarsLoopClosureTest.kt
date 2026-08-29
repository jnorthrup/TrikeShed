package borg.trikeshed.kanban

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.dag.Activation
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobCommand
import borg.trikeshed.job.JobId
import borg.trikeshed.job.JobSnapshot
import borg.trikeshed.job.KanbanColumnId
import borg.trikeshed.kanban.rules.BoardRules
import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.BeliefIntake
import borg.trikeshed.narsese.Nal
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.narsese.SemanticSignal
import borg.trikeshed.narsese.TurnReviewElement
import borg.trikeshed.runBlocking
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the board×NARS loop closures: the drift cohort IS what the bridge
 * mints (drift/garnish/bridge share one taxonomy word), flush's default
 * turnSucceeded dampens after a Fail, and the alert ring retains the
 * productions' tail per rule.
 */
class BoardNarsLoopClosureTest {

    private fun committed(jobId: String, cmd: JobCommand, col: BoardCol, seq: Long = 1L) = BoardCommitted(
        sequence = seq,
        jobId = jobId,
        snapshot = JobSnapshot(JobId(jobId), 1L, "", "active"),
        cid = ContentId.of("$jobId-$seq".encodeToByteArray()),
        command = cmd,
        col = col,
        previousCol = null,
        lastMoveMs = 0L,
    )

    private fun row(jobId: String, col: BoardCol, tags: List<String>) =
        CardRow(jobId, "Card $jobId", col, 1L, 1L, 0L, 2, 0, emptyList(), tags)

    @Test
    fun driftCohortIsExactlyWhatTheBridgeMints(): Unit = runBlocking {
        val bag = BeliefBagElement(capacity = 256)
        bag.open()
        val review = TurnReviewElement(bag)
        review.open()
        val card = row("a", BoardCol.TODO, tags = listOf("deploy"))
        val bridge = BoardReviewBridge(review, cardLookup = { if (it == "a") card else null })

        bridge.onCommitted(committed("a", JobCommand.Move(JobId("a"), "k1", 1L, KanbanColumnId("todo")), BoardCol.TODO))
        val minted = bridge.flush()
        assertTrue(minted.isNotEmpty(), "the window must mint")

        // 1. Everything the bridge mints sits inside driftT2's cohort selector.
        val observed = AngularCodec.taxonomySigOfKey("review")
        val induced = AngularCodec.taxonomySigOfKey("review/induced")
        for ((angular, gloss) in minted) {
            val sig = AngularCodec.Fields.taxonomySigOf(angular)
            assertTrue(sig == observed || sig == induced, "bridge minted outside the drift cohort: $gloss")
        }
        // 2. Garnish's centroid IS the bridge's observation angular for the same card.
        assertTrue(
            minted.any { it.first == BoardAttentionOrder.angularOf(card) },
            "garnish centroid must agree with the bridge's mint",
        )

        // 3. With out-of-cohort ballast in the field, the minted cohort registers in T².
        val ballastKey = listOf("pen", "ops", "graph", "cas", "wire").first {
            val s = AngularCodec.taxonomySigOfKey(it)
            s != observed && s != induced
        }
        for (i in 1..6) {
            bag.intake.send(
                BeliefIntake.Mint(
                    SemanticSignal(
                        angular = AngularCodec.encode(RelationKind.CAUSALITY, taxonomyKey = ballastKey, subjectTerm = "b$i", objectTerm = "x"),
                        evidence = Nal.observe(true),
                        relation = RelationKind.CAUSALITY,
                        subjectCid = ContentId.of("b$i".encodeToByteArray()).value,
                        objectCid = ContentId.of("x".encodeToByteArray()).value,
                        provenanceCid = ContentId.of("p$i".encodeToByteArray()).value,
                    ),
                    BudgetCoord(0.9f, 0.5f, 0.5f),
                ),
            )
        }
        val expected = minted.size + 6
        var waited = 0
        while (bag.size < expected && waited++ < 500) delay(10)
        assertEquals(expected, bag.size)

        val garnish = BoardAttentionOrder.garnish(bag, listOf(card))
        assertTrue(garnish.getValue("a").score > 0f, "the bridge's mint must support the garnish centroid")

        val t2 = BoardAttentionOrder.driftT2(bag)
        assertTrue(t2 > 0f && t2.isFinite(), "bridge mints must form a non-empty drift cohort, got $t2")
        review.drain()
        bag.drain()
    }

    @Test
    fun flushDefaultDampensAfterFail(): Unit = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val review = TurnReviewElement(bag)
        review.open()
        val bridge = BoardReviewBridge(review, cardLookup = { null })

        bridge.onCommitted(committed("f", JobCommand.Fail(JobId("f"), "kf", 1L, "boom"), BoardCol.BLOCKED))
        bridge.onCommitted(committed("c", JobCommand.Complete(JobId("c"), "kc", 1L), BoardCol.DONE, seq = 2))
        val dampened = bridge.flush()
        assertTrue(dampened.isNotEmpty())
        assertTrue(
            dampened.all { "(failed)" in it.second },
            "a Fail since the last flush must dampen the whole turn: ${dampened.map { it.second }}",
        )

        bridge.onCommitted(committed("c2", JobCommand.Complete(JobId("c2"), "kc2", 1L), BoardCol.DONE, seq = 3))
        val recovered = bridge.flush()
        assertTrue(
            recovered.any { "(worked)" in it.second },
            "failSinceFlush must reset at flush: ${recovered.map { it.second }}",
        )
        review.drain()
        bag.drain()
    }

    @Test
    fun alertRingRetainsTheLastNPerRule() {
        val ring = BoardRuleAlertRing(cap = 3)
        fun activation(ruleId: String, i: Int) = Activation(
            activationId = "$ruleId-$i",
            ruleId = ruleId,
            ruleVersionCid = ContentId.of(ruleId.encodeToByteArray()),
            salience = 50,
            sequence = i.toLong(),
            supportCids = emptyList(),
            bindings = mapOf("i" to "$i"),
        )
        for (i in 1..5) ring.retain(activation(BoardRules.WIP_BREACH, i))
        ring.retain(activation(BoardRules.STALL, 1))

        assertEquals(
            listOf("3", "4", "5"),
            ring.tail(BoardRules.WIP_BREACH).map { it.bindings.getValue("i") },
            "ring keeps the LAST cap activations, oldest evicted",
        )
        assertEquals(1, ring.tail(BoardRules.STALL).size)
        assertTrue(ring.tail(BoardRules.CYCLE_GUARD).isEmpty(), "unfired rules answer empty, never null")
    }
}
