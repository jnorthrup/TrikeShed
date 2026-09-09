package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
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
