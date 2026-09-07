package narchy.spacegraph

import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*
import kotlin.math.*

/** Pure graph-to-frame projection. Rendering and layout never revise source evidence. */
object SceneProjection {
    fun frame(graph: SpaceGraph, viewport: Viewport, selected: Set<String> = emptySet(), time: Double = 0.0): FramePlan {
        val snapshot = graph.snapshot(); val items = mutableListOf<DrawItem>(); val boxes = mutableMapOf<String, Rect>()
        fun screenBox(n: NodeSpec): Rect? {
            val m = graph.worldMatrix(n.id)
            val corners = listOf(Vec3(-n.data.width / 2, -n.data.height / 2), Vec3(n.data.width / 2, -n.data.height / 2),
                Vec3(n.data.width / 2, n.data.height / 2), Vec3(-n.data.width / 2, n.data.height / 2))
                .mapNotNull { graph.camera.project(m.transform(it), viewport) }
            if (corners.size != 4) return null
            return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x } - corners.minOf { it.x }, corners.maxOf { it.y } - corners.minOf { it.y })
        }
        for (n in snapshot.nodes.view) if (graph.isVisible(n.id)) screenBox(n)?.let { boxes[n.id] = it }
        fun clip(n: NodeSpec): Rect? {
            var current = n.parent; var result = viewport.bounds
            while (current != null) {
                val parent = graph.node(current) ?: return null
                if (parent.data.flag("clip", true)) result = result.intersection(boxes[current] ?: return null) ?: return null
                current = parent.parent
            }
            return result
        }
        for (e in snapshot.edges.view) {
            val a = boxes[e.source] ?: continue; val b = boxes[e.target] ?: continue
            val start = boundary(a, b.center); val end = boundary(b, a.center)
            val color = Rgba.parse(e.data.string("color", "#64748b"))
            val parts = mutableListOf<PathPart>(PathPart.Move(start))
            val curved = e.kind in setOf(EdgeKind.CurvedEdge, EdgeKind.FlowEdge, EdgeKind.BundledEdge, EdgeKind.Wire)
            val bend = (end.x - start.x) / 2
            if (curved) parts.add(PathPart.Cubic(Vec3(start.x + bend, start.y), Vec3(end.x - bend, end.y), end)) else parts.add(PathPart.Line(end))
            val dashed = e.data.flag("dashed") || e.kind in setOf(EdgeKind.DottedEdge, EdgeKind.FlowEdge, EdgeKind.AnimatedEdge)
            val width = e.data.number("thickness", if (e.kind == EdgeKind.DynamicThicknessEdge) 1 + 3 * (sin(time) + 1) / 2 else 1.5).coerceAtLeast(0.1)
            val dash = if (dashed) listOf(e.data.number("dashSize", 5.0), e.data.number("gapSize", 4.0)).toSeries() else emptySeriesOf()
            items.add(DrawItem.Path(e.id, parts.toSeries(), stroke = color, width = width, dash = dash,
                dashOffset = if (e.kind in setOf(EdgeKind.FlowEdge, EdgeKind.AnimatedEdge)) -time * 24 else 0.0, clip = viewport.bounds))
            val arrow = e.data.string("arrowhead")
            if (arrow == "true" || arrow == "target" || arrow == "both") items.add(arrow(e.id, end, if (curved) Vec3(end.x - bend, end.y) else start, color, e.data.number("arrowheadSize", 8.0)))
            if (arrow == "source" || arrow == "both") items.add(arrow(e.id, start, if (curved) Vec3(start.x + bend, start.y) else end, color, e.data.number("arrowheadSize", 8.0)))
            val label = e.data.string("label")
            if (label.isNotEmpty()) items.add(DrawItem.Text(e.id, label, (start + end) * 0.5 + Vec3(4.0, -5.0), color, e.data.number("fontSize", 12.0), clip = viewport.bounds))
        }
        fun depth(n: NodeSpec): Int = n.parent?.let { 1 + depth(requireNotNull(graph.node(it))) } ?: 0
        for (n in snapshot.nodes.view.sortedBy(::depth)) {
            val box = boxes[n.id] ?: continue; val clipping = clip(n) ?: continue
            if (!box.intersects(clipping) || min(box.width, box.height) < 0.5) continue
            val fill = Rgba.parse(n.data.string("color", if (n.parent == null && graph.children(n.id).size > 0) "#e5e7eb" else "#ffffff"))
                .copy(alpha = n.data.number("opacity", 1.0).coerceIn(0.0, 1.0))
            val border = if (n.id in selected) Rgba(3, 105, 161) else Rgba(148, 163, 184)
            val round = n.data.string("shape") in setOf("circle", "sphere", "ring") || n.kind == NodeKind.PortNode
            val outline = if (round) ellipse(box) else rectangle(box)
            items.add(DrawItem.Path(n.id, outline, fill, border, if (n.id in selected) 2.5 else 1.0, clip = clipping))
            val textClip = box.intersection(clipping) ?: continue
            val fontSize = n.data.number("fontSize", 14.0).coerceAtLeast(1.0)
            val text = n.data.string("text", n.label)
            items.add(DrawItem.Text(n.id, text, Vec3(box.x + 10, box.y + minOf(22.0, box.height - 3)),
                Rgba.parse(n.data.string("textColor", "#17212b")), fontSize, maxWidth = (box.width - 20).coerceAtLeast(1.0), clip = textClip))
            when (n.kind) {
                NodeKind.ImageNode -> items.add(DrawItem.Image(n.id, n.data.string("url"), Rect(box.x + 4, box.y + 28, (box.width - 8).coerceAtLeast(1.0), (box.height - 32).coerceAtLeast(1.0)), textClip))
                NodeKind.HtmlNode, NodeKind.DOMNode, NodeKind.IFrameNode, NodeKind.CodeEditorNode,
                NodeKind.MarkdownNode, NodeKind.MathNode, NodeKind.ChartNode, NodeKind.AudioNode, NodeKind.VideoNode -> {
                    val capability = when (n.kind) { NodeKind.AudioNode -> GraphicsCapability.AUDIO; NodeKind.VideoNode -> GraphicsCapability.VIDEO
                        NodeKind.CodeEditorNode -> GraphicsCapability.EDITABLE_TEXT; else -> GraphicsCapability.DOM }
                    val value = when (n.kind) { NodeKind.HtmlNode, NodeKind.DOMNode -> n.data.string("html")
                        NodeKind.CodeEditorNode -> n.data.string("code"); NodeKind.MathNode -> n.data.string("math")
                        NodeKind.AudioNode, NodeKind.IFrameNode -> n.data.string("src"); NodeKind.VideoNode -> n.data.string("url")
                        else -> n.data.string("content") }
                    items.add(DrawItem.Content(n.id, n.kind.name, value, Rect(box.x + 4, box.y + 28, (box.width - 8).coerceAtLeast(1.0), (box.height - 32).coerceAtLeast(1.0)), capability, textClip))
                }
                NodeKind.ProgressBarNode, NodeKind.MeterNode, NodeKind.SliderNode -> {
                    val range = (n.data.number("max", 1.0) - n.data.number("min")).coerceAtLeast(1e-12)
                    val fraction = ((n.data.number("value") - n.data.number("min")) / range).coerceIn(0.0, 1.0)
                    val track = Rect(box.x + 10, box.bottom - 18, (box.width - 20).coerceAtLeast(0.0), 6.0)
                    items.add(DrawItem.Path(n.id, rectangle(track), Rgba(226, 232, 240), clip = textClip))
                    items.add(DrawItem.Path(n.id, rectangle(track.copy(width = track.width * fraction)), Rgba(13, 148, 136), clip = textClip))
                }
                NodeKind.ToggleNode -> items.add(DrawItem.Path(n.id, ellipse(Rect(box.right - 28, box.bottom - 24, 14.0, 14.0)),
                    if (n.data.flag("value")) Rgba(13, 148, 136) else Rgba(148, 163, 184), clip = textClip))
                else -> Unit
            }
        }
        return FramePlan(viewport, items.toSeries(), revision = graph.revision)
    }

    fun rectangle(r: Rect): Series<PathPart> = listOf(PathPart.Move(Vec3(r.x, r.y)), PathPart.Line(Vec3(r.right, r.y)),
        PathPart.Line(Vec3(r.right, r.bottom)), PathPart.Line(Vec3(r.x, r.bottom)), PathPart.Close).toSeries()
    fun ellipse(r: Rect): Series<PathPart> {
        val rx = r.width / 2; val ry = r.height / 2; val cx = r.center.x; val cy = r.center.y; val k = 0.5522847498307936
        return listOf(PathPart.Move(Vec3(cx + rx, cy)),
            PathPart.Cubic(Vec3(cx + rx, cy + ry * k), Vec3(cx + rx * k, cy + ry), Vec3(cx, cy + ry)),
            PathPart.Cubic(Vec3(cx - rx * k, cy + ry), Vec3(cx - rx, cy + ry * k), Vec3(cx - rx, cy)),
            PathPart.Cubic(Vec3(cx - rx, cy - ry * k), Vec3(cx - rx * k, cy - ry), Vec3(cx, cy - ry)),
            PathPart.Cubic(Vec3(cx + rx * k, cy - ry), Vec3(cx + rx, cy - ry * k), Vec3(cx + rx, cy)), PathPart.Close).toSeries()
    }
    private fun boundary(r: Rect, target: Vec3): Vec3 {
        val d = target - r.center
        if (d.length < 1e-12) return r.center
        val t = minOf(if (abs(d.x) < 1e-12) Double.POSITIVE_INFINITY else r.width / 2 / abs(d.x),
            if (abs(d.y) < 1e-12) Double.POSITIVE_INFINITY else r.height / 2 / abs(d.y))
        return r.center + d * t
    }
    private fun arrow(id: String, tip: Vec3, previous: Vec3, color: Rgba, size: Double): DrawItem.Path {
        val d = (tip - previous).normalized(); val perpendicular = Vec3(-d.y, d.x)
        return DrawItem.Path(id, listOf(PathPart.Move(tip), PathPart.Line(tip - d * size + perpendicular * size * 0.5),
            PathPart.Line(tip - d * size - perpendicular * size * 0.5), PathPart.Close).toSeries(), color)
    }
}
