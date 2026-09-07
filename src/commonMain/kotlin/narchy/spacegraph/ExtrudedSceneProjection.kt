package narchy.spacegraph

import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*
import kotlin.math.*

object ExtrudedSceneProjection {
    /** The sole solid projection for every provider; text is a shared screen-space overlay. */
    fun frame(scene: ExtrudedScene, camera: GraphCamera, viewport: Viewport, selected: String? = null): FramePlan {
        val items = mutableListOf<Join<Double, DrawItem>>()
        val labels = mutableListOf<DrawItem>()
        val faceIndices = listOf(listOf(0, 1, 3, 2), listOf(4, 6, 7, 5), listOf(0, 4, 5, 1),
            listOf(2, 3, 7, 6), listOf(0, 2, 6, 4), listOf(1, 5, 7, 3))
        for (n in scene.nodes.view) {
            val outline = if (n.id == selected) Rgba(227, 80, 103) else n.color
            for (solid in n.solids.view) for ((faceIndex, face) in faceIndices.withIndex()) {
                val corners: Series<Vec3> = 8 j { i -> Vec3(
                    if (i and 1 == 0) solid.min.x else solid.max.x,
                    if (i and 2 == 0) solid.min.y else solid.max.y,
                    if (i and 4 == 0) solid.min.z else solid.max.z) }
                val points = face.map { camera.project(corners[it], viewport) }
                if (points.any { it == null }) continue
                val p = points.filterNotNull()
                val parts = (listOf<PathPart>(PathPart.Move(p[0])) + p.drop(1).map { PathPart.Line(it) } + PathPart.Close).toSeries()
                val fill = if (n.scope) n.color
                    else if (faceIndex == 1) Rgba(244, 246, 247) else n.color
                items.add(p.sumOf { it.z } / 4 j DrawItem.Path(n.id, parts, fill, outline, if (n.id == selected) 2.0 else .65))
            }
            val top = camera.project(n.position + Vec3(-n.size.x / 2 + 12 * n.scale, n.size.y / 2 - 23 * n.scale, n.size.z / 2 + n.scale), viewport)
            val projected = n.corners.view.mapNotNull { camera.project(it, viewport) }
            if (top != null && projected.size == 8) {
                val left = projected.minOf { it.x }; val right = projected.maxOf { it.x }
                val upper = projected.minOf { it.y }; val lower = projected.maxOf { it.y }
                val size = (12.0 * n.scale * (right - left) / n.size.x).coerceIn(9.0, 16.0)
                if (right - left >= 42 && lower - upper >= size + 8) {
                    val clip = Rect(left, upper, right - left, lower - upper)
                    val x = top.x.coerceIn(left + 3, right - 4)
                    val y = top.y.coerceIn(upper + size + 2, lower - 2)
                    val width = max(1.0, right - x - 4)
                    fun fit(text: String, fontSize: Double): String {
                        val count = max(1, (width / (fontSize * .61)).toInt())
                        return if (text.length <= count) text else text.take(max(0, count - 2)) + ".."
                    }
                    labels.add(DrawItem.Text(n.id, fit(if (width < 120) n.type else n.title, size), Vec3(x, y), Rgba(37, 45, 51), size,
                        font = "monospace", maxWidth = width, clip = clip))
                    if (!n.scope && size >= 9 && lower - top.y > size * 4) {
                        val lines = listOf(n.type) + n.details.view.take(5).map { "${it.a}: ${it.b}" }
                        for ((i, line) in lines.withIndex()) {
                            val baseline = y + (i + 1) * size * 1.6
                            if (baseline > lower - size) break
                            labels.add(DrawItem.Text(n.id, fit(line, size * .85), Vec3(x, baseline), Rgba(82, 103, 117), size * .85,
                                font = "monospace", maxWidth = width, clip = clip))
                        }
                    }
                }
            }
            for (port in n.ports.view) {
                val p = camera.project(port.position, viewport) ?: continue
                val parts: Series<PathPart> = 14 j { i: Int ->
                    val q = p + Vec3(cos(i * PI / 6) * 3, sin(i * PI / 6) * 3)
                    if (i == 0) PathPart.Move(q) else if (i == 13) PathPart.Close else PathPart.Line(q)
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
        return FramePlan(viewport, (items.sortedByDescending { it.a }.map { it.b } + labels).toSeries(), Rgba(235, 239, 240))
    }
}
