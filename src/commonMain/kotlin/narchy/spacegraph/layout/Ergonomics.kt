package narchy.spacegraph.layout

// Port of upstream src/layout/ergonomics.ts at 125b369 (F02).
// Deviation: ErgonomicsViewport is local with Double sizes; the port's Viewport requires positive Int sizes and
// throws on the zero surface that solveErgonomicLayout must answer with undefined (null here).
// Deviation: options are a data class with upstream defaults; minCard/maxCard are Pair<Double, Double>.
// Deviation: the Map and Set iteration order of the TypeScript source is kept with LinkedHashMap and LinkedHashSet.

import narchy.spacegraph.Vec3
import narchy.spacegraph.util.clamp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Host-agnostic inputs: a graph, the surface it must fit, and the legibility floor. */
data class ErgonomicsNode(val id: String, val width: Double? = null, val height: Double? = null)

data class ErgonomicsEdge(val source: String, val target: String, val weight: Double? = null)

data class ErgonomicsViewport(val width: Double, val height: Double)

data class ErgonomicsOptions(
    /** Fraction of the surface the cards should carry, before gaps. Default 0.62. */
    val targetOccupancy: Double? = null,
    /** Smallest card that still fits a label and a status chip. */
    val minCard: Pair<Double, Double>? = null,
    val maxCard: Pair<Double, Double>? = null,
    val gap: Double? = null,
    val maxColumns: Int? = null,
    val refinementPasses: Int? = null,
)

data class ErgonomicsMetrics(
    val occupancy: Double,
    val meanEdgeLength: Double,
    val aspectFit: Double,
    val overlaps: Int,
    val labelFloorMet: Boolean,
    val columns: Int,
    val rows: Int,
    val nodes: Int,
    val score: Double,
)

data class ErgonomicLayout(
    val positions: Map<String, Position>,
    val card: Pair<Double, Double>,
    val columns: Int,
    val metrics: ErgonomicsMetrics,
)

private val DEFAULT_CARD = 180.0 to 72.0

/**
 * Place highly connected nodes first, then the unplaced node with the strongest ties to what is
 * already placed. This is a Cuthill–McKee-style heuristic: linked nodes land in neighbouring slots,
 * so a row-major grid has short edges without any host notion of turns or scopes.
 */
fun orderByProximity(nodes: List<ErgonomicsNode>, edges: List<ErgonomicsEdge>): List<String> {
    val neighbours = LinkedHashMap<String, LinkedHashMap<String, Double>>()
    val degree = LinkedHashMap<String, Double>()
    for (node in nodes) {
        neighbours[node.id] = LinkedHashMap()
        degree[node.id] = 0.0
    }
    for (edge in edges) {
        val weight = edge.weight ?: 1.0
        for ((from, to) in listOf(edge.source to edge.target, edge.target to edge.source)) {
            if (from == to || from !in neighbours || to !in neighbours) continue
            neighbours.getValue(from)[to] = (neighbours.getValue(from)[to] ?: 0.0) + weight
            degree[from] = degree.getValue(from) + weight
        }
    }
    val remaining = LinkedHashSet(nodes.map { it.id })
    val order = mutableListOf<String>()
    while (remaining.isNotEmpty()) {
        var best: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (id in remaining) {
            var ties = 0.0
            for ((other, weight) in neighbours.getValue(id)) if (other !in remaining) ties += weight
            val score = ties * 100 + degree.getValue(id)
            if (score > bestScore || (score == bestScore && best != null && id < best)) {
                best = id
                bestScore = score
            }
        }
        order += best!!
        remaining.remove(best)
    }
    return order
}

/** Swap slots while the total squared edge length falls; bounded so large graphs stay cheap. */
fun refineBySwaps(
    order: List<String>,
    edges: List<ErgonomicsEdge>,
    columns: Int,
    passes: Int = 2,
): List<String> {
    if (order.size < 3 || columns < 1) return order
    val result = order.toMutableList()
    val loc = HashMap<String, Int>()
    result.forEachIndexed { index, id -> loc[id] = index }
    val cost = {
        var total = 0.0
        for (edge in edges) {
            val from = loc[edge.source] ?: continue
            val to = loc[edge.target] ?: continue
            val ax = from % columns
            val ay = floor(from.toDouble() / columns).toInt()
            val bx = to % columns
            val by = floor(to.toDouble() / columns).toInt()
            total += (edge.weight ?: 1.0) * ((ax - bx).toDouble().pow(2) + (ay - by).toDouble().pow(2))
        }
        total
    }
    for (pass in 0 until passes) {
        var changed = false
        for (i in result.indices) {
            for (j in i + 1 until result.size) {
                val before = cost()
                val a = result[i]
                val b = result[j]
                result[i] = b
                result[j] = a
                loc[b] = i
                loc[a] = j
                if (cost() < before) changed = true
                else {
                    result[i] = a
                    result[j] = b
                    loc[a] = i
                    loc[b] = j
                }
            }
        }
        if (!changed) break
    }
    return result
}

