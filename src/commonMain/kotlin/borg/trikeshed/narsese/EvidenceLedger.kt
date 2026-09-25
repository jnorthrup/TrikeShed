package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries

/** Evidence for one belief and the exact set of source ids it was drawn from. */
data class LedgerEntry(val evidence: EvidenceCoord, val basis: RoaringSeries)

/**
 * Lossless evidence store keyed by belief id. Nothing is evicted; attention is a separate
 * concern. Revision is exact over the Roaring basis: only sources absent from the entry's
 * basis contribute, so the same source never counts twice and the result is independent of
 * arrival order.
 */
class EvidenceLedger<K> {
    private val entries = LinkedHashMap<K, LedgerEntry>()

    val size: Int get() = entries.size

    operator fun get(key: K): LedgerEntry? = entries[key]

    /** Add [evidence] observed from [source]; returns the entry, unchanged when [source] is already in the basis. */
    fun observe(key: K, source: Int, evidence: EvidenceCoord): LedgerEntry {
        val prior = entries[key]
        if (prior != null && prior.basis.contains(source)) return prior
        val next = LedgerEntry(
            revise(prior?.evidence ?: EvidenceCoord.EMPTY, evidence),
            (prior?.basis ?: RoaringSeries.EMPTY) or RoaringSeries.singleton(source),
        )
        entries[key] = next
        return next
    }

    /** Beliefs whose NAL confidence has reached [threshold]. */
    fun eternal(threshold: Float): List<Pair<K, LedgerEntry>> =
        entries.filter { Nal.truthOf(it.value.evidence).confidence >= threshold }.map { it.key to it.value }
}
