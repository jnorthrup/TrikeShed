package borg.trikeshed.epistemic

import borg.trikeshed.lib.j
import borg.trikeshed.narsese.TruthCoord
import borg.trikeshed.ontology.SumoOntology.SumoCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The edge joins the three engines that were previously only bridged pairwise: a SUMO coordinate,
 * a NARS truth value, and a composition operator a Rete beta-memory can call.
 */
class EpistemicEdgeTest {

    private val strong = TruthCoord(0.9f, 0.9f)

    @Test
    fun anEdgeIsAJoinOfGeometryAndEvidence() {
        val e = epistemicEdge(SumoCategory.Agent, SumoCategory.Relation, SumoCategory.Process, strong)
        assertEquals(SumoCategory.Agent, e.path.subject)
        assertEquals(SumoCategory.Process, e.path.`object`)
        assertEquals(0.9f, e.truth.frequency, 0.001f)
        // It is a Join like everything else, so a/b destructuring is the same algebra as Series.
        val (path, truth) = e
        assertEquals(e.path, path)
        assertEquals(e.truth, truth)
        assertEquals("(Relation Agent Process)", e.path.kif())
    }

    @Test
    fun theOntologyRefusesAPathItDoesNotAdmit() {
        // A relation cannot stand at the end of a relation: this is the "structurally impossible"
        // claim, and it must be a refusal rather than a wrong answer.
        assertFalse(OntologicalPath.admits(SumoCategory.Relation, SumoCategory.Relation, SumoCategory.Process))
        assertFailsWith<IllegalArgumentException> {
            epistemicEdge(SumoCategory.Relation, SumoCategory.Relation, SumoCategory.Process, strong)
        }
    }

    @Test
    fun deductionComposesAcrossTheSharedMiddleTerm() {
        // M→P and S→M ⊢ S→P, with M = Object.
        val mp = epistemicEdge(SumoCategory.Object, SumoCategory.Relation, SumoCategory.Process, strong)
        val sm = epistemicEdge(SumoCategory.Agent, SumoCategory.Relation, SumoCategory.Object, strong)
        val sp = assertNotNull(mp.compose(sm), "M→P joined with S→M must yield S→P")
        assertEquals(SumoCategory.Agent, sp.path.subject)
        assertEquals(SumoCategory.Process, sp.path.`object`)
        // Deduction is strong but never certain: the conclusion is weaker than either premise.
        assertTrue(sp.truth.confidence < strong.confidence,
            "conclusion c=${sp.truth.confidence} should be under premise c=${strong.confidence}")
        assertTrue(sp.truth.confidence > 0f, "and it should carry real evidence, not zero")
    }

    @Test
    fun edgesThatDoNotMeetHaveNoGradient() {
        val a = epistemicEdge(SumoCategory.Agent, SumoCategory.Relation, SumoCategory.Process, strong)
        val b = epistemicEdge(SumoCategory.Quantity, SumoCategory.Relation, SumoCategory.Attribute, strong)
        assertNull(a.compose(b), "no shared term means no conclusion")
    }

    @Test
    fun differentRelationsDoNotCompose() {
        val a = epistemicEdge(SumoCategory.Object, SumoCategory.Relation, SumoCategory.Process, strong)
        val b = OntologicalPath(SumoCategory.Agent, SumoCategory.Attribute, SumoCategory.Object) to strong
        // Attribute is not under Relation, so such a path cannot even be built.
        assertFalse(OntologicalPath.admits(b.first.subject, b.first.relation, b.first.`object`))
        assertNull(a.compose(b.first j b.second))
    }

    @Test
    fun aContradictionNeedsConfidenceOnBothSides() {
        val yes = epistemicEdge(SumoCategory.Agent, SumoCategory.Relation, SumoCategory.Process, TruthCoord(0.95f, 0.95f))
        val no = epistemicEdge(SumoCategory.Agent, SumoCategory.Relation, SumoCategory.Process, TruthCoord(0.05f, 0.95f))
        assertTrue(yes.contradicts(no), "high confidence on both sides of 0.5 is the singularity")

        val unsure = epistemicEdge(SumoCategory.Agent, SumoCategory.Relation, SumoCategory.Process, TruthCoord(0.05f, 0.10f))
        assertFalse(yes.contradicts(unsure), "a weakly-held opposite is disagreement, not a paradox")

        val elsewhere = epistemicEdge(SumoCategory.Object, SumoCategory.Relation, SumoCategory.Process, TruthCoord(0.05f, 0.95f))
        assertFalse(yes.contradicts(elsewhere), "different coordinates cannot contradict")
    }

    @Test
    fun compositionIsAssociativeInGeometryIfNotInEvidence() {
        // Three links of a chain: Agent→Object→Process→Quantity.
        val ao = epistemicEdge(SumoCategory.Agent, SumoCategory.Relation, SumoCategory.Object, strong)
        val op = epistemicEdge(SumoCategory.Object, SumoCategory.Relation, SumoCategory.Process, strong)
        val pq = epistemicEdge(SumoCategory.Process, SumoCategory.Relation, SumoCategory.Quantity, strong)
        val left = assertNotNull(pq.compose(assertNotNull(op.compose(ao))))
        val right = assertNotNull(assertNotNull(pq.compose(op)).compose(ao))
        assertEquals(left.path, right.path, "the geometry of a chain does not depend on bracketing")
        // Evidence is NOT associative and must not be asserted to be: NAL is non-axiomatic.
    }
}
