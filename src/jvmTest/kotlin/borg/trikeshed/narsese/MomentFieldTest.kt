package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NAL-9 gate: the moment field as the bag's self-model — whitening beats the
 * shared-prefix confound WITHOUT the s⁴ hack, the crux axis finds the
 * controversy, Hotelling T² flags alien cohorts, principal concepts find the
 * dominant variation, and the shrunk field stays sane at cold start.
 */
class MomentFieldTest {

    private fun signal(term: String, positive: Long, negative: Long = 0, taxonomy: String = "skills") = SemanticSignal(
        angular = AngularCodec.encode(RelationKind.CAUSALITY, taxonomyKey = taxonomy, subjectTerm = term),
        evidence = EvidenceCoord(positive, negative),
        relation = RelationKind.CAUSALITY,
        subjectCid = ContentId.of(term.encodeToByteArray()).value,
    )

    private suspend fun BeliefBagElement.settle() {
        var quiet = 0; var spins = 0
        while (spins++ < 400 && quiet < 3) { delay(10); if (intake.isEmpty) quiet++ else quiet = 0 }
        delay(25)
    }

    @Test
    fun whiteningBeatsSharedPrefixConfoundWithoutS4() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        // all share relation+taxonomy bits (the confound); one is a true term-neighbor,
        // one is high-priority distant noise
        bag.intake.send(BeliefIntake.Mint(signal("kotlin gradle build works", 20 * Nal.UNIT), BudgetCoord(0.6f, 0.5f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(signal("watercolor pigment mixing", 20 * Nal.UNIT), BudgetCoord(0.95f, 0.5f, 0.5f)))
        for (i in 1..12) {
            bag.intake.send(BeliefIntake.Mint(signal("filler belief number $i", 5 * Nal.UNIT), BudgetCoord(0.5f, 0.5f, 0.5f)))
        }
        bag.settle()
        val proposal = AngularCodec.encode(RelationKind.CAUSALITY, taxonomyKey = "skills", subjectTerm = "kotlin gradle build working")
        val r = bag.resonateWhitened(proposal, k = 3)
        assertTrue(r.synonyms.isNotEmpty())
        val kotlinAngular = signal("kotlin gradle build works", 1).angular
        assertEquals(kotlinAngular, r.synonyms[0].angular,
            "whitened distance must rank the true neighbor above higher-priority distant noise")
        bag.drain()
    }

    @Test
    fun cruxAxisSeparatesTheControversy() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        // a contested topic: same term-space, opposite polarity clusters
        bag.intake.send(BeliefIntake.Mint(signal("hotswap is reliable", 20 * Nal.UNIT), BudgetCoord(0.8f, 0.5f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(signal("hotswap is reliably good", 15 * Nal.UNIT), BudgetCoord(0.7f, 0.5f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(signal("hotswap is unreliable junk", 0, 20 * Nal.UNIT), BudgetCoord(0.8f, 0.5f, 0.5f)))
        // an uncontested bystander
        bag.intake.send(BeliefIntake.Mint(signal("water is wet obviously", 20 * Nal.UNIT), BudgetCoord(0.5f, 0.5f, 0.5f)))
        bag.settle()
        val field = bag.field()
        val axis = field.cruxAxis()
        val pos = field.cruxScore(signal("hotswap is reliable", 1).angular, axis)
        val neg = field.cruxScore(signal("hotswap is unreliable junk", 0, 1).angular, axis)
        assertTrue(pos * neg < 0f, "polarity clusters must project on opposite sides of the crux axis (pos=$pos neg=$neg)")
        bag.drain()
    }

    @Test
    fun hotellingFlagsAlienCohort() = runBlocking {
        val bag = BeliefBagElement(capacity = 128)
        bag.open()
        // baseline population in one taxonomy
        for (i in 1..20) {
            bag.intake.send(BeliefIntake.Mint(signal("normal behavior pattern $i", 10 * Nal.UNIT), BudgetCoord(0.6f, 0.5f, 0.5f)))
        }
        // an alien cohort: different taxonomy, tightly-clustered coordinates
        for (i in 1..6) {
            bag.intake.send(
                BeliefIntake.Mint(signal("legion signature", 10 * Nal.UNIT, taxonomy = "pen/mux_converse"), BudgetCoord(0.6f, 0.5f, 0.5f)),
            )
        }
        bag.settle()
        val field = bag.field()
        val penSig = AngularCodec.taxonomySigOfKey("pen/mux_converse")
        val alien = field.hotelling { AngularCodec.Fields.taxonomySigOf(it) == penSig }
        val normalSig = AngularCodec.taxonomySigOfKey("skills")
        val normal = field.hotelling { AngularCodec.Fields.taxonomySigOf(it) == normalSig }
        assertTrue(alien > normal, "the alien cohort's T² must exceed the in-distribution cohort's (alien=$alien normal=$normal)")
        bag.drain()
    }

    @Test
    fun principalConceptFindsDominantVariation() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        // variation concentrated on the taxonomy axis: two clusters, two taxonomies
        for (i in 1..8) bag.intake.send(BeliefIntake.Mint(signal("alpha topic $i", 10 * Nal.UNIT, taxonomy = "skills/coding"), BudgetCoord(0.7f, 0.5f, 0.5f)))
        for (i in 1..8) bag.intake.send(BeliefIntake.Mint(signal("alpha topic $i", 10 * Nal.UNIT, taxonomy = "memories/user"), BudgetCoord(0.7f, 0.5f, 0.5f)))
        bag.settle()
        val concepts = bag.field().principalConcepts(1)
        assertTrue(concepts.isNotEmpty())
        val (variance, vec) = concepts[0]
        assertTrue(variance > 0f)
        // the top eigenvector's heaviest loadings should sit in the taxonomy block [52..37]
        val topBit = vec.withIndex().maxByOrNull { abs(it.value) }!!.index
        assertTrue(topBit in 37..52, "dominant variation lives on the taxonomy axis, got bit $topBit")
        bag.drain()
    }

    @Test
    fun coldStartStaysFiniteUnderShrinkage() = runBlocking {
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        bag.intake.send(BeliefIntake.Mint(signal("lonely first belief", 5 * Nal.UNIT), BudgetCoord(0.5f, 0.5f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(signal("second belief", 5 * Nal.UNIT), BudgetCoord(0.5f, 0.5f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(signal("doubting belief", 0, 5 * Nal.UNIT), BudgetCoord(0.5f, 0.5f, 0.5f)))
        bag.settle()
        val r = bag.resonateWhitened(signal("lonely first belief", 1).angular, k = 3)
        assertTrue(r.synonyms.isNotEmpty(), "n=3 must not blow up the inverse")
        for (pk in r.synonyms + r.antonyms) {
            assertTrue(pk.distance.isFinite() && pk.activation.isFinite(), "shrinkage must keep the field finite")
        }
        bag.drain()
    }

    @Test
    fun fieldRebuildsLazilyOnDirty() = runBlocking {
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        bag.intake.send(BeliefIntake.Mint(signal("first", 5 * Nal.UNIT), BudgetCoord(0.5f, 0.5f, 0.5f)))
        bag.settle()
        assertEquals(1, bag.field().n)
        bag.intake.send(BeliefIntake.Mint(signal("second frontier addition", 5 * Nal.UNIT), BudgetCoord(0.5f, 0.5f, 0.5f)))
        bag.settle()
        assertEquals(2, bag.field().n, "a landed intake must dirty the field; the next read rebuilds")
        bag.drain()
    }
}
