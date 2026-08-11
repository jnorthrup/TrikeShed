package borg.trikeshed.collections.associative

// The β-bucket canonical implementation lives in borg.trikeshed.collections.FunnelHashMap
// (used by Stringpool). This file contained a second, divergent variant ("whole-level
// probing"). Collapse to the canonical name so callers cannot pick the wrong geometry.
typealias FunnelHashMap<K, V> = borg.trikeshed.collections.FunnelHashMap<K, V>