/** Column count whose grid aspect is closest to the surface aspect. */
fun chooseColumns(count: Int, viewport: ErgonomicsViewport, maxColumns: Int = 4): Int {
    if (count < 1) return 1
    val aspect = viewport.width / maxOf(viewport.height, 1.0)
    val ideal = sqrt(count * aspect)
    return clamp(floor(ideal + 0.5), 1.0, maxOf(1, maxColumns).toDouble()).toInt()
}

private fun overlapsFor(
    positions: Map<String, Position>,
    nodes: List<ErgonomicsNode>,
    card: Pair<Double, Double>,
): Int {
    var overlaps = 0
    for (i in nodes.indices) {
        val a = positions[nodes[i].id] ?: continue
        for (j in i + 1 until nodes.size) {
            val b = positions[nodes[j].id] ?: continue
            if (abs(a.x - b.x) < card.first && abs(a.y - b.y) < card.second) overlaps++
        }
    }
    return overlaps
}

private fun scoreOf(metrics: ErgonomicsMetrics, scale: Double): Double =
    metrics.occupancy * 0.4 +
        metrics.aspectFit * 0.25 +
        (1 / (1 + metrics.meanEdgeLength / maxOf(1.0, scale))) * 0.35 -
        metrics.overlaps * 10 -
        (if (metrics.labelFloorMet) 0.0 else 2.0)

/**
 * Solve a compact, legible grid for any graph and surface. No DOM, renderer or host vocabulary:
 * the caller supplies sizes, edges and a viewport; the result is solved positions plus measured
 * ergonomics so hosts can compare candidates instead of trusting a screenshot.
 */
fun solveErgonomicLayout(
    nodes: List<ErgonomicsNode>,
    edges: List<ErgonomicsEdge>,
    viewport: ErgonomicsViewport,
    options: ErgonomicsOptions = ErgonomicsOptions(),
): ErgonomicLayout? {
    if (nodes.isEmpty() || viewport.width <= 0 || viewport.height <= 0) return null
    val gap = options.gap ?: 16.0
    val minCard = options.minCard ?: DEFAULT_CARD
    val maxCard = options.maxCard ?: (viewport.width to viewport.height)
    val target = clamp(options.targetOccupancy ?: 0.62, 0.1, 0.95)
    val maxColumns = options.maxColumns ?: 4
    val passes = options.refinementPasses ?: 2
    val proximity = orderByProximity(nodes, edges)

    var best: ErgonomicLayout? = null
    for (columns in 1..minOf(maxColumns, nodes.size)) {
        val rows = ceil(nodes.size.toDouble() / columns).toInt()
        val card = clamp((viewport.width - gap * (columns - 1)) / columns, minCard.first, maxCard.first) to
            clamp((viewport.height - gap * (rows - 1)) / rows, minCard.second, maxCard.second)
        val order = refineBySwaps(proximity, edges, columns, passes)
        val positions = LinkedHashMap<String, Position>()
        order.forEachIndexed { index, id ->
            positions[id] = Vec3(
                (index % columns) * (card.first + gap),
                floor(index.toDouble() / columns) * (card.second + gap),
                0.0,
            )
        }
        val gridWidth = columns * card.first + (columns - 1) * gap
        val gridHeight = rows * card.second + (rows - 1) * gap
        var edgeLength = 0.0
        var edgeCount = 0
        for (edge in edges) {
            val from = positions[edge.source] ?: continue
            val to = positions[edge.target] ?: continue
            edgeLength += hypot(from.x - to.x, from.y - to.y) * (edge.weight ?: 1.0)
            edgeCount++
        }
        val occupancy = (columns * rows * card.first * card.second) / (viewport.width * viewport.height)
        val ratio = ln((gridWidth / gridHeight) / (viewport.width / viewport.height))
        val partial = ErgonomicsMetrics(
            occupancy = minOf(occupancy, target) / target,
            meanEdgeLength = if (edgeCount > 0) edgeLength / edgeCount else 0.0,
            aspectFit = 1 / (1 + abs(ratio)),
            overlaps = overlapsFor(positions, nodes, card),
            labelFloorMet = card.first >= minCard.first && card.second >= minCard.second,
            columns = columns,
            rows = rows,
            nodes = nodes.size,
            score = 0.0,
        )
        val score = scoreOf(partial, hypot(viewport.width, viewport.height))
        if (best == null || score > best.metrics.score)
            best = ErgonomicLayout(positions, card, columns, partial.copy(score = score))
    }
    return best
}
