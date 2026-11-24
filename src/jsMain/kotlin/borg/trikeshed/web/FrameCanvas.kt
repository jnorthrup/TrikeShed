package borg.trikeshed.web

import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*
import org.w3c.dom.CanvasRenderingContext2D

/** Draws a common frame on a 2D canvas, item by item in the frame's order: paths filled then stroked, text as placed. */
object FrameCanvas {
    fun draw(c: CanvasRenderingContext2D, frame: FramePlan) {
        c.fillStyle = frame.background.css
        c.fillRect(0.0, 0.0, frame.viewport.width.toDouble(), frame.viewport.height.toDouble())
        for (k in 0 until frame.items.size) {
            val item = frame.items[k]
            val clip = item.clip
            if (clip != null) { c.save(); c.beginPath(); c.rect(clip.x, clip.y, clip.width, clip.height); c.clip() }
            when (item) {
                is DrawItem.Path -> {
                    c.beginPath()
                    for (p in item.parts.view) when (p) {
                        is PathPart.Move -> c.moveTo(p.p.x, p.p.y)
                        is PathPart.Line -> c.lineTo(p.p.x, p.p.y)
                        is PathPart.Quadratic -> c.quadraticCurveTo(p.control.x, p.control.y, p.end.x, p.end.y)
                        is PathPart.Cubic -> c.bezierCurveTo(p.c1.x, p.c1.y, p.c2.x, p.c2.y, p.end.x, p.end.y)
                        PathPart.Close -> c.closePath()
                    }
                    item.fill?.let { c.fillStyle = it.css; c.fill() }
                    item.stroke?.let {
                        c.strokeStyle = it.css; c.lineWidth = item.width
                        if (item.dash.size > 0) { c.setLineDash(item.dash.view.toList().toTypedArray()); c.lineDashOffset = item.dashOffset }
                        c.stroke()
                        if (item.dash.size > 0) c.setLineDash(arrayOf())
                    }
                }
                is DrawItem.Text -> {
                    c.font = "${item.weight} ${item.size}px ${if (item.font == "monospace") "ui-monospace,Menlo,monospace" else "system-ui,sans-serif"}"
                    c.fillStyle = item.color.css
                    val w = item.maxWidth
                    if (w != null) c.fillText(item.text, item.position.x, item.position.y, w) else c.fillText(item.text, item.position.x, item.position.y)
                }
                is DrawItem.Image, is DrawItem.Content -> Unit
            }
            if (clip != null) c.restore()
        }
    }
}
