package narchy.spacegraph.layout

// Port of upstream src/layout/scope-policy.ts at 125b369.
// The adaptive seam between host signals and scope geometry. A policy translates zoom, focus and vision scores into
// activation, a pin set, and solver parameters. It owns no state and imports no vision types, so any host can feed
// it from its own analyzer.
// No upstream test imports this file; nothing checks this port beyond its gate compile.

import narchy.spacegraph.util.clamp01

data class ScopeVisionSignal(
    /** 0..1 overlap severity; higher gives the solvers more room. */
    val overlap: Double? = null,
    /** 0..1 legibility score; lower gives labels more padding. */
    val legibility: Double? = null,
    /** 0..1 hierarchy quality; lower gives scopes more depth separation. */
    val hierarchy: Double? = null,
    /** 0..1 target-size quality; lower gives nodes more padding. */
    val ergonomics: Double? = null,
)

data class ScopePolicyInput(
    val band: Int? = null,
    val focus: String? = null,
    val maxDepth: Int? = null,
    val vision: ScopeVisionSignal? = null,
)

data class ScopePolicy(
    val active: Set<String>,
    /** The focused node plus every scope that owns it. */
    val pinned: Set<String>,
    val layout: ScopeLayoutOptions,
)

/** Derive activation, stability and solver settings from the current host signals. */
fun scopePolicy(
    tree: ScopeTree,
    input: ScopePolicyInput,
    base: ScopeLayoutOptions = ScopeLayoutOptions(),
): ScopePolicy {
    val active = activeScopePaths(tree, ScopeSignals(band = input.band, focus = input.focus, maxDepth = input.maxDepth))
    val pinned = LinkedHashSet<String>()
    input.focus?.let { focus ->
        pinned.add(focus)
        for (ancestor in scopeAncestors(tree, focus)) pinned.add(ancestor.path)
    }

    val overlap = clamp01(input.vision?.overlap ?: 0.0)
    val legibility = clamp01(input.vision?.legibility ?: 1.0)
    val ergonomics = clamp01(input.vision?.ergonomics ?: 1.0)
    val gap = (base.gap ?: 160.0) * (1 + overlap * 1.5)
    val nodePadding = (base.nodePadding ?: 20.0) * (1 + (1 - legibility) * 2 + (1 - ergonomics) * 2)
    var layout = base.copy(gap = gap, nodePadding = nodePadding)
    input.vision?.hierarchy?.let { hierarchy ->
        layout = layout.copy(
            depthStep = (base.depthStep ?: 220.0) * (1 + (1 - clamp01(hierarchy)) * 0.5),
        )
    }

    return ScopePolicy(active, pinned, layout)
}
