package borg.trikeshed.narsese

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.rdf.EnglishObservationGraph

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

enum class RetainedNalStatus { EVALUATED, UNSUPPORTED, BOUNDED }
data class RetainedNalIssue(val code: String, val sentenceIndex: Int? = null)
data class RetainedNalOccurrence(val span: SyntheticNalSpan, val sentenceIndex: Int,
    val sentenceIri: String, val subjectNodeIri: String, val predicateNodeIri: String,
    val objectNodeIri: String, val predicateLemma: String, val recognizerRuleCid: ContentId,
    val recognizerRulePreimage: String,
    val occurrencePreimage: Map<String, Any?>, val annotationPreimage: Map<String, Any?>)
data class RetainedNalPremise(val subject: ContentId, val predicate: ContentId,
    val contribution: SourceContribution, val occurrences: Series<RetainedNalOccurrence>)
data class RetainedNalReport(val status: RetainedNalStatus, val frameCid: ContentId,
    val framePreimage: Map<String, Any?>, val inspectionCid: ContentId, val originalCid: ContentId,
    val sourceCid: ContentId, val graphIri: String, val sourceText: String,
    val evaluatorCid: ContentId, val evaluatorPreimage: Map<String, Any?>,
    val terms: Map<ContentId, Long>, val termLabels: Map<ContentId, String>,
    val premises: Series<RetainedNalPremise>, val derivations: Series<SyntheticNalDerivation>,
    val issues: Series<RetainedNalIssue>, val attemptedPairs: Int) {
    val unitPolicy: String get() = SyntheticNalExperiment.RETAINED_UNIT_POLICY

    fun toMap(): Map<String, Any?> = _m[
        "kind" j "retained-nal-implication-report/1", "profile" j SyntheticNalExperiment.RETAINED_PROFILE,
        "status" j status.name, "scope" j "recorded-English atomic-symbol hypotheses; no theorem, calibrated probability, review or live admission",
        "mode" j "historical-snapshot", "currentness" j "not-checked", "parserExecution" j "not-reverified",
        "unitPolicy" j unitPolicy, "admission" j "not-performed", "independence" j "not-established; exact shared source ancestry retained",
        "numericPolicy" j _m["unitScale" j Nal.UNIT, "truthFunction" j "Nal.truthOf",
            "horizonObservations" j 1, "horizonMilliEvidence" j Nal.UNIT,
            "meaning" j "NAL evidence coordinates, not parser confidence or calibrated truth probability"],
        "frameCid" j frameCid.value, "framePreimage" j framePreimage,
        "source" j _m["inspectionCid" j inspectionCid.value, "originalCid" j originalCid.value,
            "extractedTextCid" j sourceCid.value, "graphIri" j graphIri, "text" j sourceText,
            "graphMeaning" j "existing inspection resource namespace; no quad query executed by this experiment",
            "offsetUnit" j "utf16-code-units", "endExclusive" j true],
        "evaluatorCid" j evaluatorCid.value, "evaluatorPreimage" j evaluatorPreimage,
        "limits" j _m["clauses" j 64, "orderedPairs" j 4096, "inferenceDepth" j 1],
        "attemptedPairs" j attemptedPairs,
        "issues" j issues.view.map { _m["code" j it.code, "sentenceIndex" j it.sentenceIndex] },
        "terms" j terms.entries.sortedBy { it.value }.map { (cid, local) ->
            _m["cid" j cid.value, "localId" j local, "surface" j termLabels.getValue(cid),
                "preimage" j retainedAtom(frameCid, termLabels.getValue(cid))]
        },
        "premises" j premises.view.map { p ->
            val c = p.contribution
            _m["subjectCid" j p.subject.value, "predicateCid" j p.predicate.value,
                "propositionCid" j c.proposition.value, "propositionPreimage" j retainedProposition(frameCid, p.subject, p.predicate),
                "contributionCid" j c.cid.value, "contributionPreimage" j CanonicalCbor.decodeMapBounded(SourceContributionCodec.encode(c), 16_384),
                "positive" j c.evidence.positive, "negative" j c.evidence.negative, "basis" j c.basis.map { it.value },
                "occurrences" j p.occurrences.view.map { o ->
                    val s = o.span
                    _m["sourceCid" j s.sourceCid.value, "begin" j s.begin, "end" j s.end, "quote" j s.quote,
                        "occurrenceCid" j s.occurrenceCid.value, "occurrencePreimage" j o.occurrencePreimage,
                        "annotationCid" j s.annotationCid.value, "annotationPreimage" j o.annotationPreimage,
                        "sentenceIndex" j o.sentenceIndex, "sentenceIri" j o.sentenceIri,
                        "subjectNodeIri" j o.subjectNodeIri, "predicateNodeIri" j o.predicateNodeIri,
                        "objectNodeIri" j o.objectNodeIri, "predicateLemma" j o.predicateLemma,
                        "recognizerRuleCid" j o.recognizerRuleCid.value, "recognizerRulePreimageUtf8" j o.recognizerRulePreimage]
                }]
        },
        "derivations" j derivations.view.map { d ->
            val truth = Nal.truthOf(EvidenceCoord(d.positive, d.negative))
            _m["rule" j d.kind.name, "status" j d.status, "subjectCid" j d.subject.value,
                "predicateCid" j d.predicate.value, "propositionCid" j d.propositionCid.value,
                "propositionPreimage" j retainedProposition(frameCid, d.subject, d.predicate),
                "positive" j d.positive, "negative" j d.negative, "nalFrequency" j truth.frequency,
                "nalConfidence" j truth.confidence, "receiptCid" j d.receiptCid.value,
                "receiptPreimageUtf8" j d.receiptPreimage, "premiseCids" j d.premiseCids.map { it.value },
                "basis" j d.basis.map { it.value }]
        }]
}

