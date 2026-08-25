package borg.trikeshed.narsese

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * SemanticSignal — one extraction event as a typed Narsese payload.
 *
 * This is the T-RESUME-SIGNALS-3 shape: signals are data, not objects with
 * behavior. A signal is one row in the manifold — identity by angular
 * coordinate, attention by budget, belief by evidence.
 *
 * @param angular Long bitfield identity coordinate (hamming = semantic distance)
 * @param evidence raw evidence counts; truth is derived, never stored
 * @param relation the reducer-emitted relation kind
 * @param subjectCid ContentId of the subject (CAS-anchored, never fabricated)
 * @param objectCid ContentId of the object; null = UNANCHORED mention
 * @param temporal optional temporal qualification
 * @param provenanceCid ContentId of the extraction receipt (parent stage's CID)
 * @param basisBloom 64-bit Bloom/XOR-fold of the evidential basis (provenance
 *   ancestry). Covariance-intersection preparation: overlapping blooms mean
 *   "may share evidence, do not naively sum" — revision must treat the bases
 *   as dependent. 0L = unknown/empty basis (legacy records).
 */
data class SemanticSignal(
    val angular: Long,
    val evidence: EvidenceCoord,
    val relation: RelationKind,
    val subjectCid: String,
    val objectCid: String? = null,
    val temporal: TemporalSignal? = null,
    val provenanceCid: String? = null,
    val basisBloom: Long = 0L,
) {
    val isAnchored: Boolean get() = objectCid != null
    fun truth(k: Float = 1f): TruthCoord = evidence.truth(k)
}

/** Reducer-emitted relation kinds. Similarity proposes work; it never establishes truth. */
enum class RelationKind {
    /** Similar self-content/schema signatures pull regions into the same manifold neighborhood. */
    ATTRACTION,

    /** Ordered or provenance-backed transition from one CAS region to the next. */
    CAUSALITY,

    /** Two signals share angular proximity beyond threshold */
    MATCH,

    /** One signal asserts what another lacks */
    GAP,

    /** Two signals assert contradictory predicates on the same subject */
    CONTRADICTION,

    /** A signal's subject/object cannot be CAS-anchored */
    MISSING_EVIDENCE,
}

/**
 * NarseseBag — the manifold lookup. One FunnelHashMap, key = angular j evidence-packed.
 *
 * NOT a class hierarchy: no Concept, no Machine, no lifecycle. The bag is the
 * FunnelHashMap; these are extension functions on it. recall/recallNear/seal
 * are folds and projections, not manager methods.
 *
 * Key shape: Join<Long, Long> = angular j (budget.packed). Budget rides in
 * the key so attention is addressable; the payload is the SemanticSignal.
 */

/** recall: all signals sorted by truth expectation descending. */
fun <V> Map<Join<Long, Long>, V>.recallByExpectation(
    evidenceOf: (V) -> EvidenceCoord,
): Series<V> {
    val entries = entries.toList()
    val sorted = entries.sortedByDescending { (_, v) -> evidenceOf(v).truth().expectation() }
    return sorted.size j { i: Int -> sorted[i].value }
}

/** recallNear: signals within hamming maxDistance of a centroid, nearest first. */
fun <V> Map<Join<Long, Long>, V>.recallNear(
    centroid: Long,
    maxDistance: Int,
): Series<V> {
    val entries = entries.toList()
    val near = entries
        .filter { (key, _) -> hamming(key.a, centroid) <= maxDistance }
        .sortedBy { (key, _) -> hamming(key.a, centroid) }
    return near.size j { i: Int -> near[i].value }
}

/** Hamming distance between two angular coordinates. Free function on Longs, never a method. */
fun hamming(a: Long, b: Long): Int = (a xor b).countOneBits()

// FNV-1a 64: offset basis 0xcbf29ce484222325 / prime 0x100000001b3 (signed literals).
private const val FNV64_OFFSET: Long = -0x340d631b7bdddcdbL
private const val FNV64_PRIME: Long = 0x100000001b3L

/**
 * basisBloomOf — fold evidence-source cids into a 64-bit Bloom (k=3).
 * Per cid: FNV-1a 64 of the bytes, then 3 bit indices ((h ushr 0/21/42) mod 64)
 * OR-combined. Union across cids = classic Bloom insert; zero false negatives,
 * bounded false positives — a nonzero AND between two blooms means the bases
 * MAY overlap and covariance-aware revision applies.
 */
fun basisBloomOf(vararg cids: String): Long {
    var bloom = 0L
    for (cid in cids) {
        var h = FNV64_OFFSET
        for (byte in cid.encodeToByteArray()) h = (h xor (byte.toLong() and 0xFFL)) * FNV64_PRIME
        bloom = bloom or
            (1L shl ((h ushr 0).toInt() and 63)) or
            (1L shl ((h ushr 21).toInt() and 63)) or
            (1L shl ((h ushr 42).toInt() and 63))
    }
    return bloom
}

/**
 * reviseInto: NARS revision as a map fold. Two signals with the same angular
 * identity merge their evidence bases; the result is a new signal (supersede),
 * not a mutation.
 */
fun Map<Join<Long, Long>, SemanticSignal>.reviseInto(
    key: Join<Long, Long>,
    incoming: SemanticSignal,
): Map<Join<Long, Long>, SemanticSignal> {
    val existing = this[key] ?: return this + (key to incoming)
    val merged = SemanticSignal(
        angular = incoming.angular,
        evidence = revise(existing.evidence, incoming.evidence),
        relation = incoming.relation,
        subjectCid = incoming.subjectCid,
        objectCid = incoming.objectCid,
        temporal = incoming.temporal,
        provenanceCid = incoming.provenanceCid,
        basisBloom = existing.basisBloom or incoming.basisBloom, // Bloom union: merged basis
    )
    return this + (key to merged)
}
