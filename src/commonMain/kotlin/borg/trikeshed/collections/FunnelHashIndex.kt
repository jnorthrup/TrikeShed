package borg.trikeshed.collections

/**
 * Public alias for the frozen multi-level membership index.
 * Implementation: [borg.trikeshed.collections.associative.FunnelHashIndex].
 *
 * FunnelHashIndex — tiered linear probing with expanding probe bounds.
 * Key properties:
 * - Append-only immutable segment (no delete/retract)
 * - Negative-query-heavy workloads (dedup, membership, frozen schema)
 * - Multi-level expanding probeBound geometry, NOT paper β-bucket funnel; do NOT claim O(log² 1/δ)
 * - Deterministic replay: probe entropy derived from key hashCode + committed seed
<<<<<<< HEAD
=======
 *
 * Usage:
 *   val idx = FunnelHashIndex.build(listOf("a", "b", "c"), seed)
 *   val pos = idx.get("b")  // returns Some(1) or null
>>>>>>> origin/bolt-funnel-hash-index-optimization-3357337025277533537
 */
typealias FunnelHashIndex<K> = borg.trikeshed.collections.associative.FunnelHashIndex<K>
