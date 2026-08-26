package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/** A real event observed at a Hermes production boundary. */
enum class HermesEventKind {
    TOOL_CALL,
    TOOL_RESULT,
    STATE_TRANSITION,
    HUMAN_DECISION,
    ERROR,
    RECOVERY,
    APPROVAL,
    REJECTION,
}

/**
 * CAS-addressed production observation. No event enters the semantic lane
 * without a source CID; the result may be absent for an in-flight event.
 */
data class HermesProductionEvent(
    val eventId: ContentId,
    val eventKind: HermesEventKind,
    val sourceCid: ContentId,
    val outcomeCid: ContentId? = null,
    val actor: String,
    val tool: String? = null,
    val contextFacets: Series<String> = emptySeriesOf(),
    val timestampMs: Long,
)

/** The causal projection of one production event. */
data class CausalRecord(
    val recordCid: ContentId,
    val event: HermesProductionEvent,
    val subject: String,
    val predicate: String,
    val obj: String,
    val evidence: EvidenceCoord,
    val basis: EvidenceBasis,
)

/** The explicit source-to-NAL crossing; still a candidate, never law. */
data class BridgedNalSentence(
    val sourceCid: ContentId,
    val sourceStatement: String,
    val mapped: KgNalBridge.NalMapped,
    val contextFacets: Series<String>,
    val extractionReceiptCid: ContentId,
    val evidenceBasis: EvidenceBasis,
) {
    val isRuleCandidate: Boolean
        get() = mapped.copula == NalCopula.IMPLICATION || mapped.copula == NalCopula.EQUIVALENCE
}

/** Pure projection from a production event into a causal record. */
fun HermesProductionEvent.toCausalRecord(
    subject: String,
    predicate: String,
    obj: String,
    evidence: EvidenceCoord = Nal.observe(outcomeCid != null),
): CausalRecord {
    val basis = EvidenceBasis.of(sourceCid, *(outcomeCid?.let { arrayOf(it) } ?: emptyArray()))
    val canonical = buildString {
        append(eventId.value).append('|').append(subject).append('|')
        append(predicate).append('|').append(obj).append('|').append(evidence.packed)
    }
    return CausalRecord(ContentId.of(canonical.encodeToByteArray()), this, subject, predicate, obj, evidence, basis)
}

/** Direct production-event crossing into the existing KgNalBridge mapping. */
fun HermesProductionEvent.bridgeToNal(
    subject: String,
    predicate: String,
    obj: String,
): BridgedNalSentence {
    val triplet = KgTriplet(
        subject = subject,
        predicate = predicate,
        obj = obj,
        subjectCid = sourceCid.value,
        objectCid = outcomeCid?.value,
        confidence = if (outcomeCid == null) 0.5f else 0.9f,
    )
    val bridge = KgNalBridge.map(triplet)
    val receiptCid = ContentId.of("${eventId.value}|kg-nal-bridge|$predicate".encodeToByteArray())
    return BridgedNalSentence(
        sourceCid = sourceCid,
        sourceStatement = "($predicate $subject $obj)",
        mapped = bridge,
        contextFacets = contextFacets,
        extractionReceiptCid = receiptCid,
        evidenceBasis = EvidenceBasis.of(sourceCid, *(outcomeCid?.let { arrayOf(it) } ?: emptyArray())),
    )
}

/** Permanent leaf ancestry for an observation or derivation. */
data class EvidenceBasis(
    val leaves: Series<ContentId>,
    val bloom: Long,
) {
    companion object {
        fun of(vararg leaves: ContentId): EvidenceBasis {
            val series = leaves.size j { i: Int -> leaves[i] }
            return EvidenceBasis(series, basisBloomOf(*leaves.map { it.value }.toTypedArray()))
        }
    }

    operator fun contains(cid: ContentId): Boolean {
        for (i in 0 until leaves.size) if (leaves[i] == cid) return true
        return false
    }

    fun overlaps(other: EvidenceBasis): Boolean {
        if (bloom == 0L || other.bloom == 0L || bloom and other.bloom == 0L) return false
        for (i in 0 until leaves.size) if (other.contains(leaves[i])) return true
        return false
    }
}

enum class EvidenceDependence {
    INDEPENDENT,
    DEPENDENT,
    DUPLICATE,
    UNRESOLVED,
}

data class RevisionDecision(
    val evidence: EvidenceCoord,
    val dependence: EvidenceDependence,
    val accepted: Boolean,
)

/**
 * Evidence revision with duplicate and ancestry protection. A Bloom overlap is
 * only a trigger for exact leaf comparison; it is never proof of independence.
 */
