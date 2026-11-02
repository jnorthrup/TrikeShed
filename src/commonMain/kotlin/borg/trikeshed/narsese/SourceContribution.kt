package borg.trikeshed.narsese

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

/** Snapshot and validate even a caller-owned mutable or adversarial list in bounded work. */
private fun boundedBasis(source: List<ContentId>): List<ContentId> {
    val copy = ArrayList<ContentId>()
    var previous: String? = null
    for (cid in source) {
        require(copy.size < 64) { "Basis exceeds 64 entries" }
        require(previous == null || previous < cid.value) { "Basis must be sorted and unique" }
        copy.add(cid)
        previous = cid.value
    }
    return copy
}

/** A source-attributed contribution; its evidence is not a finding that the source is true. */
data class SourceContribution(
    val proposition: ContentId,
    val occurrence: ContentId,
    val extractionReceipt: ContentId,
    val basis: List<ContentId>,
    val evidence: EvidenceCoord,
) {
    init {
        boundedBasis(basis)
    }
    val cid: ContentId get() = ContentId.of(SourceContributionCodec.encode(this))
}

/** Version 1 is bounded, strictly typed, and canonical; absent basis remains unknown. */
object SourceContributionCodec {
    private val fields = setOf("kind", "proposition", "occurrence", "extractionReceipt", "basis", "positive", "negative")
    fun encode(value: SourceContribution): ByteArray {
        val frozen = value.copy(basis = boundedBasis(value.basis))
        return CanonicalCbor.encodeMap(_m[
            "kind" j "source-contribution-v1", "proposition" j frozen.proposition.value,
            "occurrence" j frozen.occurrence.value, "extractionReceipt" j frozen.extractionReceipt.value,
            "basis" j frozen.basis.map { it.value }, "positive" j frozen.evidence.positive, "negative" j frozen.evidence.negative,
        ])
    }
    fun decode(bytes: ByteArray): SourceContribution {
        require(bytes.size <= 16_384) { "Contribution exceeds byte limit" }
        val frozenBytes = bytes.copyOf()
        val m = CanonicalCbor.decodeMapBounded(frozenBytes, maxBytes = 16_384, maxDepth = 4, maxItems = 128)
        require(m.keys == fields && m["kind"] == "source-contribution-v1") { "Invalid contribution fields or version" }
        fun cid(key: String) = ContentId(requireNotNull(m[key] as? String) { "Invalid $key" })
        fun weight(key: String): Long {
            val n = requireNotNull(m[key] as? Long) { "Invalid $key" }
            require(n in 0..0xffffffffL) { "Evidence weight out of range" }
            return n
        }
        val basis = (m["basis"] as? List<*>)?.map { ContentId(requireNotNull(it as? String)) }
            ?: throw IllegalArgumentException("Invalid basis")
        val value = SourceContribution(cid("proposition"), cid("occurrence"), cid("extractionReceipt"), basis,
            EvidenceCoord(weight("positive"), weight("negative")))
        require(encode(value).contentEquals(frozenBytes)) { "Contribution must be canonical" }
        return value
    }
}

/** A caller-supplied decision about these exact contributions, not inferred from distinct CIDs. */
data class ContributionIndependence(val first: ContentId, val second: ContentId, val decisionReceipt: ContentId) {
    init { require(first != second) }
    fun covers(a: ContentId, b: ContentId): Boolean = first == a && second == b || first == b && second == a
}

enum class ContributionStatus { EMPTY, READY, CONFLICT, UNRESOLVED, OVERFLOW, LIMIT }
data class ContributionResult(
    val status: ContributionStatus,
    val unique: Int,
    val duplicates: Int,
    val conflicts: List<ContentId>,
    val evidence: EvidenceCoord?,
    /** Receipt identities for the explicit decisions used, not a validation of their authority. */
    val decisionReceipts: List<ContentId> = emptyList(),
)

/**
 * All-or-nothing reduction of one proposition. No basis means unresolved; distinct source
 * identities are not evidence of independence. Conflicting readings of one occurrence are
 * never arbitrarily selected. Decision authority/receipt validity is the caller's boundary.
 */
fun reduceContributions(
    proposition: ContentId,
    contributions: Iterable<SourceContribution>,
    independence: Iterable<ContributionIndependence>,
): ContributionResult {
    val unique = LinkedHashMap<ContentId, SourceContribution>()
    var duplicates = 0
    var seen = 0
    for (input in contributions) {
        if (++seen > 1_024) return ContributionResult(ContributionStatus.LIMIT, unique.size, duplicates, emptyList(), null)
        val value = input.copy(basis = boundedBasis(input.basis))
        require(value.proposition == proposition) { "Contribution belongs to another proposition" }
        if (unique.put(value.cid, value) != null) duplicates++
        if (unique.size > 128) return ContributionResult(ContributionStatus.LIMIT, unique.size, duplicates, emptyList(), null)
    }
    if (unique.isEmpty()) return ContributionResult(ContributionStatus.EMPTY, 0, 0, emptyList(), null)
    val conflicts = unique.values.groupBy { it.occurrence }.filterValues { it.size > 1 }.keys.sortedBy { it.value }
    if (conflicts.isNotEmpty()) return ContributionResult(ContributionStatus.CONFLICT, unique.size, duplicates, conflicts, null)
    val entries = unique.entries.sortedBy { it.key.value }
    if (entries.any { it.value.basis.isEmpty() })
        return ContributionResult(ContributionStatus.UNRESOLVED, unique.size, duplicates, emptyList(), null)
    val decisions = HashMap<String, MutableSet<ContentId>>()
    var decisionCount = 0
    // Content IDs have fixed-length canonical strings, so concatenation is unambiguous.
    fun pair(a: ContentId, b: ContentId) = if (a.value < b.value) a.value + b.value else b.value + a.value
    for (decision in independence) {
        if (++decisionCount > 8_128) return ContributionResult(ContributionStatus.LIMIT, unique.size, duplicates, emptyList(), null)
        decisions.getOrPut(pair(decision.first, decision.second)) { HashSet() }.add(decision.decisionReceipt)
    }
    val decisionReceipts = HashSet<ContentId>()
    for (i in entries.indices) for (j in i + 1 until entries.size) {
        val a = entries[i]; val b = entries[j]
        val pairDecisions = decisions[pair(a.key, b.key)]
        if (a.value.basis.any { it in b.value.basis } || pairDecisions == null)
            return ContributionResult(ContributionStatus.UNRESOLVED, unique.size, duplicates, emptyList(), null)
        decisionReceipts.addAll(pairDecisions)
    }
    var positive = 0L; var negative = 0L
    for (entry in entries) {
        positive += entry.value.evidence.positive
        negative += entry.value.evidence.negative
        if (positive > 0xffffffffL || negative > 0xffffffffL)
            return ContributionResult(ContributionStatus.OVERFLOW, unique.size, duplicates, emptyList(), null)
    }
    return ContributionResult(ContributionStatus.READY, unique.size, duplicates, emptyList(),
        EvidenceCoord(positive, negative), decisionReceipts.sortedBy { it.value })
}
