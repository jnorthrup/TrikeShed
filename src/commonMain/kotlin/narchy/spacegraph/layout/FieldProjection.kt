package narchy.spacegraph.layout

// Port of upstream src/layout/field-projection.ts at 125b369.
// Map solved scope frames to screen rectangles. Fields follow the layout target, not the animated body, so a scope
// frame stays square while its cards settle. A renderer that cannot project a world point simply yields no rect;
// the host then keeps its own overlay path.
// No upstream test imports this file; nothing checks this port beyond its gate compile.

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

import narchy.spacegraph.Vec3

/** Screen-space rectangle for one solved scope frame. */
data class FieldRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** A host supplies the projection; the mapping itself stays pure and renderer-independent. */
fun interface FieldProjector {
    fun project(point: Position): ScreenPoint?
}

data class ScreenPoint(val x: Double, val y: Double)

fun scopeFieldRects(
    layout: ScopeLayout?,
    project: FieldProjector,
    padding: Double = 0.0,
): Map<String, FieldRect> {
    val rects = LinkedHashMap<String, FieldRect>()
    if (layout == null) return rects
    for ((id, frame) in layout.frames) {
        val bounds = frame.bounds
        val topLeft = project.project(Vec3(bounds.min.x - padding, bounds.max.y + padding, bounds.min.z))
        val bottomRight = project.project(Vec3(bounds.max.x + padding, bounds.min.y - padding, bounds.min.z))
        if (topLeft == null || bottomRight == null) continue
        rects[id] = FieldRect(
            x = min(topLeft.x, bottomRight.x),
            y = min(topLeft.y, bottomRight.y),
            width = max(1.0, abs(bottomRight.x - topLeft.x)),
            height = max(1.0, abs(bottomRight.y - topLeft.y)),
        )
    }
    return rects
}
