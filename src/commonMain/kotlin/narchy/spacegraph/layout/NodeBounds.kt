package narchy.spacegraph.layout

// Port of upstream src/layout/node-bounds.ts at 125b369 (the radius part the force core reads).
// Not ported from this file: declaredNodeBounds and resolveNodeBounds read untyped `data` / `size` records from the
// upstream node model, which the port does not have. The rest is recorded in the report.

/** Extra padding between nodes used by layout defaults. */
const val DEFAULT_NODE_PADDING = 20.0

/**
 * Half a node's bounding-box diagonal, plus optional padding.
 * Upstream uses Math.hypot; sqrt(w*w + h*h) is used here, which can differ in the last bit.
 */
fun diagonalRadius(width: Double, height: Double, padding: Double = 0.0): Double =
    kotlin.math.sqrt(width * width + height * height) / 2 + padding
