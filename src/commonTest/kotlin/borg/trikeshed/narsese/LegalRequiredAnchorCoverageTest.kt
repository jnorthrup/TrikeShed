package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import kotlin.test.*

class LegalRequiredAnchorCoverageTest {
    private val text = "A Finch is an Agent. An Agent is an Entity."
    private val bytes = text.encodeToByteArray()
    private val source = ContentId.of(bytes)
    private val first = LegalEvalWitness(source, 0, 20, text.substring(0, 20), LegalEvalWitnessRole.SUPPORTS)
    private val second = LegalEvalWitness(source, 21, text.length, text.substring(21), LegalEvalWitnessRole.SUPPORTS)
    private val anchors = _l[first, second]
    private val case = LegalEvalCase("S2", "Finch is a subclass of Entity.", source, source,
        LegalEvalLabel.SUPPORTED, anchors, anchors)

    private fun score(c: LegalEvalCase = case, witnesses: List<LegalEvalWitness> = anchors,
        label: LegalEvalLabel = LegalEvalLabel.SUPPORTED): LegalEvalReport =
        LegalNarsEvaluation.score(_l[c], _m[source j bytes], _l[LegalEvalPrediction(c.propositionCid, label, witnesses)])

    @Test fun oneSupportingQuoteDoesNotCoverBothRequiredAnchors() {
        val report = score(witnesses = _l[first])
        val row = report.rows.single()
        assertEquals(true, row.groundedAgreement)
        val coverage = assertNotNull(row.requiredAnchorCoverage)
        assertEquals(true, coverage.acceptedQuotes)
        assertEquals(2, coverage.requiredCount)
        assertEquals(1, coverage.matchedCount)
        assertEquals(_l[second], coverage.missingAnchors)
        assertEquals(false, coverage.complete)
        assertEquals(1, report.requiredAnchorCases)
        assertEquals(1, report.requiredAnchorComparableCases)
        assertEquals(0, report.completeRequiredAnchorCoverageCases)
    }

    @Test fun allExactAnchorsCoverTheDeclarationIndependentlyOfLabelAgreement() {
        val complete = score().rows.single()
        assertEquals(true, complete.groundedAgreement)
        assertEquals(true, complete.requiredAnchorCoverage?.complete)
        val wrongLabel = score(label = LegalEvalLabel.UNKNOWN)
        val row = wrongLabel.rows.single()
        assertEquals(false, row.groundedAgreement)
        assertEquals(false, row.acceptedWitness)
        assertEquals(true, row.requiredAnchorCoverage?.complete)
        assertEquals(2, row.requiredAnchorCoverage?.matchedCount)
        assertEquals(emptyList(), row.requiredAnchorCoverage?.missingAnchors)
        assertEquals(1, wrongLabel.completeRequiredAnchorCoverageCases)
    }

    @Test fun undeclaredRequirementsPreserveIdentityAndLegacyAgreement() {
        val legacy = case.copy(requiredAnchors = null)
        assertEquals(case.propositionCid, legacy.propositionCid)
        val report = score(legacy, _l[first])
        assertEquals(true, report.rows.single().groundedAgreement)
        assertNull(report.rows.single().requiredAnchorCoverage)
        assertEquals(0, report.requiredAnchorCases)
        assertEquals(0, report.requiredAnchorComparableCases)
    }

    @Test fun missingAndDuplicatePredictionsHaveNoCoverageComparison() {
        val missing = LegalNarsEvaluation.score(_l[case], _m[source j bytes], emptyList())
        val prediction = LegalEvalPrediction(case.propositionCid, LegalEvalLabel.SUPPORTED, anchors)
        val invalid = LegalNarsEvaluation.score(_l[case], _m[source j bytes], _l[prediction, prediction])
        assertEquals(LegalEvalStatus.MISSING, missing.rows.single().status)
        assertEquals(LegalEvalStatus.INVALID_PREDICTION, invalid.rows.single().status)
        assertEquals(2, invalid.invalidPredictions)
        for (report in _l[missing, invalid]) {
            assertEquals(1, report.requiredAnchorCases)
            assertEquals(0, report.requiredAnchorComparableCases)
            val coverage = assertNotNull(report.rows.single().requiredAnchorCoverage)
            assertEquals(2, coverage.requiredCount)
            assertNull(coverage.acceptedQuotes)
            assertNull(coverage.matchedCount)
            assertNull(coverage.missingAnchors)
            assertNull(coverage.complete)
        }
    }

