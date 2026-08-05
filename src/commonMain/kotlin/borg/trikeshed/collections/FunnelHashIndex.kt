package borg.trikeshed.collections

/**
<<<<<<< ours
 * Public alias for the frozen multi-level membership index.
 * Implementation: [borg.trikeshed.collections.associative.FunnelHashIndex].
=======
 * FunnelHashIndex — tiered linear probing with expanding probe bounds.
 *
 * Key properties:
 * - Append-only immutable segment (no delete/retract)
 * - Negative-query-heavy workloads (dedup, membership, frozen schema)
 * - Multi-level expanding probeBound geometry, NOT paper β-bucket funnel; do NOT claim O(log² 1/δ)
 * - Deterministic replay: probe entropy derived from key hashCode + committed seed
>>>>>>> theirs
 *
 * Cousin of Krapivin funnel geometry (expanding probe bounds), not the paper's
 * β-bucket funnel. No O(log² 1/δ) claim. Unit-cost mix64 probes.
 */
typealias FunnelHashIndex<K> = borg.trikeshed.collections.associative.FunnelHashIndex<K>
