package narchy.spacegraph.graphics.spi

import borg.trikeshed.lib.*
import narchy.spacegraph.Vec3
import kotlin.math.hypot

/** GL consumes this same ordered frame, not a second scene graph. Convex faces are emitted by the common projector. */
object FrameTriangles {
    fun vertices(frame: FramePlan): Series<Join<Vec3, Rgba>> {
        val out = mutableListOf<Join<Vec3, Rgba>>()
        fun triangle(a: Vec3, b: Vec3, c: Vec3, color: Rgba) {
            out.add(a j color); out.add(b j color); out.add(c j color)
        }
        for (item in frame.items.view) {
            if (item !is DrawItem.Path) continue
            require(item.clip == null && item.dash.size == 0) { "Triangle provider requires unclipped solid paths" }
            val points = mutableListOf<Vec3>(); var closed = false
            for (part in item.parts.view) when (part) {
                is PathPart.Move -> { require(points.isEmpty()) { "Compound paths must be decomposed into convex faces" }; points.add(part.p) }
                is PathPart.Line -> points.add(part.p)
                PathPart.Close -> closed = true
                else -> error("Curves must be sampled by the common projection")
            }
            item.fill?.let { color -> for (i in 1 until points.size - 1) triangle(points[0], points[i], points[i + 1], color) }
            item.stroke?.let { color ->
                for (i in 0 until points.size - if (closed) 0 else 1) {
                    val a = points[i]; val b = points[(i + 1) % points.size]
                    val dx = b.x - a.x; val dy = b.y - a.y; val length = hypot(dx, dy)
                    if (length == 0.0) continue
                    val v = Vec3(-dy / length, dx / length) * (item.width / 2)
                    triangle(a + v, a - v, b + v, color); triangle(b + v, a - v, b - v, color)
                }
            }
        }
        return out.toSeries()
    }
}
