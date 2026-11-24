package narchy.spacegraph.layout

import narchy.spacegraph.Vec3
import kotlin.math.max
import kotlin.math.min

// Port of upstream src/layout/bounds.ts at 125b369.
// Upstream has no test for this file; the test cases in BoundsTest.kt are worked by hand from the source.

open class LayoutBounds(val min: Position, val max: Position) {
    override fun equals(other: Any?): Boolean = other is LayoutBounds && min == other.min && max == other.max

    override fun hashCode(): Int = min.hashCode() * 31 + max.hashCode()
}

class CenterBounds(min: Position, max: Position, val center: Position, val size: DoubleArray) :
    LayoutBounds(min, max)

val ZERO_BOUNDS: LayoutBounds = LayoutBounds(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0))

/** Build the min/max/centre/size view from six extents. */
fun boundsFromExtents(
    minX: Double, minY: Double, minZ: Double,
    maxX: Double, maxY: Double, maxZ: Double,
): CenterBounds = CenterBounds(
    min = Vec3(minX, minY, minZ),
    max = Vec3(maxX, maxY, maxZ),
    center = Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2),
    size = doubleArrayOf(maxX - minX, maxY - minY, maxZ - minZ),
)

/** Accumulate extents over points; null when there are none. */
fun accumulateBounds(points: Iterable<Position>): CenterBounds? {
    var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var minZ = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY; var maxZ = Double.NEGATIVE_INFINITY
    var any = false
    for (point in points) {
        any = true
        minX = min(minX, point.x); minY = min(minY, point.y); minZ = min(minZ, point.z)
        maxX = max(maxX, point.x); maxY = max(maxY, point.y); maxZ = max(maxZ, point.z)
    }
    return if (any) boundsFromExtents(minX, minY, minZ, maxX, maxY, maxZ) else null
}

/** Affinely move and scale the extents of a bounds value. */
fun transformBounds(bounds: LayoutBounds, origin: Position, scale: Double): CenterBounds =
    boundsFromExtents(
        origin.x + bounds.min.x * scale,
        origin.y + bounds.min.y * scale,
        origin.z + bounds.min.z * scale,
        origin.x + bounds.max.x * scale,
        origin.y + bounds.max.y * scale,
        origin.z + bounds.max.z * scale,
    )

/** The smallest bounds enclosing both inputs. */
fun unionBounds(a: LayoutBounds, b: LayoutBounds): CenterBounds =
    boundsFromExtents(
        min(a.min.x, b.min.x), min(a.min.y, b.min.y), min(a.min.z, b.min.z),
        max(a.max.x, b.max.x), max(a.max.y, b.max.y), max(a.max.z, b.max.z),
    )

/** Axis-aligned bounds of the placed positions, or zero bounds when there are none. */
fun boundsOf(points: List<Position>): LayoutBounds {
    val accumulated = accumulateBounds(points)
    return if (accumulated != null) LayoutBounds(accumulated.min, accumulated.max) else ZERO_BOUNDS
}

fun boundsOfMap(positions: Map<String, Position>): LayoutBounds = boundsOf(positions.values.toList())
