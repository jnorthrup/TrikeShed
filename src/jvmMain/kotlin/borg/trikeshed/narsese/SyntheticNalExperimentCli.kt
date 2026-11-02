package borg.trikeshed.narsese

import borg.trikeshed.parse.jsonOf

/** Prints only results of the offline, hand-authored synthetic NAL experiment. */
object SyntheticNalExperimentCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "SyntheticNalExperimentCli takes no arguments" }
        println(jsonOf(payload(SyntheticNalExperiment.run())))
    }

    /** Render the entire reproducible artifact without relying on mutable standard output. */
    internal fun payload(r: SyntheticNalReport): Map<String, Any?> {
        fun contribution(c: SourceContribution) = mapOf("cid" to c.cid.value,
            "canonicalPreimage" to mapOf("kind" to "source-contribution-v1", "proposition" to c.proposition.value,
                "occurrence" to c.occurrence.value, "extractionReceipt" to c.extractionReceipt.value,
                "basis" to c.basis.map { it.value }, "positive" to c.evidence.positive, "negative" to c.evidence.negative),
            "propositionCid" to c.proposition.value,
            "occurrenceCid" to c.occurrence.value, "annotationCid" to c.extractionReceipt.value,
            "basis" to c.basis.map { it.value }, "positive" to c.evidence.positive, "negative" to c.evidence.negative)
        fun derivation(d: SyntheticNalDerivation): Map<String, Any?> {
            val truth = Nal.truthOf(EvidenceCoord(d.positive, d.negative))
            return mapOf("rule" to d.kind.name, "subjectCid" to d.subject.value, "predicateCid" to d.predicate.value,
                "propositionCid" to d.propositionCid.value, "positive" to d.positive, "negative" to d.negative,
                "frequency" to truth.frequency, "nalConfidence" to truth.confidence,
                "receiptCid" to d.receiptCid.value, "premiseCids" to d.premiseCids.map { it.value },
                "receiptPreimageUtf8" to d.receiptPreimage, "evaluatorCid" to r.evaluatorCid.value,
                "propositionPreimage" to mapOf("kind" to "synthetic-nal-implication/1",
                    "subject" to d.subject.value, "predicate" to d.predicate.value),
                "sourceBasis" to d.basis.map { it.value }, "status" to d.status)
        }
        fun reduction(c: ContributionResult) = mapOf("status" to c.status.name, "unique" to c.unique,
            "duplicates" to c.duplicates, "positive" to c.evidence?.positive, "negative" to c.evidence?.negative,
            "conflicts" to c.conflicts.map { it.value }, "decisionReceipts" to c.decisionReceipts.map { it.value })
        return mapOf(
            "kind" to "synthetic-nal-experiment/1",
            "scope" to "invented source text; hand-authored annotations; hypotheses only; no legal conclusion or live bag mutation",
            "encoding" to "source text and receiptPreimageUtf8 are UTF-8; canonicalPreimage and other preimage maps use canonical CBOR with integer-valued numbers encoded as integers",
            "independence" to "not inferred; the explicitly labeled synthetic decision is test data, not authority",
            "sources" to listOf(mapOf("cid" to r.sourceCid.value, "text" to r.sourceText),
                mapOf("cid" to r.otherSourceCid.value, "text" to r.otherSourceText)),
            "spans" to r.spans.map { mapOf("sourceCid" to it.sourceCid.value, "begin" to it.begin, "end" to it.end,
                "offsetUnit" to it.offsetUnit, "endExclusive" to true, "quote" to it.quote,
                "occurrenceCid" to it.occurrenceCid.value, "occurrencePreimage" to mapOf(
                    "kind" to "synthetic-nal-occurrence/1", "source" to it.sourceCid.value,
                    "begin" to it.begin, "end" to it.end, "quote" to it.quote),
                "propositionCid" to it.propositionCid.value, "propositionPreimage" to mapOf(
                    "kind" to "synthetic-nal-implication/1", "subject" to it.subjectCid.value, "predicate" to it.predicateCid.value),
                "annotationCid" to it.annotationCid.value, "annotationPreimage" to mapOf(
                    "kind" to "synthetic-nal-annotation/1", "occurrence" to it.occurrenceCid.value,
                    "proposition" to it.propositionCid.value, "authority" to "hand-authored-synthetic-fixture")) },
            "terms" to r.terms.entries.sortedBy { it.key.value }.map {
                mapOf("cid" to it.key.value, "localId" to it.value, "syntheticLabel" to r.termLabels.getValue(it.key),
                    "canonicalPreimage" to mapOf("kind" to "synthetic-nal-term/1", "value" to r.termLabels.getValue(it.key))) },
            "evaluator" to mapOf("cid" to r.evaluatorCid.value, "canonicalPreimage" to r.evaluatorPreimage),
            "deductionPremises" to r.deductionPremises.map(::contribution),
            "inductionPremises" to r.inductionPremises.map(::contribution),
            "deduction" to derivation(r.deduction), "induction" to derivation(r.induction),
            "syntheticDecisionCid" to r.independenceDecisionCid.value,
            "decisions" to r.decisions.map { mapOf("cid" to it.cid.value, "canonicalPreimage" to it.preimage) },
            "reductionInputs" to r.reductionInputs.mapValues { (_, values) -> values.map { it.cid.value } },
            "contributions" to (r.deductionPremises + r.inductionPremises + r.reductionInputs.values.flatten())
                .distinctBy { it.cid }.sortedBy { it.cid.value }.map(::contribution),
            "duplicate" to reduction(r.duplicate), "overlap" to reduction(r.overlap),
            "disjointWithoutDecision" to reduction(r.disjointWithoutDecision),
            "disjointWithSyntheticDecision" to reduction(r.disjointWithDecision),
            "conflict" to reduction(r.conflict), "missingBasis" to reduction(r.missingBasis),
            "emptyPremiseDeduction" to mapOf("positive" to r.emptyPremiseDeduction.positive, "negative" to r.emptyPremiseDeduction.negative),
            "emptyPremiseInduction" to mapOf("positive" to r.emptyPremiseInduction.positive, "negative" to r.emptyPremiseInduction.negative),
        )
    }
}