private fun retainedAtom(frame: ContentId, surface: String): Map<String, Any?> =
    _m["kind" j "retained-nal-atom/1", "frameCid" j frame.value, "surface" j surface]
private fun retainedProposition(frame: ContentId, subject: ContentId, predicate: ContentId): Map<String, Any?> =
    _m["kind" j "retained-nal-implication/1", "frameCid" j frame.value,
        "subject" j subject.value, "predicate" j predicate.value]

/**
 * Offline NAL experiments, with the fixed invented fixture and a separate bounded
 * retained-English overload. Only the latter reads source-checked observations through
 * the existing recognizer. Neither authenticates parser execution or establishes source
 * truth. Numeric outputs are NAL evidence, not calibrated probabilities or legal
 * conclusions. Results never enter a belief bag, review ledger, or live rule set.
 *
 * Full CIDs are authoritative within this experiment. Receipt APIs use Long terms,
 * so a bijective per-run dictionary assigns sorted full CIDs to small integers; no
 * angular routing address, truncated hash, or legacy pipe-delimited rule ID is used.
 * A derivation is dependent on its premise bases and is never minted as a new source
 * observation. Only the zero-argument run() fixture authors source mappings and an
 * independence decision. Its decision is synthetic data, not an authentication
 * mechanism or evidence that real documents are independent.
 */
object SyntheticNalExperiment {
    const val RETAINED_PROFILE = "retained-English-positive-atomic-implication/1"
    const val RETAINED_UNIT_POLICY = "recognizer-structural-milli-evidence/1; retain fixed positive weight unchanged, not parser probability or source truth; no duplicate or derived observation units"