object OverlapSafeRevision {
    fun revise(
        existingReceipt: ContentId?,
        incomingReceipt: ContentId,
        existingEvidence: EvidenceCoord,
        incomingEvidence: EvidenceCoord,
        existingBasis: EvidenceBasis?,
        incomingBasis: EvidenceBasis?,
    ): RevisionDecision {
        if (existingReceipt == incomingReceipt) {
            return RevisionDecision(existingEvidence, EvidenceDependence.DUPLICATE, accepted = false)
        }
        if (existingBasis == null || incomingBasis == null) {
            return RevisionDecision(
                conservativeMax(existingEvidence, incomingEvidence),
                EvidenceDependence.UNRESOLVED,
                accepted = true,
            )
        }
        return if (!existingBasis.overlaps(incomingBasis)) {
            RevisionDecision(revise(existingEvidence, incomingEvidence), EvidenceDependence.INDEPENDENT, accepted = true)
        } else {
            RevisionDecision(
                conservativeMax(existingEvidence, incomingEvidence),
                EvidenceDependence.DEPENDENT,
                accepted = true,
            )
        }
    }

    private fun conservativeMax(a: EvidenceCoord, b: EvidenceCoord): EvidenceCoord =
        EvidenceCoord(maxOf(a.positive, b.positive), maxOf(a.negative, b.negative))
}

enum class ActivationState {
    ACTIVE,
    STALE,
    ARCHIVED,
}

/** Mutable-in-time attention envelope; it does not carry authority or truth. */
data class RuleActivationBudget(
    val ruleCid: ContentId,
    val priority: Float,
    val durability: Float,
    val lastActivatedTick: Long,
    val activationState: ActivationState = ActivationState.ACTIVE,
) {
    fun age(tick: Long, lambda: Float = AttentionEconomy.LAMBDA): RuleActivationBudget {
        val nextPriority = priority * (durability + (1f - durability) * lambda)
        val nextState = when {
            nextPriority >= CurationState.ACTIVE.floor -> ActivationState.ACTIVE
            nextPriority >= CurationState.STALE.floor -> ActivationState.STALE
            else -> ActivationState.ARCHIVED
        }
        return copy(priority = nextPriority, lastActivatedTick = tick, activationState = nextState)
    }

    fun activate(tick: Long, priority: Float = 1f): RuleActivationBudget =
        copy(priority = priority.coerceIn(0f, 1f), lastActivatedTick = tick, activationState = ActivationState.ACTIVE)
}

enum class AdmissionAuthority {
    HUMAN,
    DECLARED_POLICY,
}

/** The only receipt that can promote a candidate into immutable Rete law. */
data class RuleAdmissionReceipt(
    val candidateRuleCid: ContentId,
    val authority: AdmissionAuthority,
    val policyCid: ContentId?,
    val independentLeafBasisCount: Int,
    val supportingReceiptCids: Series<ContentId>,
    val refutingReceiptCids: Series<ContentId>,
    val rationale: String,
    val timestampMs: Long,
    val receiptCid: ContentId,
) {
    companion object {
        fun create(
            candidateRuleCid: ContentId,
            authority: AdmissionAuthority,
            policyCid: ContentId?,
            independentLeafBasisCount: Int,
            supportingReceiptCids: Series<ContentId>,
            refutingReceiptCids: Series<ContentId>,
            rationale: String,
            timestampMs: Long,
        ): RuleAdmissionReceipt {
            require(independentLeafBasisCount > 0) { "law requires an independent evidence basis" }
            val canonical = buildString {
                append(candidateRuleCid.value).append('|')
                append(authority.name).append('|')
                append(policyCid?.value ?: "").append('|')
                append(independentLeafBasisCount).append('|')
                append(rationale).append('|').append(timestampMs)
            }
            return RuleAdmissionReceipt(
                candidateRuleCid,
                authority,
                policyCid,
                independentLeafBasisCount,
                supportingReceiptCids,
                refutingReceiptCids,
                rationale,
                timestampMs,
                ContentId.of(canonical.encodeToByteArray()),
            )
        }
    }
}

/** Immutable active-law snapshot. Supersession creates a new instance. */
data class RuleSetVersion(
    val versionCid: ContentId,
    val rules: Series<EternalRule>,
    val admissionReceiptCids: Series<ContentId>,
    val previousVersionCid: ContentId? = null,
    val createdAtMs: Long,
    val admissionReceipts: Series<RuleAdmissionReceipt> = emptySeriesOf(),
) {
    companion object {
        fun create(
            rules: Series<EternalRule>,
            receipts: Series<RuleAdmissionReceipt>,
            previousVersionCid: ContentId? = null,
            createdAtMs: Long,
        ): RuleSetVersion {
            for (i in 0 until rules.size) {
                require(receiptsContains(receipts, rules[i].ruleCid)) {
                    "rule ${rules[i].ruleCid.value} has no admission receipt"
                }
            }
            val receiptCids = receipts.size j { i: Int -> receipts[i].receiptCid }
            val canonical = buildString {
                for (i in 0 until rules.size) append(rules[i].ruleCid.value).append(';')
                for (i in 0 until receiptCids.size) append(receiptCids[i].value).append(';')
                append(previousVersionCid?.value ?: "").append('|').append(createdAtMs)
            }
            return RuleSetVersion(
                ContentId.of(canonical.encodeToByteArray()),
                rules,
                receiptCids,
                previousVersionCid,
                createdAtMs,
                receipts,
            )
        }

        private fun receiptsContains(receipts: Series<RuleAdmissionReceipt>, ruleCid: ContentId): Boolean {
            for (i in 0 until receipts.size) if (receipts[i].candidateRuleCid == ruleCid) return true
            return false
        }
    }
}

