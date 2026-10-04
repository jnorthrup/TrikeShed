package borg.trikeshed.web.spacegraph

import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify

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
        glCanvas = layer(); text = layer()
        val attrs = obj(); attrs.antialias = true
        gl = glCanvas.getContext("webgl", attrs)?.unsafeCast<WebGLRenderingContext>() ?: throw Error("WebGL unavailable")
        program()
        input()
        detail()
        fold()
        finder()
        ask()
        shelve()
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
            if (d != null) { scope.release(t()); if (kotlin.math.hypot(p[0] - d[0], p[1] - d[1]) < 3 && !scope.toggle(p[0], p[1])) scope.select(scope.pick(p[0], p[1])) } })
        host.addEventListener("pointerleave", { _: dynamic -> scope.hovered = -1 })
        document.addEventListener("keydown", { e: dynamic ->
            if (str(e.target?.tagName) == "INPUT") return@addEventListener
            when (str(e.key)) {
                "f", "F" -> { scope.selected = -1; scope.follow() }
                "ArrowRight", "PageDown" -> scope.turn(1)
                "ArrowLeft", "PageUp" -> scope.turn(-1)
                " " -> { e.preventDefault(); if (scope.pinned) scope.follow() else scope.pin() }
                "+", "=" -> scope.wheel(-120.0, 0, host.clientWidth / 2.0, host.clientHeight / 2.0, window.performance.now())
                "-" -> scope.wheel(120.0, 0, host.clientWidth / 2.0, host.clientHeight / 2.0, window.performance.now())
                "Escape" -> { scope.selected = -1; scope.close() }
            }
        })
    }

    /** The detail slider: its value is [CuratorScope.snapPercent]; the tick on its track is [CuratorScope.zoomPercent]. */
    private fun detail() {
        val slider = document.getElementById("curator-detail").asDynamic() ?: return
        slider.value = scope.snapPercent.toString()
        slider.addEventListener("input", { _: dynamic -> scope.snapPercent = num(slider.value) })
    }

    /** Each die panel folds away to give the view the whole width; the button it was pressed on holds the state. */
    private fun fold() {
        val buttons = document.querySelectorAll("header button.fold")
        for (i in 0 until buttons.length) {
            val button = buttons.item(i) as? HTMLElement ?: continue
            val side = document.getElementById(button.getAttribute("data-fold") ?: continue) as? HTMLElement ?: continue
            button.addEventListener("click", { _: dynamic ->
                val min = side.classList.toggle("min")
                button.setAttribute("aria-pressed", min.toString())
                resize()
            })
        }
    }

    /** The finding aids: find by name, pattern query, and the ordered contents; a row click takes the camera there.
     *  Enter in the find box searches the curated leaves on the daemon, the depth box deep; with the Jev box checked,
     *  what either box found goes to Jev and stands in Jev's order. */
    private var tocAt = -2
    /** Bumped per search shown: an answer arriving after the next search began is dropped. */
    private var shownAt = 0
    private fun finder() {
        val toc = document.getElementById("curator-toc") as? HTMLElement ?: return
        val search = document.getElementById("curator-search").asDynamic()
        val query = document.getElementById("curator-query").asDynamic()
        val rank = document.getElementById("curator-rank").asDynamic()
        val depth = document.getElementById("curator-depth").asDynamic()
        fun show(title: String, rows: List<Map<*, *>>, up: Int?) {
            toc.innerHTML = (if (up != null) "<h3><a data-open='$up'>◂ contents</a></h3>" else "") + narchy.spacegraph.CuratorReading.rows(title, rows)
        }
        fun contents(i: Int) {
            tocAt = i; shownAt++
            val rows = scope.contents(i)
            show(if (i < 0) "library" else rows.firstOrNull()?.get("in")?.toString() ?: "contents", rows, if (i < 0) null else -1)
        }
        fun dim(s: String) = "<p class='dim'>" + s.replace("&", "&amp;").replace("<", "&lt;") + "</p>"
        // Rows as found; with the Jev box checked, the same rows again in Jev's order, each with its noul.
        fun found(title: String, q: String, rows: List<Map<*, *>>) {
            val at = ++shownAt; tocAt = -3
            show(title, rows, -1)
            if (!truthy(rank.checked) || rows.isEmpty()) return
            toc.insertAdjacentHTML("afterbegin", dim("asking Jev…"))
            val body = jsonOf(mapOf("q" to q, "rows" to rows))
            launchJs {
                try {
                    val r = fetchJs("/api/curation/rank", objOf { it.method = "POST"; it.body = body })
                    val v = reify(str(awaitJs(r.text()))) as Map<*, *>
                    if (shownAt != at) return@launchJs
                    if (v["ranked"] == true) show("$title · Jev order · ${v["asked"]} asked, ${v["tokens"]} tokens", (v["rows"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }, -1)
                    else { show(title, rows, -1); toc.insertAdjacentHTML("afterbegin", dim("Jev: ${v["error"] ?: v["reason"]}")) }
                } catch (e: dynamic) { if (shownAt == at) { show(title, rows, -1); toc.insertAdjacentHTML("afterbegin", dim("Jev failed: " + failureText(e))) } }
            }
        }
        search.addEventListener("input", { _: dynamic ->
            val q = str(search.value); tocAt = -3; shownAt++
            if (q.isBlank()) contents(-1) else show("find “$q”", scope.find(q), -1)
        })
        search.addEventListener("keydown", { e: dynamic ->
            val q = str(search.value).trim()
            if (str(e.key) != "Enter" || q.isEmpty()) return@addEventListener
            val at = ++shownAt; tocAt = -3
            toc.innerHTML = dim("finding “$q”…")
            val body = jsonOf(mapOf("q" to q, "depth" to (str(depth.value).toIntOrNull() ?: 3).coerceIn(1, 4)))
            launchJs {
                try {
                    val r = fetchJs("/api/curation/find", objOf { it.method = "POST"; it.body = body })
                    val v = reify(str(awaitJs(r.text()))) as Map<*, *>
                    if (shownAt == at) found("found “$q” · ${v["found"]} of ${v["read"]} leaves", q, (v["rows"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> })
                } catch (e: dynamic) { if (shownAt == at) toc.innerHTML = dim("find failed: " + failureText(e)) }
            }
        })
        query.addEventListener("keydown", { e: dynamic ->
            if (str(e.key) == "Enter") { val q = str(query.value); found("statements", q, scope.query(q).map { it + ("text" to it["note"]) }) }
        })
        toc.addEventListener("click", { e: dynamic ->
            val open = e.target.closest("[data-open]")
            if (open != null) { contents(str(open.getAttribute("data-open")).toInt()); return@addEventListener }
            // A row the daemon found names its ring key: the node it stands for once the ring holds it.
            val hit = e.target.closest("[data-ring]")
            if (hit != null) {
                val i = scope.nodeOf(str(hit.getAttribute("data-ring")))
                if (i >= 0) scope.goto(i) else hit.title = "not in the ring yet"
                return@addEventListener
            }
            val row = e.target.closest("[data-node]") ?: return@addEventListener
            scope.goto(str(row.getAttribute("data-node")).toInt())
        })
        // The library's contents appear once the ring has laid it out.
        fun wait() { if (tocAt == -2 && scope.contents(-1).isNotEmpty()) contents(-1) else if (tocAt == -2) window.setTimeout({ wait() }, 500) }
        wait()
        gate(rank)
    }

    /** The Jev box [rank] is live once the daemon's startup question to Jev came back as known; else it stays off, titled why. */
    private fun gate(rank: dynamic) {
        launchJs {
            try {
                val r = fetchJs("/api/curation/rank")
                val v = reify(str(awaitJs(r.text()))) as Map<*, *>
                val ready = v["ready"] == true
                val why = (if (ready) "Jev orders the rows found · " else "Jev unavailable: ") + v["reason"]
                rank.disabled = !ready; rank.title = why; rank.parentElement.title = why
                if (v["reason"] == "probing") window.setTimeout({ gate(rank) }, 3_000)
            } catch (e: dynamic) { rank.title = "Jev unavailable: " + failureText(e) }
        }
    }

    private fun tick() {
        val tick = document.getElementById("curator-zoom") as? HTMLElement ?: return
        val percent = scope.zoomPercent.coerceIn(0.0, 100.0)
        // The range thumb travels inset by half its 16px width; the tick rides the same travel.
        val left = "calc(${kotlin.math.round(percent * 100) / 100}% + ${8 - percent * 16 / 100}px)"
        if (tick.style.left != left) tick.style.left = left
        tick.className = if (percent >= scope.snapPercent) "" else "below"
    }

    /** Each project's documents are the books on its shelf unit. A response is bound before its body is read:
     *  a dynamic call chained on a suspend result runs on the suspension marker, and the coroutine it throws
     *  out of is later resumed by the settled fetch after it completed (the coroutines-machinery fatal). */
    private fun shelve() {
        launchJs {
            try {
                val list = fetchJs("/api/projects")
                val projects = reify(str(awaitJs(list.text()))) as Map<*, *>
                for (p in (projects["scopes"] as? List<*>).orEmpty()) {
                    val name = (p as? Map<*, *>)?.get("name")?.toString() ?: continue
                    val r = fetchJs("/api/projects/" + encodeURIComponent(name) + "/docs")
                    val docs = reify(str(awaitJs(r.text()))) as Map<*, *>
                    scope.shelve(name, (docs["docs"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("id")?.toString() })
                }
            } catch (e: dynamic) { status("shelves unreadable: " + failureText(e)) }
        }
    }

    /** The reading panel's ask box: a typed question, or one the NARS state puts to the page, goes to Jev over the constellation. */
    private fun ask() {
        val el = document.getElementById("curator-panel") ?: return
        fun put(form: dynamic, q: String) {
            // The answer belongs to the page asked from: kept under its key, and written into whichever panel shows it then.
            val key = readingKey
            fun show(html: String) {
                answered.remove(key); answered[key] = q to html; if (answered.size > KEPT) answered.remove(answered.keys.first())
                if (readingKey == key) el.querySelector(".oracle")?.innerHTML = html
            }
            show("<p class='dim'>asking Jev…</p>")
            val body = jsonOf(mapOf("book" to str(form.getAttribute("data-book")),
                "section" to (str(form.getAttribute("data-section")).toIntOrNull() ?: 0), "question" to q))
            launchJs {
                try {
                    val r = fetchJs("/api/curation/jev", objOf { it.method = "POST"; it.body = body })
                    show(narchy.spacegraph.CuratorReading.oracle(reify(str(awaitJs(r.text()))) as Map<*, *>))
                } catch (e: dynamic) { show("<p class='dim'>Jev failed: ${failureText(e)}</p>") }
            }
        }
        el.addEventListener("submit", { e: dynamic ->
            e.preventDefault()
            val q = str(e.target.q.value).trim()
            if (q.isNotEmpty()) put(e.target, q)
        })
        el.addEventListener("click", { e: dynamic ->
            val chip = e.target.closest("[data-ask]") ?: return@addEventListener
            val form = el.querySelector("form.ask") ?: return@addEventListener
            val q = str(chip.getAttribute("data-ask"))
            form.asDynamic().q.value = q
            put(form, q)
        })
    }

    /** The open book's pages ask for what curation read; each answer is folded into its page. */
    private fun pages() {
        for (key in scope.wanted()) launchJs {
            val book = key.removePrefix("book.curate/").substringBeforeLast('/')
            try {
                val r = fetchJs("/api/curation/section?book=" + encodeURIComponent(book) + "&section=" + encodeURIComponent(key.substringAfterLast('/')))
                scope.read(key, reify(str(awaitJs(r.text()))) as Map<*, *>)
            } catch (e: dynamic) { scope.read(key, mapOf("error" to "reading failed: " + failureText(e))) }
        }
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
                val r = fetchJs("/api/lcnc/trail?since=${scope.cursor}&nodes=${scope.nodes}&epoch=${scope.epoch}")
                val body = str(awaitJs(r.text()))
                scope.drain(reify(body) as Map<*, *>, window.performance.now())
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
        tick()
        pages()
    }

    private fun status(s: String) { document.getElementById("curator-status")?.textContent = s }

    /** The illustrative panel: redrawn only when what it shows changes. */
    private var readingKey = ""

    /** Per section ring key, bounded: its panel as last read, and the last question put to Jev there with the answer's panel. */
    private val shown = LinkedHashMap<String, String>()
    private val answered = LinkedHashMap<String, Pair<String, String>>()
    private const val KEPT = 48

    /** The Jev answer kept for [key], back into the panel [el] now shows. */
    private fun restore(el: org.w3c.dom.Element, key: String) {
        val (q, html) = answered[key] ?: return
        el.querySelector(".oracle")?.innerHTML = html
        el.querySelector("form.ask")?.asDynamic()?.q?.value = q
    }

    /** A curated section in view is read whole from the daemon: sentences, concepts, classes, statements, citations.
     *  A section read before shows at once and is read again behind it; the panel is replaced only when that differs. */
    private fun reading(el: org.w3c.dom.Element, key: String) {
        if (key == readingKey) return; readingKey = key
        // book.curate/<book>/<heading>
        val book = key.removePrefix("book.curate/").substringBeforeLast('/')
        val heading = key.substringAfterLast('/')
        val had = shown[key]
        if (had != null) { el.innerHTML = had; restore(el, key) } else el.innerHTML = "<p class='dim'>reading ${heading.replace("<", "&lt;")}…</p>"
        launchJs {
            try {
                val r = fetchJs("/api/curation/section?book=" + encodeURIComponent(book) + "&section=" + encodeURIComponent(heading))
                val body = str(awaitJs(r.text()))
                val v = reify(body) as Map<*, *>
                val html = if (v.containsKey("error")) "<p class='dim'>${v["error"]}</p>" else narchy.spacegraph.CuratorReading.html(v)
                if (!v.containsKey("error")) { shown.remove(key); shown[key] = html; if (shown.size > KEPT) shown.remove(shown.keys.first()) }
                if (readingKey == key && html != had) { el.innerHTML = html; restore(el, key) }
            } catch (e: dynamic) { if (readingKey == key && had == null) el.innerHTML = "<p class='dim'>reading failed: ${failureText(e)}</p>" }
        }
    }

    private fun panel() {
        val el = document.getElementById("curator-panel") ?: return
        val p = scope.panel()
        if (p != null && p["type"] == "book.section" && (scope.selected >= 0 || scope.hovered >= 0)) { panelKey = ""; reading(el, p["key"].toString()); return }
        readingKey = ""
        val key = p?.let { "${it["key"]}|${it["verb"]}|${it["runs"]}|${(it["story"] as List<*>).firstOrNull()}" } ?: ""
        if (key == panelKey) return; panelKey = key
        if (p == null) { el.innerHTML = "<p class='dim'>Waiting for a curation run… start one from /panels or /api/lcnc/run.</p>"; return }
        fun esc(s: Any?) = s.toString().replace("&", "&amp;").replace("<", "&lt;")
        val mean = (p["meanMs"] as? Double)?.let { "${kotlin.math.round(it)} ms avg" } ?: "—"
        el.innerHTML = "<svg class='glyph' viewBox='0 0 44 44' style='color:${p["glyphColor"]}'><path d='${p["glyph"]}' fill='currentColor'/></svg>" +
            "<h2>${esc(p["metaphor"])}<span>${esc(p["verb"])}</span></h2>" +
            "<p class='gloss'>${esc(p["gloss"])}</p>" +
            "<dl><dt>node</dt><dd>${esc(p["key"])}</dd><dt>type</dt><dd>${esc(p["type"])}</dd>" +
            "<dt>worked</dt><dd>${p["runs"]}× · $mean${if (p["active"] == true) " · <b>working now</b>" else ""}</dd></dl>" +
            (if ((p["relations"] as List<*>).isNotEmpty()) "<h3>relations · ${p["degree"]}</h3><ol>" +
                (p["relations"] as List<*>).joinToString("") { "<li>${esc(it)}</li>" } + "</ol>" else "") +
            "<h3>its story</h3><ol>" + (p["story"] as List<*>).joinToString("") { "<li>${esc(it)}</li>" } + "</ol>"
    }
}