    /**
     * One verified historical English frame, one inference level through the existing NAL
     * primitives. Any unsupported sentence refuses the WHOLE frame before contributions exist.
     * This does not authenticate parser execution or publish source claims into a live system.
     */
    fun run(snapshot: EnglishObservationGraph.Snapshot): RetainedNalReport {
        fun cid(value: Map<String, Any?>) = ContentId.of(CanonicalCbor.encodeMap(value))
        val source = snapshot.source
        val frame = _m["kind" j "retained-nal-frame/1", "profile" j RETAINED_PROFILE,
            "inspectionCid" j snapshot.receiptCid.value, "originalCid" j source.originalCid.value,
            "sourceCid" j source.extractedTextCid.value]
        val frameCid = cid(frame)
        val evaluator = _m["kind" j "retained-nal-evaluator/1", "frameCid" j frameCid.value,
            "unitPolicy" j RETAINED_UNIT_POLICY, "rules" j _l["DEDUCTION", "INDUCTION"], "inferenceDepth" j 1]
        val evaluatorCid = cid(evaluator)
        fun report(status: RetainedNalStatus, issues: Series<RetainedNalIssue>,
            terms: Map<ContentId, Long> = emptyMap(), labels: Map<ContentId, String> = emptyMap(),
            premises: Series<RetainedNalPremise> = emptySeriesOf(),
            derivations: Series<SyntheticNalDerivation> = emptySeriesOf(), pairs: Int = 0) =
            RetainedNalReport(status, frameCid, frame, snapshot.receiptCid, source.originalCid,
                source.extractedTextCid, snapshot.graph, source.text, evaluatorCid, evaluator,
                terms, labels, premises, derivations, issues, pairs)
        val range = snapshot.inventory["range"] as? Map<*, *>
        fun number(raw: Any?) = (raw as? Number)?.toDouble()
        if (range == null || range["complete"] != true || number(range["begin"]) != 0.0 ||
            number(range["end"]) != source.text.length.toDouble() || snapshot.parsed.text != source.text)
            return report(RetainedNalStatus.UNSUPPORTED, s_[RetainedNalIssue("partial-frame")])
        val coverage = snapshot.inventory["coverage"] as? Map<*, *>
        if (snapshot.legacyIdentity || coverage == null || coverage["parserNodeIdentityReported"] != true ||
            coverage["parserDependencyNodeIdentity"] != "token-index+copy-count/1")
            return report(RetainedNalStatus.UNSUPPORTED, s_[RetainedNalIssue("unreported-dependency-identity")])
        val runtime = snapshot.parsed.metadata?.runtime.orEmpty()
        if (runtime["parserNormalization"] != "identity/1")
            return report(RetainedNalStatus.UNSUPPORTED, s_[RetainedNalIssue("parser-normalization")])
        if (runtime["sourceTextCid"] != source.extractedTextCid.value || runtime["parserInputCid"] != source.extractedTextCid.value)
            return report(RetainedNalStatus.UNSUPPORTED, s_[RetainedNalIssue("parser-input-identity")])
        val sentences = snapshot.parsed.sentences
        if (sentences.size > 64) return report(RetainedNalStatus.BOUNDED, s_[RetainedNalIssue("clause-limit")])
        if (sentences.size == 0) return report(RetainedNalStatus.UNSUPPORTED, s_[RetainedNalIssue("empty-frame")])
        val copied = sentences.view.filter { s -> s.dependencies.view.any { it.governorCopy != 0 || it.dependentCopy != 0 } }
        if (copied.isNotEmpty()) return report(RetainedNalStatus.UNSUPPORTED,
            copied α { RetainedNalIssue("copied-node", it.index) })
        val recognized = NlpcoreAxiomatics.recognize(snapshot.parsed, source.extractedTextCid.value)
        val bySentence = recognized.view.groupBy { it.sentenceIndex }
        val issues = mutableListOf<RetainedNalIssue>()
        val atoms = Regex("[A-Z][A-Z0-9_]{0,63}")
        for (sentence in sentences.view) {
            val reading = bySentence[sentence.index]?.singleOrNull()
            val code = when {
                reading == null -> "unaccounted-sentence"
                reading.predicate !in _s["imply", "entail"] -> "unsupported-predicate"
                !atoms.matches(reading.antecedent) || !atoms.matches(reading.consequent) -> "atomic-surfaces-required"
                reading.rule.copula != NalCopula.IMPLICATION || reading.rule.evidence.negative != 0L || reading.rule.evidence.positive == 0L -> "nonpositive-reading"
                else -> null
            }
            if (code != null) issues.add(RetainedNalIssue(code, sentence.index))
        }
        if (issues.isNotEmpty()) return report(RetainedNalStatus.UNSUPPORTED, issues.toSeries())

        val labels = recognized.view.flatMap { _l[it.antecedent, it.consequent] }.distinct()
            .associateBy { cid(retainedAtom(frameCid, it)) }
        val terms = labels.keys.sortedBy { it.value }.withIndex().associate { (it.value j (it.index + 1L)).pair }
        val basis = _l[source.originalCid, source.extractedTextCid].distinct().sortedBy { it.value }
        val occurrences = linkedMapOf<ContentId, MutableList<RetainedNalOccurrence>>()
        val readings = linkedMapOf<ContentId, NlpcoreAxiom>()
        for (reading in recognized.view) {
            val subject = cid(retainedAtom(frameCid, reading.antecedent))
            val predicate = cid(retainedAtom(frameCid, reading.consequent))
            val proposition = cid(retainedProposition(frameCid, subject, predicate))
            val sentence = sentences.view.single { it.index == reading.sentenceIndex }
            val root = sentence.dependencies.view.single { it.governor == 0 && it.relation.lowercase() == "root" }
            val subj = sentence.dependencies.view.single { it.governor == root.dependent && it.relation.lowercase() == "nsubj" }
            val obj = sentence.dependencies.view.single { it.governor == root.dependent && it.relation.lowercase() in _s["obj", "dobj"] }
            val sentenceIri = snapshot.graph + "/sentence/${sentence.index}"
            val quote = source.text.substring(reading.begin, reading.end)
            val occurrence = _m["kind" j "retained-nal-occurrence/1", "frameCid" j frameCid.value,
                "inspectionCid" j snapshot.receiptCid.value, "sourceCid" j source.extractedTextCid.value,
                "sentenceIndex" j sentence.index, "begin" j reading.begin, "end" j reading.end, "quote" j quote]
            val annotation = _m["kind" j "retained-nal-annotation/1", "occurrenceCid" j cid(occurrence).value,
                "propositionCid" j proposition.value, "predicateLemma" j reading.predicate,
                "recognizerRuleCid" j reading.rule.ruleCid.value, "unitPolicy" j RETAINED_UNIT_POLICY]
            val span = SyntheticNalSpan(source.extractedTextCid, reading.begin, reading.end, quote,
                cid(occurrence), proposition, subject, predicate, cid(annotation))
            occurrences.getOrPut(proposition) { mutableListOf() }.add(RetainedNalOccurrence(span, sentence.index,
                sentenceIri, "$sentenceIri/node/${subj.dependent}:${subj.dependentCopy}",
                "$sentenceIri/node/${root.dependent}:${root.dependentCopy}", "$sentenceIri/node/${obj.dependent}:${obj.dependentCopy}",
                reading.predicate, reading.rule.ruleCid,
                "${reading.rule.antecedent}|${reading.rule.consequent}|${reading.rule.copula.name}|${reading.rule.evidence.packed}|${reading.rule.provenanceCid ?: ""}",
                occurrence, annotation))
            if (proposition !in readings) readings[proposition] = reading
        }
        val premises = occurrences.map { (proposition, all) ->
            val first = all.first().span
            RetainedNalPremise(first.subjectCid, first.predicateCid,
                SourceContribution(proposition, first.occurrenceCid, first.annotationCid, basis, readings.getValue(proposition).rule.evidence),
                all.toSeries())
        }.toSeries()
        fun receipt(p: RetainedNalPremise) = PremiseReceipt(p.contribution.cid,
            TermIdentity(terms.getValue(p.subject)), TermIdentity(terms.getValue(p.predicate)))
        val derived = mutableListOf<SyntheticNalDerivation>()
        val seen = HashSet<ContentId>()
        fun emit(r: DerivationReceipt, subject: ContentId, predicate: ContentId, a: RetainedNalPremise, b: RetainedNalPremise) {
            if (!seen.add(r.canonicalCid)) return
            val preimage = "${r.ruleId.name.length}:${r.ruleId.name};${r.conclusionSubject.id};${r.conclusionPredicate.id};" +
                "${r.premises.a.hex};${r.premises.b.hex};${r.evidence.packed};${r.evaluatorCid.hex};"
            check(ContentId.of(preimage.encodeToByteArray()) == r.canonicalCid)
            derived.add(SyntheticNalDerivation(r.ruleId, subject, predicate,
                cid(retainedProposition(frameCid, subject, predicate)), r.evidence.positive, r.evidence.negative,
                r.canonicalCid, _l[a.contribution.cid, b.contribution.cid], basis,
                if (r.ruleId == RuleIdentity.DEDUCTION) "deduced-hypothesis" else "weak-induction-hypothesis", preimage))
        }
        var pairs = 0
        for (i in 0 until premises.size) for (j in 0 until premises.size) {
            if (i == j) continue
            if (++pairs > 4096) return report(RetainedNalStatus.BOUNDED, s_[RetainedNalIssue("pair-limit")])
            val a = premises[i]; val b = premises[j]
            if (a.predicate == b.subject) emit(DerivationReceipt.deduction(receipt(a), receipt(b),
                Nal.deduce(Nal.truthOf(b.contribution.evidence), Nal.truthOf(a.contribution.evidence)), evaluatorCid), a.subject, b.predicate, a, b)
            if (a.subject == b.subject && a.predicate != b.predicate) emit(DerivationReceipt.induction(receipt(a), receipt(b),
                Nal.induce(Nal.truthOf(a.contribution.evidence), Nal.truthOf(b.contribution.evidence)), evaluatorCid), b.predicate, a.predicate, a, b)
        }
        return report(RetainedNalStatus.EVALUATED, emptySeriesOf(), terms, labels, premises, derived.toSeries(), pairs)
    }

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