data class WeightingExplanation(
    val candidateCid: ContentId,
    val truthExpectation: Float,
    val attentionPriority: Float,
    val semanticProximity: Float,
    val contextCompatibility: Float,
    val temporalRelevance: Float,
    val taskUtility: Float,
    val independenceFactor: Float,
    val finalScore: Float,
    val beta: Float,
)

data class ContextCandidate(
    val cid: ContentId,
    val signal: SemanticSignal,
    val budget: BudgetCoord,
    val semanticProximity: Float,
    val contextCompatibility: Float,
    val temporalRelevance: Float,
    val taskUtility: Float,
    val independenceFactor: Float,
)

/** Attention ordering only; this function never rewrites evidence or truth. */
object ContextWeigher {
    fun explain(candidate: ContextCandidate, beta: Float = 1f): WeightingExplanation {
        val truth = candidate.signal.truth().expectation()
        val proximity = candidate.semanticProximity.coerceIn(0f, 1f)
        val score = truth * candidate.budget.pf * proximity *
            candidate.contextCompatibility.coerceIn(0f, 1f) *
            candidate.temporalRelevance.coerceIn(0f, 1f) *
            candidate.taskUtility.coerceIn(0f, 1f) *
            candidate.independenceFactor.coerceIn(0f, 1f)
        return WeightingExplanation(
            candidate.cid,
            truth,
            candidate.budget.pf,
            proximity,
            candidate.contextCompatibility,
            candidate.temporalRelevance,
            candidate.taskUtility,
            candidate.independenceFactor,
            score,
            beta,
        )
    }
}

data class ContextBundle(
    val bundleCid: ContentId,
    val strongestSupport: Series<ContentId>,
    val strongestRefutation: Series<ContentId>,
    val applicableRuleCids: Series<ContentId>,
    val causalRecordCids: Series<ContentId>,
    val unresolvedContradictionCids: Series<ContentId>,
    val derivationReceiptCids: Series<ContentId>,
    val weighting: Series<WeightingExplanation>,
    val createdAtMs: Long,
) {
    companion object {
        /** Build a bounded, explainable bundle from candidate context. */
        fun create(
            candidates: Series<ContextCandidate>,
            applicableRuleCids: Series<ContentId> = emptySeriesOf(),
            causalRecordCids: Series<ContentId> = emptySeriesOf(),
            unresolvedContradictionCids: Series<ContentId> = emptySeriesOf(),
            derivationReceiptCids: Series<ContentId> = emptySeriesOf(),
            createdAtMs: Long,
            maxPerFront: Int = 8,
            beta: Float = 1f,
        ): ContextBundle {
            require(maxPerFront >= 0) { "maxPerFront must be non-negative" }
            val scored = mutableListOf<WeightingExplanation>()
            for (i in 0 until candidates.size) scored.add(ContextWeigher.explain(candidates[i], beta))
            val ordered = scored.sortedByDescending { it.finalScore }
            val support = ordered.filter { it.truthExpectation >= 0.5f }.take(maxPerFront)
            val refutation = ordered.filter { it.truthExpectation < 0.5f }.take(maxPerFront)
            val canonical = buildString {
                for (item in ordered) append(item.candidateCid.value).append(':').append(item.finalScore).append(';')
                append(createdAtMs)
            }
            return ContextBundle(
                ContentId.of(canonical.encodeToByteArray()),
                support.size j { i: Int -> support[i].candidateCid },
                refutation.size j { i: Int -> refutation[i].candidateCid },
                applicableRuleCids,
                causalRecordCids,
                unresolvedContradictionCids,
                derivationReceiptCids,
                ordered.size j { i: Int -> ordered[i] },
                createdAtMs,
            )
        }
    }
}

/**
 * Bounded event spine. Evidence-bearing events are backpressured; presentation
 * taps can explicitly choose DROP_OLDEST at their own boundary.
 */
class ProductionEventSpine(capacity: Int = 256, renderOnly: Boolean = false) {
    val events: Channel<HermesProductionEvent> = if (renderOnly) {
        Channel(capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    } else {
        Channel(capacity)
    }
}

/** Typed live spine for the complete causal pipeline. Evidence channels backpressure. */
class LiveProductionSpine(capacity: Int = 256) {
    val production = Channel<HermesProductionEvent>(capacity)
    val causalRecords = Channel<CausalRecord>(capacity)
    val reteFirings = Channel<ReteFiring>(capacity)
    val admissions = Channel<RuleAdmissionReceipt>(capacity)
    val bundles = Channel<ContextBundle>(capacity)

    fun close() {
        production.close()
        causalRecords.close()
        reteFirings.close()
        admissions.close()
        bundles.close()
    }
}
