package narchy.spacegraph.representations

import narchy.spacegraph.zoom.ScaleSample

// Port of upstream src/representations/ladder.ts at 125b369. One representation ladder per kind.
// The library registers, arbitrates and selects rungs from a ScaleDomain sample; the host supplies the order,
// the bands and the appearance of every rung. Selection keeps node ids stable across swaps.
// Deviation: LadderNode/LadderContext/LadderRung are Kotlin interfaces/data classes; `build` is a lambda.

/** Host salience value on `importance` overrides the interpolated importance of the scale sample. */
interface LadderNode : LadderEntry

data class LadderContext(
    /** The stable node id, unchanged when the representation changes. */
    val id: String,
    val sample: ScaleSample,
    val rung: String,
)

data class LadderRung<Node : LadderNode, Output>(
    override val id: String,
    /** Normalized scale at which this rung takes over, usually a ScaleDomain stop. */
    override val at: Double,
    /** Node kinds served by this rung; null means every kind. */
    override val kinds: List<String>? = null,
    /** Minimum effective importance for this rung; finer rungs are skipped below it. */
    override val minImportance: Double? = null,
    /** Host-owned appearance. The library never inspects the result. */
    val build: (node: Node, context: LadderContext) -> Output,
) : Rung, RungGate

data class LadderConfig(
    /** Normalized dead zone around a rung boundary; dampens wheel bursts at a swap. */
    val hysteresis: Double? = null,
)

data class LadderSelection<Node : LadderNode, Output>(
    val id: String,
    val rung: LadderRung<Node, Output>,
    val value: Output,
)

/** The coarse-to-fine ladder a host may adopt; hosts own the real order and appearance. */
val DEFAULT_RUNG_IDS: List<String> = listOf("dot", "glyph", "card", "widget", "detail")

class RepresentationLadder<Node : LadderNode, Output>(
    rungs: List<LadderRung<Node, Output>> = emptyList(),
    config: LadderConfig = LadderConfig(),
) : LadderBase<Node, LadderRung<Node, Output>, LadderSelection<Node, Output>>(rungs, config.hysteresis) {

    override fun createSelection(
        entry: Node,
        sample: ScaleSample,
        rung: LadderRung<Node, Output>,
    ): LadderSelection<Node, Output> = LadderSelection(
        id = entry.id,
        rung = rung,
        value = rung.build(entry, LadderContext(entry.id, sample, rung.id)),
    )
}

/** Align rungs with the stops of a ScaleDomain; a missing builder removes that rung. */
fun <Node : LadderNode, Output> ladderFromStops(
    stops: List<RungStop>,
    builders: Map<String, (Node, LadderContext) -> Output>,
    config: LadderConfig = LadderConfig(),
): RepresentationLadder<Node, Output> = RepresentationLadder(
    rungsFromStops(stops, builders) { at, id, build ->
        LadderRung(id = id, at = at, build = build)
    },
    config,
)

data class RungChange(
    val id: String,
    val from: String,
    val to: String,
    /** Seconds to play the swap, supplied by the scale domain rather than hardcoded here. */
    val duration: Double,
)

/** Diff two selections; the host plays the result with its own transition mechanism. */
fun representationChanges(
    previous: Map<String, String>,
    next: Map<String, String>,
    sample: ScaleSample,
): List<RungChange> = next.mapNotNull { (id, to) ->
    val from = previous[id]
    if (from != null && from != to) RungChange(id, from, to, sample.transitionDuration) else null
}

interface ClusterNode : LadderNode {
    /** Members hidden while the cluster is collapsed; ids stay stable across swaps. */
    val clusterOf: List<String>?
}

data class ClusterCollapse(
    /** Node ids a host may draw at this scale. */
    val visible: Set<String>,
    /** Cluster id to its rung, so a host can draw the collapsed chip. */
    val clusters: Map<String, String>,
)

/** Clusters collapse as representations, not as a separate level state. */
fun <Node : ClusterNode, Output> collapseClusters(
    nodes: List<Node>,
    sample: ScaleSample,
    ladder: RepresentationLadder<Node, Output>,
    expandRung: String,
): ClusterCollapse {
    val clusters = LinkedHashMap<String, String>()
    val hidden = HashSet<String>()

    for (node in nodes) {
        val rung = ladder.select(node, sample)?.rung?.id ?: continue
        val members = node.clusterOf
        if (rung == expandRung || members.isNullOrEmpty()) continue
        clusters[node.id] = rung
        hidden.addAll(members)
    }

    return ClusterCollapse(
        visible = nodes.filter { it.id !in hidden }.map { it.id }.toSet(),
        clusters = clusters,
    )
}
