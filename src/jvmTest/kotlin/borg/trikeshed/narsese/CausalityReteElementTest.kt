package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live-layer gate: the rete fires against the bag as it exists, and the
 * curator impulse element lands hindsight verdicts as banked knowledge +
 * bag signals.
 */
class CausalityReteElementTest {

    private suspend fun BeliefBagElement.settle() {
        var quiet = 0
        var spins = 0
        while (spins++ < 400 && quiet < 3) {
            delay(10)
            if (intake.isEmpty) quiet++ else quiet = 0
        }
        delay(25)
    }

    private fun signal(subject: String, obj: String, relation: RelationKind = RelationKind.MATCH) = SemanticSignal(
        angular = AngularCodec.encode(relation = relation, taxonomyKey = "test", subjectTerm = subject, objectTerm = obj),
        evidence = EvidenceCoord(Nal.UNIT, 0L),
        relation = relation,
        subjectCid = ContentId.of(subject.encodeToByteArray()).value,
        objectCid = ContentId.of(obj.encodeToByteArray()).value,
    )

    @Test
    fun fireLiveMintsDiscountedConsequentsIntoBag() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val rule = EternalRule("fire", "smoke", NalCopula.IMPLICATION, EvidenceCoord(10 * Nal.UNIT, 0L))
        val rete = CausalityReteElement(bag, listOf(rule).toSeries(), discount = 0.5f)
        rete.open()

        // mint the antecedent assertion and register its term identity
        val antecedent = signal("fire", "flame")
        bag.intake.send(BeliefIntake.Mint(antecedent, BudgetCoord(0.8f, 0.5f, 0.5f)))
        bag.settle()
        rete.register(antecedent.angular, "fire", "flame")

        val landed = rete.fireLive()
        bag.settle()
        assertEquals(1, landed.size, "the live rete must fire against the bag as it exists")
        assertTrue(landed[0].second.contains("fire ==> smoke"))

        // the consequent landed in the bag at discounted evidence
        val snapshot = bag.snapshot()
        val consequent = snapshot.values.firstOrNull { it.angular == landed[0].first }
        assertTrue(consequent != null, "firing must mint the consequent into the bag")
        assertEquals(5 * Nal.UNIT, consequent!!.evidence.positive, "support must carry the weak-rule discount")
        bag.drain()
    }

    @Test
    fun unregisteredSignalsAreSkippedNotGuessed() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val rule = EternalRule("fire", "smoke", NalCopula.IMPLICATION, EvidenceCoord(10 * Nal.UNIT, 0L))
        val rete = CausalityReteElement(bag, listOf(rule).toSeries())
        rete.open()

        // mint WITHOUT registering term identity — the rete must not guess terms
        bag.intake.send(BeliefIntake.Mint(signal("fire", "flame"), BudgetCoord(0.8f, 0.5f, 0.5f)))
        bag.settle()
        val landed = rete.fireLive()
        assertEquals(0, landed.size, "unregistered signals must be skipped, never term-guessed")
        bag.drain()
    }

    @Test
    fun curatorImpulseElementBanksAndMints() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val rete = CausalityReteElement(bag, emptyList<EternalRule>().toSeries())
        rete.open()
        val curator = CuratorImpulseElement(bag, rete = rete)
        curator.open()

        val impulses = listOf(
            CuratorImpulse(CuratorImpulseKind.CONSOLIDATE, "skill-a", "merge dupes"),
            CuratorImpulse(CuratorImpulseKind.PRUNE, "skill-b", "stale"),
        ).toSeries()
        val scenarios = listOf(
            ReplayScenario("s1", "skill-a", listOf(ReplayTurn("user", "replay"), ReplayTurn("agent", "outcome [PASS]")).toSeries()),
            ReplayScenario("s2", "skill-b", listOf(ReplayTurn("user", "replay"), ReplayTurn("agent", "outcome [FAIL]")).toSeries()),
        ).toSeries()

        val landed = curator.train(impulses, scenarios)
        bag.settle()
        assertEquals(2, landed.size, "SUPPORTED + REFUTED both mint; NEUTRAL would not")

        // banked knowledge is queryable predicate logic
        val hits = curator.queryBank("(instance impulse_consolidate_skill-a Agent)")
        assertTrue(hits.isNotEmpty(), "assessed impulses must bank as SUMO Agents")
        val impl = curator.queryBank("(=> (verdict impulse_prune_skill-b REFUTED) ?O)")
        assertTrue(impl.isNotEmpty(), "refuted verdicts must bank as implications")

        // the refuted signal landed with negative evidence (refutation front)
        val snapshot = bag.snapshot()
        val refuted = snapshot.values.firstOrNull { it.relation == RelationKind.CONTRADICTION }
        assertTrue(refuted != null, "REFUTED must mint a CONTRADICTION signal")
        assertTrue(Nal.truthOf(refuted!!.evidence).frequency < 0.5f)
        bag.drain()
    }

    @Test
    fun neutralVerdictsMintNothing() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val curator = CuratorImpulseElement(bag)
        curator.open()
        val impulses = listOf(CuratorImpulse(CuratorImpulseKind.PATCH, "skill-z", "x")).toSeries()
        val scenarios = listOf(
            ReplayScenario("s1", "skill-z", listOf(ReplayTurn("user", "replay"), ReplayTurn("agent", "no marker")).toSeries()),
        ).toSeries()
        val landed = curator.train(impulses, scenarios)
        bag.settle()
        assertEquals(0, landed.size, "NEUTRAL mints nothing — honesty over volume")
        assertEquals(0, bag.size)
        bag.drain()
    }
}
