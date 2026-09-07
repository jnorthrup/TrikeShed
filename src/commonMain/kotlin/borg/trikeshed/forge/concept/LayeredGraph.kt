package borg.trikeshed.forge.concept

/**
 * The layered-DAG layout both Forge mindmaps share.
 *
 * [ConceptGraph] maps the CODE (lib → cursor → confix → facets → surface → widgets) and
 * [DocsGraph] maps the DOCS (index → guide → spec → contract → plan → analysis → note).
 * They are the same picture over different corpora, so they are the same layout: one column
 * per layer, nodes stacked in declaration order, each column centred against the tallest.
 * Deterministic and overlap-free — a lattice needs no force simulation.
 *
 * The seed shape is fixed by `script.js`, which draws every graph mode with one renderer:
 * `{nodes:[{id,title,layer,symbol,file,x,y,topo}], edges:[{from,to,rel}], layers, camera}`.
 * A third mindmap is therefore a third corpus, not a third renderer.
 */
data class LayeredNode(
    val id: String,
    val title: String,
    val layer: String,
    /** The one-line gloss under the title in the inspector. */
    val symbol: String,
    /** Where the thing actually lives, shown as the inspector's footer. */
    val file: String,
)

data class LayeredEdge(val from: String, val to: String, val rel: String)

/**
 * Lay [nodes] out in [layers] order and emit the seed the graph view reads.
 *
 * Nodes whose layer is not in [layers] are dropped rather than piled at column -1, because a
 * mislaid column is a silent picture and an absent node is a visible one.
 */
fun layeredLayoutSeed(
    nodes: List<LayeredNode>,
    edges: List<LayeredEdge>,
    layers: List<String>,
    colGap: Double = 300.0,
    rowGap: Double = 58.0,
    zoom: Double = 0.7,
    filePrefix: String = "",
): Map<String, Any?> {
    val placed = nodes.filter { it.layer in layers }
    val byLayer = placed.groupBy { it.layer }
    val tallest = byLayer.values.maxOfOrNull { it.size } ?: 1
    val laidOut = placed.map { n ->
        val col = layers.indexOf(n.layer)
        val column = byLayer.getValue(n.layer)
        val row = column.indexOf(n)
        val yOffset = (tallest - column.size) * rowGap / 2.0
        mapOf(
            "id" to n.id,
            "title" to n.title,
            "layer" to n.layer,
            "symbol" to n.symbol,
            "file" to filePrefix + n.file,
            "x" to col * colGap,
            "y" to yOffset + row * rowGap,
            "topo" to col,
        )
    }
    val known = placed.map { it.id }.toSet()
    return mapOf(
        "nodes" to laidOut,
        // An edge to a dropped node would draw to the origin, which reads as a real relation
        // pointing at nothing.
        "edges" to edges.filter { it.from in known && it.to in known }
            .map { mapOf("from" to it.from, "to" to it.to, "rel" to it.rel) },
        "layers" to layers,
        "camera" to mapOf(
            "x" to (layers.size - 1) * colGap / 2.0,
            "y" to (tallest - 1) * rowGap / 2.0,
            "zoom" to zoom,
        ),
    )
}
