package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The evaluator is inside the receipt's canonical CID, so who derived a belief is part of what
 * the belief IS. These assert that the identity actually discriminates — before this, every
 * derivation in the tree was attributed to a hardcoded code-path name.
 */
private fun runTestCompat(body: suspend () -> Unit) = runTest { body() }

class EvaluatorProvenanceTest {

    private val p1 = PremiseReceipt(
        ContentId.of("a".encodeToByteArray()),
        TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = "ctx")),
        TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = "x")),
    )
    private val p2 = PremiseReceipt(
        ContentId.of("b".encodeToByteArray()),
        TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = "ctx")),
        TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = "y")),
    )
    private val evidence = Nal.induce(TruthCoord(1f, 0.9f), TruthCoord(1f, 0.9f))

    @Test
    fun twoModelsInducingTheSameThingAreDifferentCitizens() {
        val a = DerivationReceipt.induction(p1, p2, evidence, Evaluator.model("zai/glm-5").cid)
        val b = DerivationReceipt.induction(p1, p2, evidence, Evaluator.model("openai/gpt-4o").cid)
        assertNotEquals(
            a.canonicalCid.value, b.canonicalCid.value,
            "same premises, same evidence, different guide — these must not collide",
        )
    }

    @Test
    fun theSameModelIsStableAcrossProcesses() {
        // The CID is over kind and id, never an object identity, or provenance would not survive a restart.
        assertEquals(Evaluator.model("zai/glm-5").cid.value, Evaluator.model("zai/glm-5").cid.value)
        val a = DerivationReceipt.induction(p1, p2, evidence, Evaluator.model("zai/glm-5").cid)
        val b = DerivationReceipt.induction(p1, p2, evidence, Evaluator.model("zai/glm-5").cid)
        assertEquals(a.canonicalCid.value, b.canonicalCid.value)
    }

    @Test
    fun aPureKindIsDistinctFromAModelOfTheSameName() {
        assertNotEquals(Evaluator.pure("turn-review").cid.value, Evaluator.model("turn-review").cid.value)
    }

    @Test
    fun onlyAPurePassClaimsToBeReproducible() {
        // An LLM-guided induction cannot be recomputed by replaying the premises through NAL, so an
        // audit has to know which derivations it can recompute and which it can only cite.
        assertTrue(Evaluator.pure("turn-review").reproducible)
        assertFalse(Evaluator.model("zai/glm-5").reproducible)
        assertFalse(Evaluator.human("jnorthrup").reproducible)
    }

    @Test
    fun theDefaultKeepsTheReceiptTheElementAlwaysMinted() {
        // Nothing changes for an existing caller: the default identity is the old constant's bytes.
        assertEquals(
            ContentId.of("pure:turn-review".encodeToByteArray()).value,
            Evaluator.pure("turn-review").cid.value,
        )
    }
}

/**
 * The induction lego resolves its guide the way every other lego resolves a model: the card's
 * `model` param, then the live mux. Nothing here picks a vendor.
 */
class ReviewRunnerEvaluatorTest {

    private val facts = listOf(
        mapOf("verb" to "opened", "ok" to true, "context" to "ctx", "object" to "a"),
        mapOf("verb" to "closed", "ok" to true, "context" to "ctx", "object" to "b"),
    )

    private suspend fun run(model: String?): Map<String, Any?> {
        val bag = BeliefBagElement()
        bag.open()
        val review = TurnReviewElement(bag)
        review.open()
        val runner = BeliefsNodes.reviewRunner(review)
        val params = if (model == null) emptyMap() else mapOf("model" to model)
        @Suppress("UNCHECKED_CAST")
        return runner.run(
            borg.trikeshed.lcnc.LcncNode("r", "beliefs.review", params = params),
            mapOf("facts" to facts),
        ) as Map<String, Any?>
    }

    @Test
    fun aNamedModelBecomesTheEvaluatorAndThePassIsNotReproducible() = runTestCompat {
        val out = run("zai/glm-5")
        assertEquals("model:zai/glm-5", out["evaluator"])
        assertEquals(false, out["reproducible"], "an LLM-guided induction cannot be replayed from premises")
    }

    @Test
    fun noModelLeavesThePassPureAndReproducible() = runTestCompat {
        val out = run(null)
        assertEquals("pure:turn-review", out["evaluator"])
        assertEquals(true, out["reproducible"])
    }
}
