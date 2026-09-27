package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import narchy.spacegraph.CuratorScope
import narchy.spacegraph.Viewport
import narchy.spacegraph.graphics.spi.DrawItem
import narchy.spacegraph.graphics.spi.FrameTriangles
import org.khronos.webgl.Float32Array
import org.khronos.webgl.WebGLRenderingContext
import org.khronos.webgl.WebGLRenderingContext.Companion.ARRAY_BUFFER
import org.khronos.webgl.WebGLRenderingContext.Companion.BLEND
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_BUFFER_BIT
import org.khronos.webgl.WebGLRenderingContext.Companion.DYNAMIC_DRAW
import org.khronos.webgl.WebGLRenderingContext.Companion.FLOAT
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAGMENT_SHADER
import org.khronos.webgl.WebGLRenderingContext.Companion.ONE_MINUS_SRC_ALPHA
import org.khronos.webgl.WebGLRenderingContext.Companion.SRC_ALPHA
import org.khronos.webgl.WebGLRenderingContext.Companion.TRIANGLES
import org.khronos.webgl.WebGLRenderingContext.Companion.VERTEX_SHADER
import org.khronos.webgl.set
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

/**
 * /curator: the live curation scope. GL draws the common frame's triangles, a 2D overlay its text,
 * and the side panel the metaphor of the node in view; the ring is drained every [POLL_MS].
 * Decisions (layout, camera, follow, trail, spikes, metaphors) are CuratorScope's.
 */
object CuratorPage {
    private const val POLL_MS = 250
    private val scope = CuratorScope()
    private lateinit var host: HTMLElement
    private lateinit var gl: WebGLRenderingContext
    private lateinit var glCanvas: HTMLCanvasElement
    private lateinit var text: HTMLCanvasElement
    private var buffer: dynamic = null
    private var capacity = 0
    private var ratio = 1.0
    private var drag: DoubleArray? = null
    private var down: DoubleArray? = null
    private var panelKey = ""

    fun mount() {
        host = document.getElementById("curator") as HTMLElement
        window.location.search.let { q -> Regex("hold=([0-9.]+)").find(q)?.let { scope.hold = it.groupValues[1].toDouble() * 1000 } }
        glCanvas = layer(); text = layer()
        val attrs = obj(); attrs.antialias = true
        gl = glCanvas.getContext("webgl", attrs)?.unsafeCast<WebGLRenderingContext>() ?: throw Error("WebGL unavailable")
        program()
        input()
        jsNew(js("ResizeObserver"), { _: dynamic -> resize() }).observe(host)
        resize()
        poll()
        fun loop(t: Double) { draw(t); window.requestAnimationFrame(::loop) }
        window.requestAnimationFrame(::loop)
    }

    private fun layer(): HTMLCanvasElement = (document.createElement("canvas") as HTMLCanvasElement).also {
        it.style.cssText = "position:absolute;inset:0;width:100%;height:100%;pointer-events:none"; host.append(it)
    }

