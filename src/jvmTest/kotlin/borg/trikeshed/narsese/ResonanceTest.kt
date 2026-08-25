package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The vectorized hit on all action potentials: one flat sweep returns both the
 * support front (synonym peaks) and refutation front (antonym peaks) of a
 * proposal — and a frontier addition participates with zero dropout lag.
 */
class ResonanceTest {

    private fun mintSignal(term: String, positive: Long, negative: Long) = SemanticSignal(
        angular = AngularCodec.encode(RelationKind.CAUSALITY, taxonomyKey = "skills", subjectTerm = term),
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
    fun resonanceSplitsSupportAndRefutationFronts() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        // support: near-identical terms with positive evidence
        bag.intake.send(BeliefIntake.Mint(mintSignal("kotlin gradle build works", 20 * Nal.UNIT, 0), BudgetCoord(0.8f, 0.5f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(mintSignal("kotlin gradle builds work", 10 * Nal.UNIT, 0), BudgetCoord(0.7f, 0.5f, 0.5f)))
        // refutation: near term, negative evidence
        bag.intake.send(BeliefIntake.Mint(mintSignal("kotlin gradle build breaks", 0, 15 * Nal.UNIT), BudgetCoord(0.8f, 0.5f, 0.5f)))
        // distant noise
        bag.intake.send(BeliefIntake.Mint(mintSignal("watercolor pigment mixing", 20 * Nal.UNIT, 0), BudgetCoord(0.9f, 0.5f, 0.5f)))
        bag.settle()

        val proposal = AngularCodec.encode(RelationKind.CAUSALITY, taxonomyKey = "skills", subjectTerm = "kotlin gradle build working")
        val r = bag.resonate(proposal, k = 3)

        assertTrue(r.synonyms.isNotEmpty(), "support front must surface")
        assertTrue(r.antonyms.isNotEmpty(), "refutation front must surface")
        assertTrue(
            Nal.truthOf(r.synonyms[0].signal.evidence).frequency >= 0.5f,
            "synonym peaks carry positive evidence",
        )
        assertTrue(
            Nal.truthOf(r.antonyms[0].signal.evidence).frequency < 0.5f,
            "antonym peaks carry negative evidence",
        )
        // the nearest positive neighbor should out-rank the distant noise
        val topSubject = r.synonyms[0].signal.subjectCid
        assertTrue(topSubject != ContentId.of("watercolor pigment mixing".encodeToByteArray()).value,
            "distant noise must not top the support front")
        bag.drain()
    }

    @Test
    fun frontierAdditionsResonateWithZeroLag() = runBlocking {
        val bag = BeliefBagElement(capacity = 32)
        bag.open()
        bag.intake.send(BeliefIntake.Mint(mintSignal("baseline belief", 5 * Nal.UNIT, 0), BudgetCoord(0.5f, 0.5f, 0.5f)))
        bag.settle()
        val proposal = AngularCodec.encode(RelationKind.CAUSALITY, taxonomyKey = "skills", subjectTerm = "frontier addition topic")
        val before = bag.resonate(proposal, k = 4)
        val beforeTop = before.synonyms.firstOrNull()?.signal?.subjectCid
        // frontier addition: exact-topic belief lands...
        bag.intake.send(BeliefIntake.Mint(mintSignal("frontier addition topic", 30 * Nal.UNIT, 0), BudgetCoord(0.9f, 0.5f, 0.5f)))
        bag.settle()
        // ...and the very next sweep sees it as the top peak — no reindex, no dropout window
        val after = bag.resonate(proposal, k = 4)
        val frontierCid = ContentId.of("frontier addition topic".encodeToByteArray()).value
        assertTrue(after.synonyms.isNotEmpty())
        assertTrue(
            after.synonyms[0].signal.subjectCid == frontierCid && beforeTop != frontierCid,
            "the frontier addition must top the very next sweep",
        )
        bag.drain()
    }
}
