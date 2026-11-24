package narchy.spacegraph.layout

// Port of upstream src/layout/incremental.ts at 125b369.
// The seam between two layout results: which boxes are new, which disappeared, which moved. Time is never stored
// here; a renderer interpolates `moved` from its own clock, so the layout stays pure and endpoints are exact at t=0
// and t=1. Upstream's optional z (`from.z ?? 0`) is non-optional here because Position is Vec3.

import narchy.spacegraph.Vec3

data class LayoutMove(val id: String, val from: Position, val to: Position)

data class IncrementalLayout(
    val positions: Map<String, Position>,
    val added: Map<String, Position>,
    val removed: List<String>,
    /** Exactly the ids both layouts place differently. */
    val moved: Map<String, LayoutMove>,
)

fun interpolatePosition(from: Position, to: Position, t: Double): Position = Vec3(
    from.x + (to.x - from.x) * t,
    from.y + (to.y - from.y) * t,
    from.z + (to.z - from.z) * t,
)

fun computeIncremental(positions: Map<String, Position>, previous: Map<String, Position>? = null): IncrementalLayout {
    if (previous == null) {
        return IncrementalLayout(positions, LinkedHashMap(positions), emptyList(), emptyMap())
    }

    val added = positions.filterKeys { it !in previous }
    val removed = previous.keys.filter { it !in positions }
    val moved = LinkedHashMap<String, LayoutMove>()
    for ((id, to) in positions) {
        val from = previous[id] ?: continue
        if (from.x == to.x && from.y == to.y && from.z == to.z) continue
        moved[id] = LayoutMove(id, from, to)
    }
    return IncrementalLayout(positions, added, removed, moved)
}

val diffPositions: (Map<String, Position>, Map<String, Position>?) -> IncrementalLayout = ::computeIncremental

/**
 * Applies the clock to one incremental result. Ids without a move keep their target position; ids that moved are
 * interpolated. The same `t` is valid for any number of views.
 */
fun interpolatedPositions(layout: IncrementalLayout, t: Double): Map<String, Position> {
    if (t >= 1) return layout.positions
    val result = LinkedHashMap<String, Position>()
    for ((id, to) in layout.positions) {
        val move = layout.moved[id]
        result[id] = if (move != null) interpolatePosition(move.from, move.to, t) else to
    }
    return result
}
