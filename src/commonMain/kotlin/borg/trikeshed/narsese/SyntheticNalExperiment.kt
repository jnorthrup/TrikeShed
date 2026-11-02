package borg.trikeshed.narsese

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId

/** Exact synthetic source occurrence and the proposition the hand-authored fixture assigns it. */
data class SyntheticNalSpan(val sourceCid: ContentId, val begin: Int, val end: Int, val quote: String,
    val occurrenceCid: ContentId, val propositionCid: ContentId, val subjectCid: ContentId,
    val predicateCid: ContentId, val annotationCid: ContentId, val offsetUnit: String = "utf16-code-units")

/** A numerical NAL result with both full term identities and the original premise identities. */
data class SyntheticNalDerivation(val kind: RuleIdentity, val subject: ContentId, val predicate: ContentId,
    val propositionCid: ContentId, val positive: Long, val negative: Long, val receiptCid: ContentId,
    val premiseCids: List<ContentId>, val basis: List<ContentId>, val status: String, val receiptPreimage: String)

/** Fixture-declared decision only; neither its CID nor its preimage authenticates independence. */
data class SyntheticNalDecision(val first: ContentId, val second: ContentId) {
    val preimage: Map<String, Any?> get() = mapOf("kind" to "synthetic-independence-decision/1",
        "first" to first.value, "second" to second.value, "authority" to "hand-authored-synthetic-fixture")
    val cid: ContentId get() = ContentId.of(CanonicalCbor.encodeMap(preimage))
}

data class SyntheticNalReport(
    val sourceText: String, val sourceCid: ContentId, val otherSourceText: String, val otherSourceCid: ContentId,
    val spans: List<SyntheticNalSpan>, val terms: Map<ContentId, Long>, val termLabels: Map<ContentId, String>,
    val deductionPremises: List<SourceContribution>, val inductionPremises: List<SourceContribution>,
    val deduction: SyntheticNalDerivation, val induction: SyntheticNalDerivation,
    val evaluatorPreimage: Map<String, Any?>, val evaluatorCid: ContentId,
    val reductionInputs: Map<String, List<SourceContribution>>, val decisions: List<SyntheticNalDecision>,
    val independenceDecisionCid: ContentId, val duplicate: ContributionResult, val overlap: ContributionResult,
    val disjointWithoutDecision: ContributionResult, val disjointWithDecision: ContributionResult,
    val conflict: ContributionResult, val missingBasis: ContributionResult,
    val emptyPremiseDeduction: EvidenceCoord, val emptyPremiseInduction: EvidenceCoord,
)

/**
 * Deterministic offline NAL experiment over invented statements, not legal text.
 * Source quotations and symbolic mappings are authored by this fixture, not inferred
 * by NLP. Numeric outputs are NAL evidence, not calibrated probabilities or legal
 * conclusions. Results never enter a belief bag, review ledger, or live rule set.
 *
 * Full CIDs are authoritative within this experiment. Receipt APIs use Long terms,
 * so a bijective per-run dictionary assigns sorted full CIDs to small integers; no
 * angular routing address, truncated hash, or legacy pipe-delimited rule ID is used.
 * A derivation is dependent on its premise bases and is never minted as a new source
 * observation. The one independence decision below is deliberately synthetic data,
 * not an authentication mechanism or evidence that real documents are independent.
 */
