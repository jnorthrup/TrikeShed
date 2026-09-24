@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge.graph

import borg.trikeshed.forge.asInt
import borg.trikeshed.forge.asList
import borg.trikeshed.forge.asMaps
import borg.trikeshed.forge.asStr
import borg.trikeshed.forge.asStrOrNull
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Graph-view decisions — script.js "Render: graph" minus the SVG: the three graph modes, the
 * layout seed shape, the camera math (fit / pan / pointer-anchored zoom), and the inspector's
 * relation derivation. The layout itself runs server-side (`blackboard/ForceLayout.kt`); the
 * browser only draws and moves the camera.
 */

/** `GRAPH_MODES` — causal (forceLayout), concept (ConceptGraph), docs (DocsGraph). */
enum class ForgeGraphMode(val id: String) {
    Causal("causal"), Concept("concept"), Docs("docs");

    companion object {
        fun of(id: String?): ForgeGraphMode = entries.firstOrNull { it.id == id } ?: Causal
    }
}

data class ForgeGraphNode(
    val id: String,
    val title: String,
    val x: Double,
    val y: Double,
    val topo: Int,
    val layer: String? = null,
    val symbol: String? = null,
    val file: String? = null,
)

data class ForgeGraphEdge(val from: String, val to: String, val rel: String? = null)

/** ForgeBlackboardCamera convention on this surface: world → translate(−cam) → scale(zoom). */
data class ForgeGraphCamera(val x: Double, val y: Double, val zoom: Double)

data class ForgeGraphLayout(
    val nodes: List<ForgeGraphNode>,
    val edges: List<ForgeGraphEdge>,
    val camera: ForgeGraphCamera,
    val layers: List<String> = emptyList(),
)

/** Node card size in world px — the spread the baked layout assumes. */
const val GRAPH_NODE_W = 150.0
const val GRAPH_NODE_H = 36.0

const val GRAPH_MIN_ZOOM = 0.1
const val GRAPH_MAX_ZOOM = 3.2

private fun Any?.numOrNull(): Double? = (this as? Number)?.toDouble()

/** `layoutOf`: a value is a layout only when it carries a nodes array; else the empty layout. */
fun forgeGraphLayoutOf(v: Any?): ForgeGraphLayout {
    val map = v as? Map<String, Any?> ?: return ForgeGraphLayout(emptyList(), emptyList(), ForgeGraphCamera(0.0, 0.0, 1.0))
    val nodes = map["nodes"]?.asMaps() ?: return ForgeGraphLayout(emptyList(), emptyList(), ForgeGraphCamera(0.0, 0.0, 1.0))
    return ForgeGraphLayout(
        nodes = nodes.map { n ->
            ForgeGraphNode(
                id = n["id"].asStr(),
                title = n["title"].asStr(),
                x = n["x"].numOrNull() ?: 0.0,
                y = n["y"].numOrNull() ?: 0.0,
                topo = n["topo"].asInt(),
                layer = n["layer"].asStrOrNull(),
                symbol = n["symbol"].asStrOrNull(),
                file = n["file"].asStrOrNull(),
            )
        },
        edges = map["edges"].asMaps().map { e ->
            ForgeGraphEdge(from = e["from"].asStr(), to = e["to"].asStr(), rel = e["rel"].asStrOrNull())
        },
        camera = map["camera"]?.let { c ->
            val cm = c as? Map<String, Any?> ?: emptyMap()
            ForgeGraphCamera(
                x = cm["x"].numOrNull() ?: 0.0,
                y = cm["y"].numOrNull() ?: 0.0,
                zoom = cm["zoom"].numOrNull() ?: 1.0,
            )
        } ?: ForgeGraphCamera(0.0, 0.0, 1.0),
        layers = map["layers"].asList().map { it.asStr() },
    )
}

/** The three seeded layouts, keyed by mode. */
fun forgeGraphLayoutsOf(seed: Map<String, Any?>): Map<ForgeGraphMode, ForgeGraphLayout> = mapOf(
    ForgeGraphMode.Causal to forgeGraphLayoutOf(seed["graphLayout"]),
    ForgeGraphMode.Concept to forgeGraphLayoutOf(seed["conceptGraph"]),
    ForgeGraphMode.Docs to forgeGraphLayoutOf(seed["docsGraph"]),
)

/** The per-mode empty message. */
fun graphEmptyText(mode: ForgeGraphMode): String = when (mode) {
    ForgeGraphMode.Concept -> "No concept lattice in the seed."
    ForgeGraphMode.Docs -> "No docs mindmap in the seed — bake with a docs/ corpus to populate it."
    ForgeGraphMode.Causal -> "No causal nodes in the seed yet — ingest a donor to populate the graph."
}