    private fun program() {
        fun shader(type: Int, src: String) = gl.createShader(type)!!.also { gl.shaderSource(it, src); gl.compileShader(it) }
        val p = gl.createProgram()!!
        gl.attachShader(p, shader(VERTEX_SHADER, "attribute vec2 point; attribute vec4 color; varying vec4 rgba; void main(){ gl_Position=vec4(point,0.,1.); rgba=color; }"))
        gl.attachShader(p, shader(FRAGMENT_SHADER, "precision mediump float; varying vec4 rgba; void main(){ gl_FragColor=rgba; }"))
        gl.linkProgram(p); gl.useProgram(p)
        buffer = gl.createBuffer(); gl.bindBuffer(ARRAY_BUFFER, buffer)
        val point = gl.getAttribLocation(p, "point"); val color = gl.getAttribLocation(p, "color")
        gl.enableVertexAttribArray(point); gl.vertexAttribPointer(point, 2, FLOAT, false, 24, 0)
        gl.enableVertexAttribArray(color); gl.vertexAttribPointer(color, 4, FLOAT, false, 24, 8)
        gl.enable(BLEND); gl.blendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)
    }

    private fun input() {
        fun at(e: dynamic): DoubleArray { val r = host.getBoundingClientRect(); return doubleArrayOf(num(e.clientX) - r.left, num(e.clientY) - r.top) }
        val opts = obj(); opts.passive = false
        fun t() = window.performance.now()
        host.addEventListener("wheel", { e: dynamic -> e.preventDefault(); val p = at(e)
            scope.wheel(num(e.deltaY), num(e.deltaMode).toInt(), p[0], p[1], t()) }, opts)
        host.addEventListener("pointerdown", { e: dynamic -> if (num(e.button) == 0.0) { val p = at(e); drag = p; down = p
            scope.press(p[0], p[1], t()); host.asDynamic().setPointerCapture(e.pointerId) } })
        host.addEventListener("pointermove", { e: dynamic ->
            val p = at(e); val d = drag
            if (d != null) { scope.drag(p[0] - d[0], p[1] - d[1], p[0], p[1], t()); drag = p } else scope.hovered = scope.pick(p[0], p[1])
        })
        host.addEventListener("pointerup", { e: dynamic -> val p = at(e); val d = down; drag = null; down = null
            if (d != null) { scope.release(t()); if (kotlin.math.hypot(p[0] - d[0], p[1] - d[1]) < 3) scope.selected = scope.pick(p[0], p[1]) } })
        host.addEventListener("pointerleave", { _: dynamic -> scope.hovered = -1 })
        document.addEventListener("keydown", { e: dynamic ->
            when (str(e.key)) {
                "f", "F" -> { scope.selected = -1; scope.follow() }
                " " -> { e.preventDefault(); if (scope.pinned) scope.follow() else scope.pin() }
                "+", "=" -> scope.wheel(-120.0, 0, host.clientWidth / 2.0, host.clientHeight / 2.0, window.performance.now())
                "-" -> scope.wheel(120.0, 0, host.clientWidth / 2.0, host.clientHeight / 2.0, window.performance.now())
                "Escape" -> scope.selected = -1
            }
        })
    }

    private fun resize() {
        ratio = window.devicePixelRatio.let { if (it == 0.0) 1.0 else it }
        val w = maxOf(1, host.clientWidth); val h = maxOf(1, host.clientHeight)
        scope.viewport = Viewport(w, h)
        for (c in listOf(glCanvas, text)) { c.width = (w * ratio).toInt(); c.height = (h * ratio).toInt() }
    }

    private fun poll() {
        launchJs {
            try {
                val r = fetchJs("/api/lcnc/trail?since=${scope.cursor}&nodes=${scope.nodes}")
                val body = str(awaitJs(r.text()))
                scope.drain(borg.trikeshed.parse.json.JsonSupport.parse(body) as Map<*, *>, window.performance.now())
                status("live · ${scope.nodes} nodes")
            } catch (e: dynamic) { status("ring unreachable: " + failureText(e)) }
            window.setTimeout({ poll() }, POLL_MS)
        }
    }

    private fun draw(t: Double) {
        val frame = scope.frame(t)
        val vs = FrameTriangles.vertices(frame); val n = vs.a
        val floats = Float32Array(n * 6)
        val vw = frame.viewport.width.toDouble(); val vh = frame.viewport.height.toDouble()
        for (i in 0 until n) {
            val (p, c) = vs.b(i); val o = i * 6
            floats[o] = (p.x / vw * 2 - 1).toFloat(); floats[o + 1] = (1 - p.y / vh * 2).toFloat()
            floats[o + 2] = (c.red / 255.0).toFloat(); floats[o + 3] = (c.green / 255.0).toFloat()
            floats[o + 4] = (c.blue / 255.0).toFloat(); floats[o + 5] = c.alpha.toFloat()
        }
        gl.viewport(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight)
        gl.clearColor(11 / 255f, 14 / 255f, 20 / 255f, 1f); gl.clear(COLOR_BUFFER_BIT)
        if (floats.byteLength > capacity) { capacity = floats.byteLength * 2; gl.bufferData(ARRAY_BUFFER, capacity, DYNAMIC_DRAW) }
        gl.bufferSubData(ARRAY_BUFFER, 0, floats); gl.drawArrays(TRIANGLES, 0, n)
        val c: dynamic = text.getContext("2d")
        c.setTransform(ratio, 0, 0, ratio, 0, 0); c.clearRect(0, 0, vw, vh)
        for (k in 0 until frame.items.a) { val item = frame.items.b(k); if (item !is DrawItem.Text) continue
            c.font = "${item.weight} ${item.size}px ${if (item.font == "monospace") "ui-monospace,Menlo,monospace" else "system-ui,sans-serif"}"
            c.fillStyle = item.color.css
            if (item.maxWidth != null) c.fillText(item.text, item.position.x, item.position.y, item.maxWidth) else c.fillText(item.text, item.position.x, item.position.y)
        }
        panel()
    }

    private fun status(s: String) { document.getElementById("curator-status")?.textContent = s }

    /** The illustrative panel: redrawn only when what it shows changes. */
    private fun panel() {
        val el = document.getElementById("curator-panel") ?: return
        val p = scope.panel()
        val key = p?.let { "${it["key"]}|${it["verb"]}|${it["runs"]}|${(it["story"] as List<*>).firstOrNull()}" } ?: ""
        if (key == panelKey) return; panelKey = key
        if (p == null) { el.innerHTML = "<p class='dim'>Waiting for a curation run… start one from /panels or /api/lcnc/run.</p>"; return }
        fun esc(s: Any?) = s.toString().replace("&", "&amp;").replace("<", "&lt;")
        val mean = (p["meanMs"] as? Double)?.let { "${kotlin.math.round(it)} ms avg" } ?: "—"
        el.innerHTML = "<div class='glyph' style='background:${p["color"]}'></div>" +
            "<h2>${esc(p["metaphor"])}<span>${esc(p["verb"])}</span></h2>" +
            "<p class='gloss'>${esc(p["gloss"])}</p>" +
            "<dl><dt>node</dt><dd>${esc(p["key"])}</dd><dt>type</dt><dd>${esc(p["type"])}</dd>" +
            "<dt>worked</dt><dd>${p["runs"]}× · $mean${if (p["active"] == true) " · <b>working now</b>" else ""}</dd></dl>" +
            "<h3>its story</h3><ol>" + (p["story"] as List<*>).joinToString("") { "<li>${esc(it)}</li>" } + "</ol>"
    }
}