object SyntheticNalExperiment {
    fun run(): SyntheticNalReport {
        fun cid(value: Map<String, Any?>): ContentId = ContentId.of(CanonicalCbor.encodeMap(value))
        fun term(value: String) = cid(mapOf("kind" to "synthetic-nal-term/1", "value" to value))
        fun proposition(subject: ContentId, predicate: ContentId) = cid(mapOf(
            "kind" to "synthetic-nal-implication/1", "subject" to subject.value, "predicate" to predicate.value))
        val alpha = term("alpha"); val beta = term("beta"); val gamma = term("gamma")
        val delta = term("delta"); val epsilon = term("epsilon")
        val termIds = listOf(alpha, beta, gamma, delta, epsilon).sortedBy { it.value }
            .mapIndexed { i, id -> id to (i + 1L) }.toMap()

        val source = "ALPHA implies BETA.\nBETA implies GAMMA.\nEPSILON implies GAMMA.\n" +
            "EPSILON implies DELTA.\nALPHA implies BETA."
        val otherSource = "Another synthetic record says ALPHA implies BETA."
        val sourceCid = ContentId.of(source.encodeToByteArray())
        val otherSourceCid = ContentId.of(otherSource.encodeToByteArray())
        val spans = ArrayList<SyntheticNalSpan>()

        fun contribution(text: String, textCid: ContentId, quote: String, startAt: Int,
            subject: ContentId, predicate: ContentId): SourceContribution {
            require(ContentId.of(text.encodeToByteArray()) == textCid)
            val begin = text.indexOf(quote, startAt)
            val end = begin + quote.length
            require(DocumentQuotationCheck.exactSpan(text, begin, end, quote)) { "Synthetic quotation does not match" }
            val propositionCid = proposition(subject, predicate)
            val occurrenceCid = cid(mapOf("kind" to "synthetic-nal-occurrence/1", "source" to textCid.value,
                "begin" to begin, "end" to end, "quote" to quote))
            val extractionCid = cid(mapOf("kind" to "synthetic-nal-annotation/1",
                "occurrence" to occurrenceCid.value, "proposition" to propositionCid.value,
                "authority" to "hand-authored-synthetic-fixture"))
            spans.add(SyntheticNalSpan(textCid, begin, end, quote, occurrenceCid, propositionCid,
                subject, predicate, extractionCid))
            return SourceContribution(propositionCid, occurrenceCid, extractionCid, listOf(textCid), Nal.observe(true))
        }

        val p1 = contribution(source, sourceCid, "ALPHA implies BETA.", 0, alpha, beta)
        val p2 = contribution(source, sourceCid, "BETA implies GAMMA.", 0, beta, gamma)
        val p3 = contribution(source, sourceCid, "EPSILON implies GAMMA.", 0, epsilon, gamma)
        val p4 = contribution(source, sourceCid, "EPSILON implies DELTA.", 0, epsilon, delta)
        val repeated = contribution(source, sourceCid, "ALPHA implies BETA.", source.indexOf('\n') + 1, alpha, beta)
        val separate = contribution(otherSource, otherSourceCid, "ALPHA implies BETA.", 0, alpha, beta)
        val evaluatorPreimage: Map<String, Any?> = mapOf("kind" to "synthetic-nal-experiment/1", "method" to "nal-truth-functions")
        val evaluator = cid(evaluatorPreimage)

        fun premise(c: SourceContribution, subject: ContentId, predicate: ContentId): PremiseReceipt {
            require(c.proposition == proposition(subject, predicate)) { "Premise proposition and full terms differ" }
            return PremiseReceipt(c.cid, TermIdentity(termIds.getValue(subject)), TermIdentity(termIds.getValue(predicate)))
        }
        val d1 = premise(p1, alpha, beta); val d2 = premise(p2, beta, gamma)
        val i1 = premise(p3, epsilon, gamma); val i2 = premise(p4, epsilon, delta)
        val deductionEvidence = Nal.deduce(Nal.truthOf(p2.evidence), Nal.truthOf(p1.evidence))
        val inductionEvidence = Nal.induce(Nal.truthOf(p3.evidence), Nal.truthOf(p4.evidence))
        val deductionReceipt = DerivationReceipt.deduction(d1, d2, deductionEvidence, evaluator)
        val inductionReceipt = DerivationReceipt.induction(i1, i2, inductionEvidence, evaluator)

        fun derivation(receipt: DerivationReceipt, expectedSubject: ContentId, expectedPredicate: ContentId,
            inputs: List<SourceContribution>): SyntheticNalDerivation {
            require(receipt.conclusionSubject == TermIdentity(termIds.getValue(expectedSubject)) &&
                receipt.conclusionPredicate == TermIdentity(termIds.getValue(expectedPredicate)))
            require(receipt.premises.a == inputs[0].cid && receipt.premises.b == inputs[1].cid)
            // Match the exact UTF-8 preimage defined by DerivationReceipt, including ordered premises.
            val preimage = buildString {
                append(receipt.ruleId.name.length).append(':').append(receipt.ruleId.name).append(';')
                append(receipt.conclusionSubject.id).append(';').append(receipt.conclusionPredicate.id).append(';')
                append(receipt.premises.a.hex).append(';').append(receipt.premises.b.hex).append(';')
                append(receipt.evidence.packed).append(';').append(receipt.evaluatorCid.hex).append(';')
            }
            require(ContentId.of(preimage.encodeToByteArray()) == receipt.canonicalCid) { "Receipt preimage differs" }
            return SyntheticNalDerivation(receipt.ruleId, expectedSubject, expectedPredicate,
                proposition(expectedSubject, expectedPredicate), receipt.evidence.positive, receipt.evidence.negative,
                receipt.canonicalCid, inputs.map { it.cid }, inputs.flatMap { it.basis }.distinct().sortedBy { it.value },
                "synthetic-hypothesis", preimage)
        }

        val declared = SyntheticNalDecision(p1.cid, separate.cid)
        val overlapDeclared = SyntheticNalDecision(p1.cid, repeated.cid)
        val decision = ContributionIndependence(declared.first, declared.second, declared.cid)
        val overlapDecision = ContributionIndependence(overlapDeclared.first, overlapDeclared.second, overlapDeclared.cid)
        val inputs = mapOf("duplicate" to listOf(p1, p1), "overlap" to listOf(p1, repeated),
            "disjointWithoutDecision" to listOf(p1, separate), "disjointWithDecision" to listOf(p1, separate),
            "conflict" to listOf(p1, p1.copy(evidence = Nal.observe(false))),
            "missingBasis" to listOf(p1.copy(basis = emptyList())))
        return SyntheticNalReport(source, sourceCid, otherSource, otherSourceCid, spans.toList(), termIds,
            mapOf(alpha to "alpha", beta to "beta", gamma to "gamma", delta to "delta", epsilon to "epsilon"),
            listOf(p1, p2), listOf(p3, p4),
            derivation(deductionReceipt, alpha, gamma, listOf(p1, p2)),
            derivation(inductionReceipt, delta, gamma, listOf(p3, p4)), evaluatorPreimage, evaluator, inputs,
            listOf(declared, overlapDeclared), declared.cid,
            reduceContributions(p1.proposition, inputs.getValue("duplicate"), emptyList()),
            reduceContributions(p1.proposition, inputs.getValue("overlap"), listOf(overlapDecision)),
            reduceContributions(p1.proposition, inputs.getValue("disjointWithoutDecision"), emptyList()),
            reduceContributions(p1.proposition, inputs.getValue("disjointWithDecision"), listOf(decision)),
            reduceContributions(p1.proposition, inputs.getValue("conflict"), emptyList()),
            reduceContributions(p1.proposition, inputs.getValue("missingBasis"), emptyList()),
            Nal.deduce(Nal.truthOf(EvidenceCoord.EMPTY), Nal.truthOf(p1.evidence)),
            Nal.induce(Nal.truthOf(EvidenceCoord.EMPTY), Nal.truthOf(p4.evidence)))
    }
}
