package borg.trikeshed.collections

/**
 * Public alias for the frozen multi-level membership index.
 * Implementation: [borg.trikeshed.collections.associative.FunnelHashIndex].
 *
 * Cousin of Krapivin funnel geometry (expanding probe bounds), not the paper's
 * β-bucket funnel. No O(log² 1/δ) claim. Unit-cost mix64 probes.
 */
typealias FunnelHashIndex<K> = borg.trikeshed.collections.associative.FunnelHashIndex<K>
