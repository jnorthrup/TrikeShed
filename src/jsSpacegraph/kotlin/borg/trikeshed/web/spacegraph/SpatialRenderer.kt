package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import narchy.spacegraph.BrowserSpatialEngine
import org.khronos.webgl.Float32Array
import org.khronos.webgl.WebGLBuffer
import org.khronos.webgl.WebGLProgram
import org.khronos.webgl.WebGLRenderingContext
import org.khronos.webgl.WebGLRenderingContext.Companion.ARRAY_BUFFER
import org.khronos.webgl.WebGLRenderingContext.Companion.BLEND
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_BUFFER_BIT
import org.khronos.webgl.WebGLRenderingContext.Companion.COMPILE_STATUS
import org.khronos.webgl.WebGLRenderingContext.Companion.DYNAMIC_DRAW
import org.khronos.webgl.WebGLRenderingContext.Companion.FLOAT
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAGMENT_SHADER
import org.khronos.webgl.WebGLRenderingContext.Companion.LINK_STATUS
import org.khronos.webgl.WebGLRenderingContext.Companion.ONE_MINUS_SRC_ALPHA
import org.khronos.webgl.WebGLRenderingContext.Companion.SRC_ALPHA
import org.khronos.webgl.WebGLRenderingContext.Companion.TRIANGLES
import org.khronos.webgl.WebGLRenderingContext.Companion.VERTEX_SHADER
import org.khronos.webgl.WebGLShader
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement

/** What the spatial workspace hears from its renderer. */
interface SpatialCallbacks {
    fun select(id: String?, port: dynamic)
    fun moveNode(id: String, dx: Double, dy: Double, commit: Boolean)
    fun viewChanged()
    fun unavailable(message: String)
}

/**
 * SpatialRenderer.mjs: the Kotlin scene engine drawn through one of three providers — GL
 * (org.khronos.webgl, triangles from the engine), Canvas 2D, or the engine's SVG projection.
 */
class SpatialRenderer(private val host: HTMLElement, private val callbacks: SpatialCallbacks) {
    val engine = BrowserSpatialEngine()
    var backend = "canvas"
    var mode = "pan"
    private val output: HTMLElement = (document.createElement("div") as HTMLElement).also { it.className = "sg-output"; host.append(it) }
    private val canvas = (document.createElement("canvas") as HTMLCanvasElement).also {
        it.setAttribute("aria-label", "LCNC extruded scene")
        it.style.cssText = "position:absolute;inset:0;width:100%;height:100%;pointer-events:none"
    }
    private val context: dynamic = canvas.getContext("2d")
    private val textCache = HashMap<String, String>()
    private var svgSource: String? = null
    private val fonts: dynamic = window.getComputedStyle(host)
    private val pointers = LinkedHashMap<Int, DoubleArray>()
    private var packet: dynamic = null
    private var hasCamera = false
    private var renderRequest = 0
    private var gesture: dynamic = null
    private var pinch: DoubleArray? = null
    private var gl: WebGLRenderingContext? = null
    private var glCanvas: HTMLCanvasElement? = null
    private var program: WebGLProgram? = null
    private var buffer: WebGLBuffer? = null
    private var bufferCapacity = 0
    private var uploadedVertices: dynamic = null
    private var width = 1.0
    private var height = 1.0
    private var ratio = 1.0
    var vertices = 0
    private val fontsChanged: (dynamic) -> Unit = { textCache.clear(); svgSource = null; render() }
    private val handlers = LinkedHashMap<String, (dynamic) -> Unit>()
    private val resizeObserver: dynamic

