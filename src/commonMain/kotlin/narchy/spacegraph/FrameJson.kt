package narchy.spacegraph

import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*

/** JSON-facing values for platform presenters. Geometry is authored by the common projection. */
object FrameJson {
    private fun point(p: Vec3) = listOf(p.x, p.y, p.z)
    private fun rect(r: Rect) = listOf(r.x, r.y, r.width, r.height)
    fun value(frame: FramePlan): Map<String, Any?> = linkedMapOf(
        "width" to frame.viewport.width, "height" to frame.viewport.height, "pixelRatio" to frame.viewport.pixelRatio,
        "revision" to frame.revision.toString(), "background" to frame.background.css,
        "items" to frame.items.view.map { item ->
            val common = linkedMapOf<String, Any?>("entityId" to item.entityId, "clip" to item.clip?.let(::rect))
            when (item) {
                is DrawItem.Path -> common.putAll(mapOf("type" to "path", "fill" to item.fill?.css, "stroke" to item.stroke?.css,
                    "width" to item.width, "dash" to item.dash.view.toList(), "dashOffset" to item.dashOffset,
                    "path" to item.parts.view.map { part -> when (part) {
                        is PathPart.Move -> listOf("M", part.p.x, part.p.y)
                        is PathPart.Line -> listOf("L", part.p.x, part.p.y)
                        is PathPart.Quadratic -> listOf("Q", part.control.x, part.control.y, part.end.x, part.end.y)
                        is PathPart.Cubic -> listOf("C", part.c1.x, part.c1.y, part.c2.x, part.c2.y, part.end.x, part.end.y)
                        PathPart.Close -> listOf("Z")
                    } }))
                is DrawItem.Text -> common.putAll(mapOf("type" to "text", "text" to item.text, "position" to point(item.position),
                    "color" to item.color.css, "size" to item.size, "font" to item.font, "weight" to item.weight, "maxWidth" to item.maxWidth))
                is DrawItem.Image -> common.putAll(mapOf("type" to "image", "resource" to item.resource, "bounds" to rect(item.bounds)))
                is DrawItem.Content -> common.putAll(mapOf("type" to "content", "kind" to item.kind, "content" to item.content,
                    "bounds" to rect(item.bounds), "capability" to item.requiredCapability.name))
            }
            common
        },
    )
}