    @Test fun malformedDuplicateSpuriousAndWrongRoleQuotesCannotEarnCoverage() {
        val spurious = first.copy(end = text.length, quote = text)
        val malformed = first.copy(quote = "wrong quote")
        val wrongSource = first.copy(excerptCid = ContentId.of("other".encodeToByteArray()))
        val wrongRole = second.copy(role = LegalEvalWitnessRole.CONTEXT)
        val tooMany = (0 until 65).toSeries().α { first }.toList()
        for (witnesses in _l[_l[first, second, spurious], _l[first, second, malformed],
            _l[first, second, wrongSource], _l[first, second, wrongRole], _l[first, second, first], tooMany]) {
            val report = score(witnesses = witnesses)
            val coverage = assertNotNull(report.rows.single().requiredAnchorCoverage)
            assertEquals(false, coverage.acceptedQuotes)
            assertNull(coverage.matchedCount)
            assertNull(coverage.missingAnchors)
            assertNull(coverage.complete)
            assertEquals(1, report.requiredAnchorCases)
            assertEquals(0, report.requiredAnchorComparableCases)
            assertEquals(0, report.completeRequiredAnchorCoverageCases)
        }
    }

    @Test fun emptyDuplicateForeignAndWrongRoleRequirementsAreInvalidFixtures() {
        val foreign = first.copy(excerptCid = ContentId.of("foreign".encodeToByteArray()))
        for (required in _l[emptyList(), _l[first, first], _l[foreign],
            _l[first.copy(role = LegalEvalWitnessRole.CONTEXT)], (0 until 65).toSeries().α { first }.toList()]) {
            val report = score(case.copy(requiredAnchors = required))
            assertEquals(LegalEvalStatus.INVALID_FIXTURE, report.rows.single().status)
            assertEquals(1, report.invalidFixtures)
            assertEquals(0, report.requiredAnchorCases)
            assertNull(report.rows.single().requiredAnchorCoverage)
        }
    }

    @Test fun explicitUnknownWithNoQuotesIsNotVacuousRequiredContextCoverage() {
        val context = first.copy(role = LegalEvalWitnessRole.CONTEXT)
        val unknown = case.copy(expected = LegalEvalLabel.UNKNOWN, anchors = _l[context], requiredAnchors = _l[context])
        val row = score(unknown, emptyList(), LegalEvalLabel.UNKNOWN).rows.single()
        assertEquals(true, row.groundedAgreement)
        assertEquals(true, row.requiredAnchorCoverage?.acceptedQuotes)
        assertEquals(false, row.requiredAnchorCoverage?.complete)
        assertEquals(0, row.requiredAnchorCoverage?.matchedCount)
        assertEquals(_l[context], row.requiredAnchorCoverage?.missingAnchors)
        assertNull(score(unknown.copy(requiredAnchors = null), emptyList(), LegalEvalLabel.UNKNOWN)
            .rows.single().requiredAnchorCoverage)
    }

    @Test fun repeatedTextAtAnotherOffsetCannotCoverTheRequiredOccurrence() {
        val repeated = "A. A.".encodeToByteArray()
        val cid = ContentId.of(repeated)
        val a = first.copy(excerptCid = cid, begin = 0, end = 2, quote = "A.")
        val b = a.copy(begin = 3, end = 5)
        val c = case.copy(claimedOriginalCid = cid, excerptCid = cid, anchors = _l[a, b], requiredAnchors = _l[b])
        val row = LegalNarsEvaluation.score(_l[c], _m[cid j repeated],
            _l[LegalEvalPrediction(c.propositionCid, LegalEvalLabel.SUPPORTED, _l[a])]).rows.single()
        assertEquals(true, row.groundedAgreement)
        assertEquals(false, row.requiredAnchorCoverage?.complete)
        assertEquals(_l[b], row.requiredAnchorCoverage?.missingAnchors)
    }
}