    init {
        document.asDynamic().fonts?.addEventListener("loadingdone", fontsChanged)
        fun pinchOf(): DoubleArray? {
            val v = pointers.values.toList(); if (v.size < 2) return null
            val a = v[0]; val b = v[1]
            return doubleArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, kotlin.math.hypot(b[0] - a[0], b[1] - a[1]))
        }
        handlers["pointerdown"] = { e ->
            if (num(e.button) <= 2) {
                val (x, y) = point(e); val hit = engine.pick(x, y)
                val n = arr(packet?.nodes).firstOrNull { it.id == hit?.nodeId }
                pointers[e.pointerId as Int] = doubleArrayOf(x, y); host.asDynamic().setPointerCapture(e.pointerId)
                if (pointers.size > 1) { finishDrag(false); gesture = null; pinch = pinchOf() }
                else {
                    val g = obj(); g.x = x; g.y = y; g.lastX = x; g.lastY = y; g.button = e.button; g.pan = truthy(e.shiftKey) || e.button != 0; g.hit = hit
                    if (mode == "move" && e.button == 0 && n != null) {
                        val d = obj(); d.id = n.id; d.z = n.position[2]; d.point = engine.unproject(x, y, num(n.position[2])); d.dx = 0.0; d.dy = 0.0; g.drag = d
                    }
                    gesture = g
                    host.asDynamic().setPointerCapture(e.pointerId)
                }
            }
        }
        handlers["pointermove"] = { e ->
            if (pointers.containsKey(e.pointerId as Int)) { val (px, py) = point(e); pointers[e.pointerId as Int] = doubleArrayOf(px, py) }
            val next = pinchOf(); val old = pinch
            if (next != null && old != null) {
                engine.pan(next[0] - old[0], next[1] - old[1]); if (old[2] > 0 && next[2] > 0) engine.zoom(next[2] / old[2], next[0], next[1]); pinch = next; changed()
            } else {
                val g = gesture
                if (g != null) {
                    val (x, y) = point(e); val dx = x - num(g.lastX); val dy = y - num(g.lastY)
                    g.lastX = x; g.lastY = y
                    if (g.drag != null) {
                        val p = engine.unproject(x, y, num(g.drag.z))
                        if (p != null && g.drag.point != null) {
                            val ddx = num(p[0]) - num(g.drag.point[0]); val ddy = num(g.drag.point[1]) - num(p[1])
                            callbacks.moveNode(str(g.drag.id), ddx, ddy, false); g.drag.dx = num(g.drag.dx) + ddx; g.drag.dy = num(g.drag.dy) + ddy; g.drag.point = p
                        }
                    } else { engine.pan(dx, dy); changed() }
                }
            }
        }
        handlers["pointerup"] = { e ->
            val g = gesture; finishDrag(true); gesture = null; pointers.remove(e.pointerId as Int); pinch = null
            if (truthy(host.asDynamic().hasPointerCapture(e.pointerId))) host.asDynamic().releasePointerCapture(e.pointerId)
            if (g != null && kotlin.math.hypot(num(g.lastX) - num(g.x), num(g.lastY) - num(g.y)) < 5 && e.button == 0)
                callbacks.select(g.hit?.nodeId?.unsafeCast<String>(), g.hit?.port)
        }
        handlers["pointercancel"] = { e -> finishDrag(false); gesture = null; pointers.remove(e.pointerId as Int); pinch = null }
        handlers["wheel"] = { e ->
            e.preventDefault(); val (x, y) = point(e)
            engine.zoom(kotlin.math.exp(-maxOf(-200.0, minOf(200.0, num(e.deltaY))) * .004), x, y); changed()
        }
        handlers["dblclick"] = { e -> val hit = hit(e); if (hit != null) focus(str(hit.nodeId)) }
        handlers["contextmenu"] = { e -> e.preventDefault() }
        val opts = obj(); opts.passive = false
        for ((event, handler) in handlers) host.addEventListener(event, handler, opts)
        resizeObserver = jsNew(js("ResizeObserver"), { _: dynamic -> resize() })
        resizeObserver.observe(host)
    }

    private fun point(e: dynamic): Pair<Double, Double> { val r = host.getBoundingClientRect(); return (num(e.clientX) - r.left) to (num(e.clientY) - r.top) }

    private fun finishDrag(commit: Boolean) {
        val d = gesture?.drag ?: return
        if (num(d.dx) != 0.0 || num(d.dy) != 0.0) callbacks.moveNode(str(d.id), if (commit) 0.0 else -num(d.dx), if (commit) 0.0 else -num(d.dy), commit)
    }

    fun hit(e: dynamic): dynamic { val (x, y) = point(e); return engine.pick(x, y) }

    fun project(name: String, document: dynamic, geometry: dynamic, spacing: Double, reset: Boolean = false): dynamic {
        engine.resize(maxOf(1, host.clientWidth), maxOf(1, host.clientHeight))
        packet = engine.project(name, JSON.stringify(document), JSON.stringify(geometry), spacing, reset || !hasCamera)
        hasCamera = true; render(); return packet
    }

    fun cameraValue(): dynamic = if (hasCamera) engine.camera() else null

    private fun changed() {
        if (renderRequest == 0) renderRequest = window.requestAnimationFrame { renderRequest = 0; render() }
        callbacks.viewChanged()
    }

    fun fit() { engine.fit(); changed() }
    fun zoom(factor: Double) { engine.zoom(factor, host.clientWidth / 2.0, host.clientHeight / 2.0); changed() }
    fun focus(id: String) {
        if (width != host.clientWidth.toDouble() || height != host.clientHeight.toDouble()) resize()
        engine.focus(id); changed()
    }
    fun highlight(id: String?) { engine.select(id); render() }

    fun setBackend(value: String) {
        if (value !in listOf("gl", "canvas", "svg")) throw Error("Unknown provider $value")
        if (value == "gl" && gl == null) openGL()
        backend = value; output.asDynamic().replaceChildren()
        svgSource = null
        if (value == "gl") output.append(glCanvas!!, canvas)
        else if (value == "canvas") output.append(canvas)
        resize()
    }

    private fun openGL() {
        val c = (document.createElement("canvas") as HTMLCanvasElement)
        c.setAttribute("aria-label", "LCNC GL geometry")
        c.style.cssText = "position:absolute;inset:0;width:100%;height:100%;pointer-events:none"
        val attrs = obj(); attrs.antialias = true; attrs.preserveDrawingBuffer = true
        val g = c.getContext("webgl", attrs)?.unsafeCast<WebGLRenderingContext>() ?: throw Error("WebGL unavailable")
        val shaders = ArrayList<WebGLShader>()
        fun shader(type: Int, source: String): WebGLShader {
            val s = g.createShader(type)!!; g.shaderSource(s, source); g.compileShader(s); shaders.add(s)
            if (g.getShaderParameter(s, COMPILE_STATUS) != true) throw Error(g.getShaderInfoLog(s) ?: "")
            return s
        }
        val prog = g.createProgram()!!
        try {
            g.attachShader(prog, shader(VERTEX_SHADER, "attribute vec2 point; attribute vec4 color; varying vec4 rgba; void main(){ gl_Position=vec4(point,0.,1.); rgba=color; }"))
            g.attachShader(prog, shader(FRAGMENT_SHADER, "precision mediump float; varying vec4 rgba; void main(){ gl_FragColor=rgba; }"))
            g.linkProgram(prog); if (g.getProgramParameter(prog, LINK_STATUS) != true) throw Error(g.getProgramInfoLog(prog) ?: "")
        } catch (error: Throwable) { g.deleteProgram(prog); throw error }
        finally { shaders.forEach { g.deleteShader(it) } }
        gl = g; glCanvas = c; program = prog; buffer = g.createBuffer()
        bufferCapacity = 0; uploadedVertices = null
        g.useProgram(prog); g.bindBuffer(ARRAY_BUFFER, buffer)
        val position = g.getAttribLocation(prog, "point"); val color = g.getAttribLocation(prog, "color")
        g.enableVertexAttribArray(position); g.vertexAttribPointer(position, 2, FLOAT, false, 24, 0)
        g.enableVertexAttribArray(color); g.vertexAttribPointer(color, 4, FLOAT, false, 24, 8)
        g.enable(BLEND); g.blendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA)
        c.addEventListener("webglcontextlost", { e -> e.preventDefault(); callbacks.unavailable("WebGL context lost; using Canvas") })
        c.addEventListener("webglcontextrestored", { gl = null; program = null; buffer = null })
    }

    fun resize() {
        width = maxOf(1, host.clientWidth).toDouble(); height = maxOf(1, host.clientHeight).toDouble()
        ratio = window.devicePixelRatio.let { if (it == 0.0) 1.0 else it }; engine.resize(width.toInt(), height.toInt())
        for (c in listOfNotNull(canvas, glCanvas)) {
            val w = num(js("Math.round")(width * ratio)).toInt(); val h = num(js("Math.round")(height * ratio)).toInt()
            if (c.width != w) c.width = w; if (c.height != h) c.height = h
        }
        render()
    }

    private fun font(family: String): String = when (family) {
        "monospace" -> str(fonts.getPropertyValue("--mono")).trim().ifEmpty { family }
        "sans-serif" -> str(fonts.getPropertyValue("--sans")).trim().ifEmpty { family }
        else -> family
    }

    private fun text(text: String, size: Double, family: String, weight: Double, maxWidth: Double?): String {
        val f = str(if (weight == 0.0) 400.0 else weight) + " " + str(size) + "px " + font(family)
        val c = context
        c.font = f; c.fontKerning = "normal"; c.letterSpacing = "0px"
        val key = JSON.stringify(arrayOf(f, maxWidth, text))
        textCache[key]?.let { return it }
        var shown = text
        if (maxWidth != null && num(c.measureText(text).width) > maxWidth) {
            val chars = arr(js("Array.from(text)")).map { str(it) }; val ellipsis = "\u2026"
            var low = 0; var high = chars.size
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (num(c.measureText(chars.subList(0, mid).joinToString("") + ellipsis).width) <= maxWidth) low = mid else high = mid - 1
            }
            shown = if (num(c.measureText(ellipsis).width) <= maxWidth) chars.subList(0, low).joinToString("") + ellipsis else ""
        }
        if (textCache.size >= 2048) textCache.clear()
        textCache[key] = shown; return shown
    }

    fun render() {
        if (!hasCamera) return
        if (backend == "svg") {
            val source = engine.svg(); if (svgSource == source) return; svgSource = source
            val doc = jsNew(js("DOMParser")).parseFromString(source, "image/svg+xml")
            if (doc.querySelector("parsererror") != null) throw Error("Invalid common SVG projection")
            for (label in arr(doc.querySelectorAll("text"))) {
                val family = str(label.getAttribute("font-family")); val w = label.getAttribute("data-max-width")
                val weight = num(label.getAttribute("font-weight")).let { if (it.isNaN() || it == 0.0) 400.0 else it }
                label.textContent = text(str(label.textContent), num(label.getAttribute("font-size")), family, weight, if (truthy(w)) num(w) else null)
                label.setAttribute("font-family", font(family)); label.style.letterSpacing = "0px"; label.style.fontKerning = "normal"
            }
            output.asDynamic().replaceChildren(document.asDynamic().importNode(doc.documentElement, true)); return
        }
        val data = engine.frame(backend == "gl"); val c = context
        c.setTransform(ratio, 0, 0, ratio, 0, 0); c.clearRect(0, 0, width, height)
        if (backend == "gl") {
            val g = gl!!
            g.viewport(0, 0, g.drawingBufferWidth, g.drawingBufferHeight); g.clearColor(16 / 255f, 20 / 255f, 27 / 255f, 1f); g.clear(COLOR_BUFFER_BIT)
            val verts = data.vertices.unsafeCast<Float32Array>()
            if (uploadedVertices !== verts) {
                if (bufferCapacity == 0 || bufferCapacity < verts.byteLength) { bufferCapacity = maxOf(4096, verts.byteLength * 2); g.bufferData(ARRAY_BUFFER, bufferCapacity, DYNAMIC_DRAW) }
                g.bufferSubData(ARRAY_BUFFER, 0, verts); uploadedVertices = verts
            }
            g.drawArrays(TRIANGLES, 0, verts.length / 6)
            vertices = verts.length / 6
        } else { c.fillStyle = data.background; c.fillRect(0, 0, data.width, data.height) }
        for (item in arr(data.items)) {
            c.save()
            if (item.clip != null) { c.beginPath(); c.rect(item.clip[0], item.clip[1], item.clip[2], item.clip[3]); c.clip() }
            if (item.type == "path" && backend == "canvas") {
                c.beginPath()
                for (seg in arr(item.path)) {
                    val a = arr(seg)
                    when (str(a[0])) {
                        "M" -> c.moveTo(a[1], a[2]); "L" -> c.lineTo(a[1], a[2]); "Q" -> c.quadraticCurveTo(a[1], a[2], a[3], a[4])
                        "C" -> c.bezierCurveTo(a[1], a[2], a[3], a[4], a[5], a[6]); "Z" -> c.closePath()
                    }
                }
                if (truthy(item.fill)) { c.fillStyle = item.fill; c.fill() }
                if (truthy(item.stroke)) { c.strokeStyle = item.stroke; c.lineWidth = item.width; c.setLineDash(item.dash); c.lineDashOffset = item.dashOffset; c.stroke() }
            } else if (item.type == "text") {
                val w = num(item.weight).let { if (it.isNaN() || it == 0.0) 0.0 else it }
                val t = text(str(item.text), num(item.size), str(item.font), w, if (item.maxWidth == null) null else num(item.maxWidth))
                c.fillStyle = item.color; c.fillText(t, item.position[0], item.position[1])
            }
            c.restore()
        }
    }

    fun destroy() {
        window.cancelAnimationFrame(renderRequest); document.asDynamic().fonts?.removeEventListener("loadingdone", fontsChanged); textCache.clear(); resizeObserver.disconnect()
        for ((event, handler) in handlers) host.removeEventListener(event, handler)
        gl?.let { g -> g.deleteBuffer(buffer); g.deleteProgram(program) }
        output.remove()
    }
}
