package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.job.ContentId
import kotlin.test.*

/** Application identity is distinct from the angular address used to route evidence. */
class CausalityReteIdentityTest {
    private val rule = EternalRule("Delays", "cancellations", NalCopula.IMPLICATION,
        EvidenceCoord(Nal.UNIT, 0L), "source-a")

    private fun assertion(party: String) = ReteAssertion("Delays", party,
        KgTriplet("Delays", "state", party).angularIdentity(), EvidenceCoord(Nal.UNIT, 0L),
        RelationKind.CAUSALITY)

    @Test
    fun partiesSharingAnAngularAddressHaveDistinctApplications() {
        val a = assertion("party-a")
        val b = assertion("party-b")
        assertEquals(a.angular, b.angular, "Angular routing deliberately excludes the object")
        val rete = CausalityRete(s_[rule])
        val firings = rete.fire(s_[a, b, a])
        assertNotEquals(firings[0].firingCid, firings[1].firingCid,
            "Distinct party hypotheses must not share an application identity")
        assertEquals(firings[0].firingCid, firings[2].firingCid, "Exact replay retains application identity")
        assertEquals(firings[0].refractionCid, firings[1].refractionCid,
            "Distinct provenance cannot inflate support at a shared angular address")
        assertEquals(firings[0].sourceRuleCid, firings[1].sourceRuleCid)
        assertEquals(firings[0].consequentAngular, firings[1].consequentAngular,
            "Application identity does not change the bag's routing algebra")
        assertEquals(firings[0].support, firings[1].support)
        assertEquals(EvidenceDependence.DEPENDENT, firings[0].dependence)
    }

    @Test
    fun existingAngularRefractionKeysRemainRecognizable() {
        val firing = CausalityRete(s_[rule]).fire(s_[assertion("party-a")])[0]
        val priorKey = ContentId.of(
            "${firing.sourceRuleCid.value}|${firing.matched.angular}|${firing.consequentAngular}".encodeToByteArray())
        assertTrue(firing.refractionCid in setOf(priorKey),
            "An already-seen key must still suppress the application after its provenance ID changes")
        assertNotEquals(priorKey, firing.firingCid)
    }

    @Test
    fun matchedTermsAndRelationSurviveAngularAliasing() {
        val base = CausalityRete(s_[rule]).fire(s_[assertion("party-a")])[0]
        assertNotEquals(base.firingCid, base.copy(matched = base.matched.copy(subject = "other")).firingCid)
        assertNotEquals(base.firingCid, base.copy(matched = base.matched.copy(relation = RelationKind.MATCH)).firingCid)
    }

    @Test
    fun termBoundariesCannotAliasInTheApplicationEncoding() {
        val base = CausalityRete(s_[rule]).fire(s_[assertion("party-a")])[0]
        val left = base.copy(matched = base.matched.copy(subject = "a|b", obj = "c"))
        val right = base.copy(matched = base.matched.copy(subject = "a", obj = "b|c"))
        assertNotEquals(left.firingCid, right.firingCid,
            "Term content must be framed, not joined by an unescaped delimiter")
        assertEquals(left.firingCid, left.copy().firingCid)
    }

    @Test
    fun accumulatedEvidenceDoesNotCreateAnotherApplication() {
        val base = CausalityRete(s_[rule]).fire(s_[assertion("party-a")])[0]
        val revised = base.copy(matched = base.matched.copy(evidence = EvidenceCoord(9000, 3000)),
            support = EvidenceCoord(1, 0), floored = true)
        assertEquals(base.firingCid, revised.firingCid,
            "Changing evidence or the offer does not create a new rule application")
        assertEquals(base.refractionCid, revised.refractionCid)
    }

    @Test
    fun ruleVersionsAndEquivalenceDirectionsRemainDistinct() {
        val equivalence = rule.copy(copula = NalCopula.EQUIVALENCE)
        val rete = CausalityRete(s_[equivalence, equivalence.copy(provenanceCid = "source-b")])
        val firings = rete.fire(s_[assertion("party-a"), assertion("party-a").copy(subject = "cancellations")])
        assertEquals(4, firings.size)
        assertEquals(4, firings.view.map { it.firingCid }.distinct().size)
        assertEquals(firings[0].sourceRuleCid, firings[2].sourceRuleCid,
            "Reverse equivalence retains the original rule's provenance")
        assertEquals("Delays", firings[2].rule.consequent)
    }
}

