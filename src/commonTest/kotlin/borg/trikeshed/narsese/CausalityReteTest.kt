package borg.trikeshed.narsese

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The assertion under test: a causality rete is LIVE for ETERNAL truths at
 * potential NARS-bag discounts, supporting a minimum understanding of the
 * stochastic bag assertions as they exist.
 */
class CausalityReteTest {

    private fun rule(a: String, c: String, copula: NalCopula = NalCopula.IMPLICATION, wPlus: Long = 10 * Nal.UNIT) =
        EternalRule(a, c, copula, EvidenceCoord(wPlus, 0L))

    private fun assertion(subject: String, obj: String = "o") = ReteAssertion(
        subject = subject,
        obj = obj,
        angular = KgTriplet(subject, "asserts", obj).angularIdentity(),
        evidence = EvidenceCoord(Nal.UNIT, 0L),
        relation = RelationKind.MATCH,
    )

    @Test
    fun admitsOnlyEternalTruths() {
        val (rete, rejected) = CausalityRete.admit(
            listOf(
                rule("fire", "smoke"),
                EternalRule("a", "b", NalCopula.PREDICTIVE_IMPLICATION, EvidenceCoord(Nal.UNIT, 0L)),
                EternalRule("c", "d", NalCopula.INHERITANCE, EvidenceCoord(Nal.UNIT, 0L)),
                rule("heat", "expansion", NalCopula.EQUIVALENCE),
            ).toSeries(),
        )
        assertEquals(2, rejected, "temporal and non-implicational rules must be refused")
        assertEquals(2, rete.rules.size)
    }

    @Test
    fun firesLiveAgainstAssertionsAsTheyExist() {
        val rete = CausalityRete(listOf(rule("fire", "smoke")).toSeries())
        val firings = rete.fire(listOf(assertion("fire"), assertion("unrelated")).toSeries())
        assertEquals(1, firings.size)
        assertEquals("smoke", firings[0].rule.consequent)
        assertEquals("fire", firings[0].matched.subject)
    }

    @Test
    fun supportIsDiscountedByTheWeakRuleHaircut() {
        val rete = CausalityRete(listOf(rule("fire", "smoke", wPlus = 10 * Nal.UNIT)).toSeries(), discount = 0.5f)
        val firing = rete.fire(listOf(assertion("fire")).toSeries())[0]
        assertEquals(5 * Nal.UNIT, firing.support.positive, "banked evidence must be halved at the discount")
        assertFalse(firing.floored)
    }

    @Test
    fun minimumUnderstandingFloorGuaranteesSupport() {
        // banked evidence so weak the discount rounds it to nothing
        val rete = CausalityRete(
            listOf(rule("whisper", "echo", wPlus = 1L)).toSeries(),
            discount = 0.5f,
            minSupport = Nal.UNIT / 4,
        )
        val firing = rete.fire(listOf(assertion("whisper")).toSeries())[0]
        assertTrue(firing.floored)
        assertEquals(Nal.UNIT / 4, firing.support.positive, "every matched assertion gets a floor of understanding")
        assertTrue(firing.support.positive > 0L)
    }

    @Test
    fun equivalenceFiresFromEitherEnd() {
        val rete = CausalityRete(listOf(rule("energy", "mass", NalCopula.EQUIVALENCE)).toSeries())
        val fromLeft = rete.fire(listOf(assertion("energy")).toSeries())
        val fromRight = rete.fire(listOf(assertion("mass")).toSeries())
        assertEquals(1, fromLeft.size)
        assertEquals("mass", fromLeft[0].rule.consequent)
        assertEquals(1, fromRight.size)
        assertEquals("energy", fromRight[0].rule.consequent, "equivalence must fire toward the unmatched end")
    }

    @Test
    fun emptyBagFiresNothing() {
        val rete = CausalityRete(listOf(rule("fire", "smoke")).toSeries())
        assertEquals(0, rete.fire(emptyList<ReteAssertion>().toSeries()).size)
    }

    @Test
    fun firingIsPotentialNotActual() {
        // the rete proposes; it never writes. A firing carries evidence for
        // the caller to mint — verify the bag is untouched by firing alone.
        val bag = HashMap<borg.trikeshed.lib.Join<Long, Long>, SemanticSignal>()
        val rete = CausalityRete(listOf(rule("fire", "smoke")).toSeries())
        val firings = rete.fire(listOf(assertion("fire")).toSeries())
        assertEquals(0, bag.size, "firing must not mutate any bag")
        // the caller's mint path: discounted budget, bag roulette decides
        val firing = firings[0]
        val minted = bag.reviseInto(
            firing.consequentAngular j 0L,
            SemanticSignal(
                angular = firing.consequentAngular,
                evidence = firing.support,
                relation = RelationKind.CAUSALITY,
                subjectCid = "sha256:" + "0".repeat(64),
            ),
        )
        assertEquals(1, minted.size)
        assertEquals(firing.support.positive, minted.values.first().evidence.positive)
    }
}
