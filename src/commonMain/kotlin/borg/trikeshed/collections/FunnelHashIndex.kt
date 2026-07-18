package borg.trikeshed.collections

import borg.trikeshed.job.Sha256Pure

/**
 * FunnelHashIndex — tiered linear probing with expanding probe bounds.
 *
 * Key properties:
 * - Append-only immutable segment (no delete/retract)
 * - Negative-query-heavy workloads (dedup, membership, frozen schema)
 * - Multi-level funnel: each level doubles probe bound, halves capacity
 * - Deterministic replay: probe entropy derived from canonical facet bytes + committed seed
 *
 * Usage:
 *   val idx = FunnelHashIndex.build(listOf("a", "b", "c"), seed)
 *   val pos = idx.get("b")  // returns Some(1) or null
 */
typealias FunnelHashIndex<K> = borg.trikeshed.collections.associative.FunnelHashIndex<K>