/** `fitGraph`'s camera: center on the bounding box, zoom to fill at 90%, clamped. */
fun fitGraphCamera(layout: ForgeGraphLayout, viewportW: Double, viewportH: Double): ForgeGraphCamera? {
    if (layout.nodes.isEmpty()) return null
    var minX = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (n in layout.nodes) {
        minX = min(minX, n.x - GRAPH_NODE_W); maxX = max(maxX, n.x + GRAPH_NODE_W)
        minY = min(minY, n.y - GRAPH_NODE_H); maxY = max(maxY, n.y + GRAPH_NODE_H)
    }
    val zoom = (min(viewportW / (maxX - minX), viewportH / (maxY - minY)) * 0.9)
        .coerceIn(GRAPH_MIN_ZOOM, GRAPH_MAX_ZOOM)
    return ForgeGraphCamera(x = (minX + maxX) / 2, y = (minY + maxY) / 2, zoom = zoom)
}

/** Drag pan: screen delta divided by zoom, subtracted from the camera anchor. */
fun ForgeGraphCamera.pannedBy(dxScreen: Double, dyScreen: Double): ForgeGraphCamera =
    ForgeGraphCamera(x - dxScreen / zoom, y - dyScreen / zoom, zoom)

/** Wheel zoom factor from the DOM deltaY. */
fun graphWheelFactor(deltaY: Double): Double = exp(-deltaY * 0.0015)

/**
 * Pointer-anchored zoom: the world point under the cursor stays fixed.
 * `pointerX/Y` are viewport-relative px; `viewportW/H` the scroll element's size.
 */
fun ForgeGraphCamera.zoomedAt(
    factor: Double,
    pointerX: Double,
    pointerY: Double,
    viewportW: Double,
    viewportH: Double,
): ForgeGraphCamera {
    val wx = x + (pointerX - viewportW / 2) / zoom
    val wy = y + (pointerY - viewportH / 2) / zoom
    val next = (zoom * factor).coerceIn(GRAPH_MIN_ZOOM, GRAPH_MAX_ZOOM)
    val ratio = zoom / next
    return ForgeGraphCamera(x = wx - (wx - x) * ratio, y = wy - (wy - y) * ratio, zoom = next)
}

/** Keyboard zoom: `+`/`=` step up, `-` steps down, factor 1.2. */
fun ForgeGraphCamera.zoomStepped(up: Boolean): ForgeGraphCamera =
    ForgeGraphCamera(x, y, (if (up) zoom * 1.2 else zoom / 1.2).coerceIn(GRAPH_MIN_ZOOM, GRAPH_MAX_ZOOM))

/** One inspector relation row: `→ rel otherTitle` for outgoing, `←` for incoming. */
data class ForgeGraphRelation(val arrow: String, val rel: String, val otherTitle: String)

/** `inspectNode`'s relation derivation: every edge touching the node, oriented. */
fun graphRelationsOf(layout: ForgeGraphLayout, nodeId: String): List<ForgeGraphRelation> =
    layout.edges.filter { it.from == nodeId || it.to == nodeId }.map { e ->
        val outgoing = e.from == nodeId
        val other = if (outgoing) e.to else e.from
        val otherTitle = layout.nodes.firstOrNull { it.id == other }?.title ?: other
        ForgeGraphRelation(
            arrow = if (outgoing) "→ " else "← ",
            rel = e.rel ?: "parent",
            otherTitle = otherTitle,
        )
    }

/** The cubic edge path between two node cards (vertical midpoint control points). */
fun graphEdgePath(a: ForgeGraphNode, b: ForgeGraphNode): String {
    val mx = (a.x + b.x) / 2
    return "M ${a.x} ${a.y + GRAPH_NODE_H / 2} C $mx ${a.y + GRAPH_NODE_H / 2}, $mx ${b.y - GRAPH_NODE_H / 2}, ${b.x} ${b.y - GRAPH_NODE_H / 2}"
}

/** The truncated node label (18 chars + ellipsis). */
fun graphNodeLabel(title: String): String = if (title.length > 18) title.take(17) + "…" else title

/** Layer captions: the topmost node of each layer anchors its label. */
fun graphLayerLabelAnchors(layout: ForgeGraphLayout): Map<String, ForgeGraphNode> {
    val byLayer = mutableMapOf<String, ForgeGraphNode>()
    for (n in layout.nodes) {
        val layer = n.layer ?: continue
        val cur = byLayer[layer]
        if (cur == null || n.y < cur.y) byLayer[layer] = n
    }
    return byLayer
}
