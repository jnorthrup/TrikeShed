package borg.trikeshed.narsese

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase-8 gate: pure induction pass — evidence counts, intake cap, drain hygiene. */
class TurnReviewElementTest {

    private suspend fun BeliefBagElement.settle() {
        var quiet = 0; var spins = 0
        while (spins++ < 400 && quiet < 3) { delay(10); if (intake.isEmpty) quiet++ else quiet = 0 }
        delay(25)
    }

    @Test
    fun observationsAndInductionsLand() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val review = TurnReviewElement(bag)
        review.open()
        val facts = listOf(
            TurnReviewElement.TurnFact("crumb_walk", ok = true, contextTerm = "kotlin build task", objectTerm = "kotlin-gradle"),
            TurnReviewElement.TurnFact("skill_scribe", ok = true, contextTerm = "kotlin build task", objectTerm = "gradle-notes"),
            TurnReviewElement.TurnFact("bag_recall", ok = true, contextTerm = "memory probe"),
        )
        val landed = review.reviewTurn(facts, turnSucceeded = true)
        bag.settle()
        // 3 observations + 1 induction (the two kotlin-context objects generalize pairwise)
        assertEquals(4, landed.size)
        assertTrue(landed.all { it.second.isNotBlank() }, "every landing carries a gloss")
        assertEquals(4, bag.size)
        // induced belief is NAL-weak: strictly less total evidence than an observation
        val induced = bag.snapshot().values.first { it.relation == RelationKind.ATTRACTION }
        val observed = bag.snapshot().values.first { it.relation == RelationKind.CAUSALITY }
        assertTrue(induced.evidence.total < observed.evidence.total, "induction must be weak")
        assertTrue(induced.provenanceCid != null, "induced belief must carry its receipt CID")
        review.drain()
        bag.drain()
    }

    @Test
    fun intakeCapBoundsTheTurn() = runBlocking {
        val bag = BeliefBagElement(capacity = 256)
        bag.open()
        val review = TurnReviewElement(bag, intakeCap = 5)
        review.open()
        val facts = (1..20).map {
            TurnReviewElement.TurnFact("verb$it", ok = true, contextTerm = "shared context", objectTerm = "obj$it")
        }
        val landed = review.reviewTurn(facts, turnSucceeded = true)
        bag.settle()
        assertEquals(5, landed.size, "the 16→5 intake cap must bound the pass")
        assertTrue(bag.size <= 5)
        review.drain()
        bag.drain()
    }

    @Test
    fun failedTurnLandsNegativeEvidence() = runBlocking {
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        val review = TurnReviewElement(bag)
        review.open()
        review.reviewTurn(
            listOf(TurnReviewElement.TurnFact("crumb_walk", ok = true, contextTerm = "doomed task", objectTerm = "bad-skill")),
            turnSucceeded = false,
        )
        bag.settle()
        val belief = bag.snapshot().values.single()
        assertEquals(0L, belief.evidence.positive)
        assertEquals(Nal.UNIT, belief.evidence.negative, "failed turn = negative observation")
        review.drain()
        bag.drain()
    }

    @Test
    fun drainedReviewRefusesNewTurns() = runBlocking {
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        val review = TurnReviewElement(bag)
        review.open()
        review.drain()
        assertEquals(ElementState.CLOSED, review.state)
        val landed = review.reviewTurn(
            listOf(TurnReviewElement.TurnFact("v", true, "ctx", "o")),
            turnSucceeded = true,
        )
        assertEquals(0, landed.size, "a drained review lands nothing — no orphan intakes")
        bag.settle()
        assertEquals(0, bag.size)
        bag.drain()
    }
}
