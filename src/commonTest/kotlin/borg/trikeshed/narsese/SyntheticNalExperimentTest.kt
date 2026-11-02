package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.CanonicalCbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A synthetic arithmetic and lineage probe, not a legal inference benchmark. */
class SyntheticNalExperimentTest {
    @Test
    fun exportedPreimagesReconstructDecisionsReductionsAndDerivationReceipts() {
        val report = SyntheticNalExperiment.run()
        fun canonicalCid(value: Map<String, Any?>) = ContentId.of(CanonicalCbor.encodeMap(value))
        assertEquals(report.evaluatorCid, canonicalCid(report.evaluatorPreimage))

        assertEquals(2, report.decisions.size)
        assertTrue(report.decisions.any { it.cid == report.independenceDecisionCid })
        for (decision in report.decisions) {
            assertEquals(decision.cid, canonicalCid(decision.preimage))
            assertEquals(decision.first.value, decision.preimage["first"])
            assertEquals(decision.second.value, decision.preimage["second"])
            assertNotEquals(decision.first, decision.second)
        }

        val expected = mapOf(
            "duplicate" to report.duplicate,
            "overlap" to report.overlap,
            "disjointWithoutDecision" to report.disjointWithoutDecision,
            "disjointWithDecision" to report.disjointWithDecision,
            "conflict" to report.conflict,
            "missingBasis" to report.missingBasis,
        )
        assertEquals(expected.keys, report.reductionInputs.keys)
        for ((name, inputs) in report.reductionInputs) {
            assertTrue(inputs.isNotEmpty())
            for (input in inputs) {
                assertEquals(input.cid, ContentId.of(SourceContributionCodec.encode(input)))
            }
            val ids = inputs.map { it.cid }.toSet()
            val decisions = if (name == "overlap" || name == "disjointWithDecision") {
                report.decisions.filter { it.first in ids && it.second in ids }.map {
                    ContributionIndependence(it.first, it.second, it.cid)
                }.also { assertEquals(1, it.size) }
            } else emptyList()
            val replay = reduceContributions(inputs.first().proposition, inputs, decisions)
            val original = expected.getValue(name)
            assertEquals(original.status, replay.status, name)
            assertEquals(original.unique, replay.unique, name)
            assertEquals(original.duplicates, replay.duplicates, name)
            assertEquals(original.evidence, replay.evidence, name)
            assertEquals(original.conflicts, replay.conflicts, name)
            assertEquals(original.decisionReceipts, replay.decisionReceipts, name)
        }

        fun proposition(subject: ContentId, predicate: ContentId) = canonicalCid(mapOf(
            "kind" to "synthetic-nal-implication/1", "subject" to subject.value, "predicate" to predicate.value,
        ))
        val ids = report.terms.keys.toList()
        fun termsOf(contribution: SourceContribution): Pair<ContentId, ContentId> =
            ids.flatMap { subject -> ids.map { predicate -> subject to predicate } }
                .single { (subject, predicate) -> proposition(subject, predicate) == contribution.proposition }
        fun premise(contribution: SourceContribution): PremiseReceipt {
            val (subject, predicate) = termsOf(contribution)
            return PremiseReceipt(contribution.cid, TermIdentity(report.terms.getValue(subject)),
                TermIdentity(report.terms.getValue(predicate)))
        }
        for ((derived, inputs) in listOf(report.deduction to report.deductionPremises,
            report.induction to report.inductionPremises)) {
            assertEquals(derived.propositionCid, proposition(derived.subject, derived.predicate))
            assertEquals(derived.receiptCid, ContentId.of(derived.receiptPreimage.encodeToByteArray()))
            val evidence = EvidenceCoord(derived.positive, derived.negative)
            val reconstructed = when (derived.kind) {
                RuleIdentity.DEDUCTION -> DerivationReceipt.deduction(premise(inputs[0]), premise(inputs[1]), evidence, report.evaluatorCid)
                RuleIdentity.INDUCTION -> DerivationReceipt.induction(premise(inputs[0]), premise(inputs[1]), evidence, report.evaluatorCid)
                else -> error("Unexpected derivation rule")
            }
            assertEquals(derived.receiptCid, reconstructed.canonicalCid)
            assertEquals(TermIdentity(report.terms.getValue(derived.subject)), reconstructed.conclusionSubject)
            assertEquals(TermIdentity(report.terms.getValue(derived.predicate)), reconstructed.conclusionPredicate)
        }
        for ((cid, label) in report.termLabels) {
            assertEquals(cid, canonicalCid(mapOf("kind" to "synthetic-nal-term/1", "value" to label)))
        }
        assertEquals(report.terms.keys, report.termLabels.keys)
    }

