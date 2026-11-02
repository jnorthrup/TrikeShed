package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegalNarsEvaluationTest {
    private val text = "A😀B"
    private val bytes = text.encodeToByteArray()
    private val excerpt = ContentId.of(bytes)
    // This is a claimed source identity; the evaluator is not supplied original bytes.
    private val original = ContentId.of("claimed-original".encodeToByteArray())
    private val excerpts get() = _m[excerpt j bytes]

    private fun witness(begin: Int, end: Int, quote: String, role: LegalEvalWitnessRole,
        cid: ContentId = excerpt) = LegalEvalWitness(cid, begin, end, quote, role)

    private fun case(name: String, label: LegalEvalLabel, anchors: List<LegalEvalWitness>) =
        LegalEvalCase(name, "Proposition for $name", original, excerpt, label, anchors)

    @Test
    fun explicitPredictionCanMatchProvisionalLabelAndAnnotatedWitness() {
        // The emoji occupies two UTF-16 code units, [1, 3).
        val anchor = witness(1, 3, "😀", LegalEvalWitnessRole.SUPPORTS)
        val item = case("supported", LegalEvalLabel.SUPPORTED, _l[anchor])
        val prediction = LegalEvalPrediction(item.propositionCid, LegalEvalLabel.SUPPORTED, _l[anchor])
        val report = LegalNarsEvaluation.score(_l[item], excerpts, _l[prediction])
        val row = report.rows.single()
        assertEquals(LegalEvalStatus.SCORED, row.status)
        assertEquals(true, row.labelAgrees)
        assertEquals(true, row.witnessIntegrity)
        assertEquals(true, row.acceptedWitness)
        assertEquals(true, row.groundedAgreement)
        assertEquals(1, report.scored)
        assertEquals(1, report.labelAgreements)
        assertEquals(1, report.groundedAgreements)
    }

    @Test
    fun exactQuoteWithAnUnannotatedRoleDoesNotBecomeSupport() {
        val anchor = witness(1, 3, "😀", LegalEvalWitnessRole.SUPPORTS)
        val item = case("wrong-role", LegalEvalLabel.SUPPORTED, _l[anchor])
        val claimed = witness(1, 3, "😀", LegalEvalWitnessRole.REFUTES)
        val prediction = LegalEvalPrediction(item.propositionCid, LegalEvalLabel.SUPPORTED, _l[claimed])
        val report = LegalNarsEvaluation.score(_l[item], excerpts, _l[prediction])
        val row = report.rows.single()
        assertEquals(LegalEvalStatus.SCORED, row.status)
        assertEquals(true, row.labelAgrees)
        assertEquals(true, row.witnessIntegrity)
        assertEquals(false, row.acceptedWitness)
        assertEquals(false, row.groundedAgreement)
        assertEquals(1, report.labelAgreements)
        assertEquals(0, report.groundedAgreements)
        val noWitness = LegalEvalPrediction(item.propositionCid, LegalEvalLabel.SUPPORTED, emptyList())
        val unsupported = LegalNarsEvaluation.score(_l[item], excerpts, _l[noWitness]).rows.single()
        assertEquals(true, unsupported.labelAgrees)
        assertEquals(false, unsupported.groundedAgreement)
        val repeated = LegalEvalPrediction(item.propositionCid, LegalEvalLabel.SUPPORTED, _l[anchor, anchor])
        val duplicated = LegalNarsEvaluation.score(_l[item], excerpts, _l[repeated]).rows.single()
        assertEquals(false, duplicated.witnessIntegrity)
        assertEquals(false, duplicated.groundedAgreement)
    }

    @Test
    fun conflictNeedsBothAnnotatedRolesWithoutAssumingIndependentSources() {
        val support = witness(0, 1, "A", LegalEvalWitnessRole.SUPPORTS)
        val refute = witness(3, 4, "B", LegalEvalWitnessRole.REFUTES)
        val item = case("conflict", LegalEvalLabel.CONFLICTED, _l[support, refute])
        val both = LegalEvalPrediction(item.propositionCid, LegalEvalLabel.CONFLICTED, _l[support, refute])
        val one = LegalEvalPrediction(item.propositionCid, LegalEvalLabel.CONFLICTED, _l[support])
        val complete = LegalNarsEvaluation.score(_l[item], excerpts, _l[both]).rows.single()
        val incomplete = LegalNarsEvaluation.score(_l[item], excerpts, _l[one]).rows.single()
        assertEquals(true, complete.groundedAgreement)
        assertEquals(true, incomplete.labelAgrees)
        assertEquals(false, incomplete.groundedAgreement)
        // Both quotations come from the same excerpt. This checks answer grounding only;
        // neither this test nor the score treats them as independent evidence.
    }

    @Test
    fun refutationRequiresARefutingAnnotatedWitness() {
        val refute = witness(3, 4, "B", LegalEvalWitnessRole.REFUTES)
        val item = case("refuted", LegalEvalLabel.REFUTED, _l[refute])
        val prediction = LegalEvalPrediction(item.propositionCid, LegalEvalLabel.REFUTED, _l[refute])
        val row = LegalNarsEvaluation.score(_l[item], excerpts, _l[prediction]).rows.single()
        assertEquals(LegalEvalStatus.SCORED, row.status)
        assertEquals(true, row.groundedAgreement)
    }

    @Test
    fun missingOutputIsNotAnExplicitUnknownPrediction() {
        val item = case("unknown", LegalEvalLabel.UNKNOWN, emptyList())
        val absent = LegalNarsEvaluation.score(_l[item], excerpts, emptyList())
        assertEquals(LegalEvalStatus.MISSING, absent.rows.single().status)
        assertEquals(1, absent.missing)
        assertEquals(0, absent.scored)
        val explicit = LegalEvalPrediction(item.propositionCid, LegalEvalLabel.UNKNOWN, emptyList())
        val present = LegalNarsEvaluation.score(_l[item], excerpts, _l[explicit])
        assertEquals(LegalEvalStatus.SCORED, present.rows.single().status)
        assertEquals(true, present.rows.single().groundedAgreement)
        assertEquals(1, present.scored)
    }

    @Test
    fun rejectsCorruptFixtureAndDuplicatePredictions() {
        val anchor = witness(1, 3, "😀", LegalEvalWitnessRole.SUPPORTS)
        val item = case("integrity", LegalEvalLabel.SUPPORTED, _l[anchor])
        val prediction = LegalEvalPrediction(item.propositionCid, LegalEvalLabel.SUPPORTED, _l[anchor])
        val corrupt = LegalNarsEvaluation.score(_l[item], _m[excerpt j "other".encodeToByteArray()], _l[prediction])
        assertEquals(LegalEvalStatus.INVALID_FIXTURE, corrupt.rows.single().status)
        assertEquals(1, corrupt.invalidFixtures)
        val duplicate = LegalNarsEvaluation.score(_l[item], excerpts, _l[prediction, prediction])
        assertEquals(LegalEvalStatus.INVALID_PREDICTION, duplicate.rows.single().status)
        assertEquals(2, duplicate.invalidPredictions)
        assertEquals(0, duplicate.scored)
        assertFailsWith<IllegalArgumentException> {
            LegalNarsEvaluation.score(_l[item, item], excerpts, _l[prediction])
        }
        val unrelated = LegalEvalPrediction(ContentId.of("other-proposition".encodeToByteArray()),
            LegalEvalLabel.SUPPORTED, _l[anchor])
        val mismatch = LegalNarsEvaluation.score(_l[item], excerpts, _l[unrelated])
        assertEquals(LegalEvalStatus.MISSING, mismatch.rows.single().status)
        assertEquals(1, mismatch.invalidPredictions)
    }

    @Test
    fun splitSurrogateAndWrongExcerptCannotGroundAClaim() {
        val anchor = witness(1, 3, "😀", LegalEvalWitnessRole.SUPPORTS)
        val item = case("bad-witness", LegalEvalLabel.SUPPORTED, _l[anchor])
        val split = witness(1, 2, text.substring(1, 2), LegalEvalWitnessRole.SUPPORTS)
        val wrongCid = witness(1, 3, "😀", LegalEvalWitnessRole.SUPPORTS, ContentId.of("other".encodeToByteArray()))
        for (bad in _l[split, wrongCid]) {
            val predicted = LegalEvalPrediction(item.propositionCid, LegalEvalLabel.SUPPORTED, _l[bad])
            val row = LegalNarsEvaluation.score(_l[item], excerpts, _l[predicted]).rows.single()
            assertFalse(row.groundedAgreement == true)
            assertFalse(row.acceptedWitness == true)
        }
    }
}
