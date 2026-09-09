package narchy.spacegraph.graphics.spi

import borg.trikeshed.lib.*
import narchy.spacegraph.Vec3
import narchy.spacegraph.Rect
import kotlin.math.hypot

/** GL consumes this same ordered frame, not a second scene graph. Convex faces are emitted by the common projector. */
object FrameTriangles {
    fun vertices(frame: FramePlan): Series<Join<Vec3, Rgba>> {
        val out = mutableListOf<Join<Vec3, Rgba>>()
        fun triangle(a: Vec3, b: Vec3, c: Vec3, color: Rgba, clip: Rect?) {
            if (clip == null || clip.contains(a) && clip.contains(b) && clip.contains(c)) {
                out.add(a j color); out.add(b j color); out.add(c j color)
                return
            }
            var polygon = listOf(a, b, c)
            // Clip the emitted triangle, including stroke thickness, to the shared frame rectangle.
            if (clip != null) for (edge in 0..3) {
                if (polygon.isEmpty()) break
                fun distance(p: Vec3) = when (edge) { 0 -> p.x - clip.x; 1 -> clip.right - p.x; 2 -> p.y - clip.y; else -> clip.bottom - p.y }
                val next = mutableListOf<Vec3>()
                var previous = polygon.last(); var d0 = distance(previous)
                for (point in polygon) {
                    val d1 = distance(point)
                    if ((d0 >= 0) != (d1 >= 0)) next.add(previous.lerp(point, d0 / (d0 - d1)))
                    if (d1 >= 0) next.add(point)
                    previous = point; d0 = d1
                }
                polygon = next
            }
            for (i in 1 until polygon.size - 1) {
                out.add(polygon[0] j color); out.add(polygon[i] j color); out.add(polygon[i + 1] j color)
            }
        }
        for (item in frame.items.view) {
            if (item !is DrawItem.Path) continue
            require(item.dash.size == 0) { "Triangle provider requires solid paths" }
            val points = mutableListOf<Vec3>(); var closed = false
            for (part in item.parts.view) when (part) {
                is PathPart.Move -> { require(points.isEmpty()) { "Compound paths must be decomposed into convex faces" }; points.add(part.p) }
                is PathPart.Line -> points.add(part.p)
                PathPart.Close -> closed = true
                else -> error("Curves must be sampled by the common projection")
            }
            item.fill?.let { color -> for (i in 1 until points.size - 1) triangle(points[0], points[i], points[i + 1], color, item.clip) }
            item.stroke?.let { color ->
                for (i in 0 until points.size - if (closed) 0 else 1) {
                    val a = points[i]; val b = points[(i + 1) % points.size]
                    val dx = b.x - a.x; val dy = b.y - a.y; val length = hypot(dx, dy)
                    if (length == 0.0) continue
                    val v = Vec3(-dy / length, dx / length) * (item.width / 2)
                    triangle(a + v, a - v, b + v, color, item.clip); triangle(b + v, a - v, b - v, color, item.clip)
                }
            }
        }
        return out.toSeries()
    }
}
