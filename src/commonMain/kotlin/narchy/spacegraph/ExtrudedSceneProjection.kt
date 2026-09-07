package narchy.spacegraph

import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*
import kotlin.math.*

object ExtrudedSceneProjection {
    /** Canvas and SVG receive a projection of the same solid faces and port coordinates as GL. */
    fun frame(scene: ExtrudedScene, camera: GraphCamera, viewport: Viewport): FramePlan {
        val items = mutableListOf<Join<Double, DrawItem>>()
        val faceIndices = listOf(listOf(0, 1, 3, 2), listOf(4, 6, 7, 5), listOf(0, 4, 5, 1),
            listOf(2, 3, 7, 6), listOf(0, 2, 6, 4), listOf(1, 5, 7, 3))
        for (n in scene.nodes.view) {
            val corners = n.corners
            for ((faceIndex, face) in faceIndices.withIndex()) {
                val points = face.map { camera.project(corners[it], viewport) }
                if (points.any { it == null }) continue
                val p = points.filterNotNull()
                val parts = (listOf<PathPart>(PathPart.Move(p[0])) + p.drop(1).map { PathPart.Line(it) } + PathPart.Close).toSeries()
                val fill = if (n.scope) Rgba(n.color.red, n.color.green, n.color.blue, .045)
                    else if (faceIndex == 1) Rgba(244, 246, 247) else n.color
                items.add(p.sumOf { it.z } / 4 j DrawItem.Path(n.id, parts, fill, n.color, if (n.scope) 1.2 else .65))
            }
            val top = camera.project(n.position + Vec3(-n.size.x / 2 + 12, n.size.y / 2 - 23, n.size.z / 2 + 1), viewport)
            val projected = corners.view.mapNotNull { camera.project(it, viewport) }
            if (top != null && projected.size == 8) {
                val left = projected.minOf { it.x }; val right = projected.maxOf { it.x }
                val upper = projected.minOf { it.y }; val lower = projected.maxOf { it.y }
                val size = min(12.0, 12.0 * (right - left) / n.size.x)
                if (size >= 5.0 && lower - upper >= size + 4) {
                    val clip = Rect(left, upper, right - left, lower - upper)
                    items.add((top.z - .1) j DrawItem.Text(n.id, n.title, top, Rgba(37, 45, 51), size,
                        maxWidth = max(1.0, right - top.x - 4), clip = clip))
                }
            }
            for (port in n.ports.view) {
                val p = camera.project(port.position, viewport) ?: continue
                val parts: Series<PathPart> = 13 j { i: Int ->
                    val q = p + Vec3(cos(i * PI / 6) * 3, sin(i * PI / 6) * 3)
                    if (i == 0) PathPart.Move(q) else PathPart.Line(q)
                }
                items.add((p.z - .2) j DrawItem.Path(n.id, parts, n.color))
            }
        }
        for (c in scene.cables.view) {
            val p = (0..32).mapNotNull { i ->
                val t = i / 32.0; val u = 1 - t
                camera.project(c.points[0] * (u*u*u) + c.points[1] * (3*u*u*t) + c.points[2] * (3*u*t*t) + c.points[3] * (t*t*t), viewport)
            }
            if (p.size < 2) continue
            items.add(p.sumOf { it.z } / p.size j DrawItem.Path(c.id,
                (listOf<PathPart>(PathPart.Move(p.first())) + p.drop(1).map { PathPart.Line(it) }).toSeries(), stroke = Rgba(25, 145, 139), width = 1.6))
        }
        return FramePlan(viewport, items.sortedByDescending { it.a }.map { it.b }.toSeries(), Rgba(235, 239, 240))
    }
}
