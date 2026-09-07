package narchy.spacegraph.graphics.spi

import borg.trikeshed.lib.*
import narchy.spacegraph.Viewport

/** Deterministic SVG encoding; resource access and displaying the result belong to the caller. */
class SvgGraphicsProvider : GraphicsOperations {
    override val capabilities = GraphicsCapabilities("common-svg", GraphicsBackend.SVG,
        setOf(GraphicsCapability.PATHS, GraphicsCapability.TEXT, GraphicsCapability.IMAGES), false)
    private var opened = false
    var document: String = ""; private set
    override suspend fun open(viewport: Viewport) { check(!opened); opened = true }
    override suspend fun close() { opened = false; document = "" }
    override suspend fun render(frame: FramePlan): FrameReceipt {
        check(opened)
        val result = encode(frame); document = result.a
        return result.b
    }
    companion object {
        fun escape(value: String): String = buildString {
            for (c in value) append(when (c) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; else -> c.toString() })
        }
        fun path(parts: Series<PathPart>): String = parts.view.joinToString(" ") { p -> when (p) {
            is PathPart.Move -> "M ${p.p.x} ${p.p.y}"; is PathPart.Line -> "L ${p.p.x} ${p.p.y}"
            is PathPart.Quadratic -> "Q ${p.control.x} ${p.control.y} ${p.end.x} ${p.end.y}"
            is PathPart.Cubic -> "C ${p.c1.x} ${p.c1.y} ${p.c2.x} ${p.c2.y} ${p.end.x} ${p.end.y}"
            PathPart.Close -> "Z"
        } }
        fun encode(frame: FramePlan): Join<String, FrameReceipt> {
            val refusals = mutableListOf<GraphicsRefusal>(); var drawn = 0
            val doc = buildString {
                append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${frame.viewport.width}\" height=\"${frame.viewport.height}\" viewBox=\"0 0 ${frame.viewport.width} ${frame.viewport.height}\" role=\"img\">")
                append("<rect width=\"100%\" height=\"100%\" fill=\"${frame.background.css}\"/>")
                frame.items.view.forEachIndexed { index, item ->
                    if (item is DrawItem.Content) {
                        refusals.add(GraphicsRefusal(item.entityId, item.requiredCapability, "Interactive content requires a content provider"))
                        return@forEachIndexed
                    }
                    item.clip?.let { r ->
                        append("<defs><clipPath id=\"sg-clip-$index\"><rect x=\"${r.x}\" y=\"${r.y}\" width=\"${r.width}\" height=\"${r.height}\"/></clipPath></defs>")
                    }
                    append("<g data-entity=\"${escape(item.entityId)}\"")
                    if (item.clip != null) append(" clip-path=\"url(#sg-clip-$index)\"")
                    append('>')
                    when (item) {
                        is DrawItem.Path -> {
                            append("<path d=\"${path(item.parts)}\" fill=\"${item.fill?.css ?: "none"}\" stroke=\"${item.stroke?.css ?: "none"}\" stroke-width=\"${item.width}\" stroke-linejoin=\"round\" stroke-linecap=\"round\"")
                            if (item.dash.size > 0) append(" stroke-dasharray=\"${item.dash.view.joinToString(" ")}\" stroke-dashoffset=\"${item.dashOffset}\"")
                            append("/>")
                        }
                        is DrawItem.Text -> append("<text x=\"${item.position.x}\" y=\"${item.position.y}\" fill=\"${item.color.css}\" font-size=\"${item.size}\" font-family=\"${escape(item.font)}\">${escape(item.text)}</text>")
                        is DrawItem.Image -> {
                            val r = item.bounds
                            append("<image x=\"${r.x}\" y=\"${r.y}\" width=\"${r.width}\" height=\"${r.height}\" href=\"${escape(item.resource)}\" preserveAspectRatio=\"xMidYMid meet\"/>")
                        }
                        is DrawItem.Content -> Unit
                    }
                    append("</g>"); drawn++
                }
                append("</svg>")
            }
            return doc j FrameReceipt("common-svg", frame.revision, drawn, refusals.toSeries())
        }
    }
}