    @Test
    fun sourceHashesAndSpansGroundTheHandAuthoredPremises() {
        val report = SyntheticNalExperiment.run()
        assertEquals(ContentId.of(report.sourceText.encodeToByteArray()), report.sourceCid)
        assertEquals(ContentId.of(report.otherSourceText.encodeToByteArray()), report.otherSourceCid)
        assertNotEquals(report.sourceCid, report.otherSourceCid)
        val sources = mapOf(report.sourceCid to report.sourceText, report.otherSourceCid to report.otherSourceText)
        assertEquals(sources.keys, report.spans.map { it.sourceCid }.toSet())
        assertEquals(report.spans.size, report.spans.map { it.occurrenceCid }.toSet().size)
        for (span in report.spans) {
            val source = sources.getValue(span.sourceCid)
            assertTrue(span.begin >= 0 && span.begin < span.end && span.end <= source.length)
            assertEquals(source.substring(span.begin, span.end), span.quote)
            assertEquals("utf16-code-units", span.offsetUnit)
            fun cid(fields: Map<String, Any?>) = ContentId.of(CanonicalCbor.encodeMap(fields))
            assertEquals(span.occurrenceCid, cid(mapOf("kind" to "synthetic-nal-occurrence/1",
                "source" to span.sourceCid.value, "begin" to span.begin, "end" to span.end, "quote" to span.quote)))
            assertEquals(span.propositionCid, cid(mapOf("kind" to "synthetic-nal-implication/1",
                "subject" to span.subjectCid.value, "predicate" to span.predicateCid.value)))
            assertEquals(span.annotationCid, cid(mapOf("kind" to "synthetic-nal-annotation/1",
                "occurrence" to span.occurrenceCid.value, "proposition" to span.propositionCid.value,
                "authority" to "hand-authored-synthetic-fixture")))
        }
        val spansByOccurrence = report.spans.associateBy { it.occurrenceCid }
        for (premise in report.deductionPremises + report.inductionPremises) {
            val span = spansByOccurrence.getValue(premise.occurrence)
            assertEquals(premise.proposition, span.propositionCid)
            assertEquals(listOf(span.sourceCid), premise.basis)
            assertEquals(premise.extractionReceipt, span.annotationCid)
        }
        assertEquals(listOf(report.independenceDecisionCid), report.disjointWithDecision.decisionReceipts)
    }

    @Test
    fun derivationsPreservePremisesAndSourceAndReplayDeterministically() {
        val report = SyntheticNalExperiment.run()
        val replay = SyntheticNalExperiment.run()
        val deduction = report.deduction
        val induction = report.induction

        assertEquals(RuleIdentity.DEDUCTION, deduction.kind)
        assertEquals(RuleIdentity.INDUCTION, induction.kind)
        assertTrue(deduction.positive in 332L..334L)
        assertTrue(induction.positive in 249L..251L)
        assertEquals(0L, deduction.negative)
        assertEquals(0L, induction.negative)
        assertTrue(induction.positive < deduction.positive)
        assertTrue(deduction.status.contains("hypothesis", ignoreCase = true))
        assertTrue(induction.status.contains("hypothesis", ignoreCase = true))

        assertEquals(2, report.deductionPremises.size)
        assertEquals(2, report.inductionPremises.size)
        assertEquals(report.deductionPremises.map { it.cid }, deduction.premiseCids)
        assertEquals(report.inductionPremises.map { it.cid }, induction.premiseCids)
        assertEquals(listOf(report.sourceCid), deduction.basis)
        assertEquals(listOf(report.sourceCid), induction.basis)
        assertTrue(report.deductionPremises.all { it.basis == listOf(report.sourceCid) })
        assertTrue(report.inductionPremises.all { it.basis == listOf(report.sourceCid) })

        assertTrue(report.terms.size >= 5)
        assertEquals(report.terms.size, report.terms.values.toSet().size)
        for (derived in listOf(deduction, induction)) {
            assertTrue(derived.subject in report.terms)
            assertTrue(derived.predicate in report.terms)
            assertNotEquals(derived.subject, derived.predicate)
        }
        assertNotEquals(deduction.subject, induction.subject)
        assertEquals(deduction.predicate, induction.predicate)
        assertNotEquals(deduction.receiptCid, induction.receiptCid)

        assertEquals(report.sourceCid, replay.sourceCid)
        assertEquals(report.terms, replay.terms)
        assertEquals(deduction.receiptCid, replay.deduction.receiptCid)
        assertEquals(induction.receiptCid, replay.induction.receiptCid)
        assertEquals(deduction.premiseCids, replay.deduction.premiseCids)
        assertEquals(induction.premiseCids, replay.induction.premiseCids)
        assertEquals(deduction.basis, replay.deduction.basis)
        assertEquals(induction.basis, replay.induction.basis)
        assertEquals(deduction.positive, replay.deduction.positive)
        assertEquals(induction.positive, replay.induction.positive)
    }

    @Test
    fun duplicateAndUnresolvedSourcesDoNotBecomeIndependentEvidence() {
        val report = SyntheticNalExperiment.run()
        assertEquals(ContributionStatus.READY, report.duplicate.status)
        assertEquals(1, report.duplicate.unique)
        assertEquals(1, report.duplicate.duplicates)
        assertEquals(EvidenceCoord(1_000, 0), report.duplicate.evidence)

        assertEquals(ContributionStatus.UNRESOLVED, report.overlap.status)
        assertNull(report.overlap.evidence)
        assertEquals(ContributionStatus.UNRESOLVED, report.disjointWithoutDecision.status)
        assertNull(report.disjointWithoutDecision.evidence)
        assertEquals(ContributionStatus.READY, report.disjointWithDecision.status)
        assertEquals(EvidenceCoord(2_000, 0), report.disjointWithDecision.evidence)
        assertEquals(1, report.disjointWithDecision.decisionReceipts.size)

        assertEquals(ContributionStatus.CONFLICT, report.conflict.status)
        assertNull(report.conflict.evidence)
        assertTrue(report.conflict.conflicts.isNotEmpty())
        assertEquals(ContributionStatus.UNRESOLVED, report.missingBasis.status)
        assertNull(report.missingBasis.evidence)
    }

    @Test
    fun anEmptyPremiseProducesNoEvidence() {
        val report = SyntheticNalExperiment.run()
        assertEquals(EvidenceCoord.EMPTY, report.emptyPremiseDeduction)
        assertEquals(EvidenceCoord.EMPTY, report.emptyPremiseInduction)
    }
}
