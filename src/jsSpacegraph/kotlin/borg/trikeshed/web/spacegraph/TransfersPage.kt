package borg.trikeshed.web.spacegraph

import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoHyperarchy
import borg.trikeshed.parse.reify
import borg.trikeshed.transfers.*
import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import narchy.spacegraph.GroundScene
import narchy.spacegraph.Viewport
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URLSearchParams

/**
 * /transfers: what every transport reports, on the SUMO ground ([TransferGround] on a [GroundScene]). The canvas draws
 * the scene's frame, input goes to the scene, the URL keeps its view (`#x=…&y=…&z=…&pitch=…&yaw=…&path=…`) and the
 * transfer in hand (`?transfer=`), and the side panel shows that transfer with the link to what asked for it. Rows come
 * from /api/transfers every [POLL_MS]; the hyperarchy once from /api/sumo/hyperarchy.
 */
object TransfersPage {
    internal val page = PanelsPage { status("Page action failed: " + failureText(it)) }
    const val POLL_MS = 1000
    val scene = GroundScene()
    lateinit var host: HTMLElement
    lateinit var canvas: HTMLCanvasElement
    var ratio = 1.0
    var rows: Series<TransferRow> = emptySeriesOf()
    var dirty = true
    /** Where the pointer went down, and where it was last while held. */
    var press: DoubleArray? = null
    var last: DoubleArray? = null
    var turning = false
    /** The operator has moved the camera, or the URL gave a view: polling no longer frames the transfers. */
    var touched = window.location.hash.isNotEmpty()
    var animation = 0
    var pollTimer = 0
    var resizeObserver: dynamic = null

    fun mount() { page.mount { install() } }

    fun close() = page.close()

    fun install() {
        page.own { window.cancelAnimationFrame(animation); window.clearTimeout(pollTimer); resizeObserver?.disconnect() }
        host = document.getElementById("transfers") as HTMLElement
        canvas = (document.createElement("canvas") as HTMLCanvasElement).also {
            it.style.cssText = "position:absolute;inset:0;width:100%;height:100%"; host.append(it)
        }
        scene.selected = URLSearchParams(window.location.search).get("transfer")
        if (touched) scene.restore(window.location.hash)
        input()
        resizeObserver = jsNew(js("ResizeObserver"), { _: dynamic -> if (!page.closing) resize() })
        resizeObserver.observe(host)
        resize()
        page.submit {
            try {
                val r = fetchJs("/api/sumo/hyperarchy")
                if (!truthy(r.ok)) error("HTTP " + str(r.status))
                scene.load(SumoHyperarchy.of(reify(str(awaitJs(r.text()))) as Map<*, *>))
                settle(); dirty = true
            } catch (e: dynamic) { status("SUMO hyperarchy unavailable: " + failureText(e)) }
        }
        poll()
        fun loop(t: Double) { if (!page.closing) { draw(); animation = window.requestAnimationFrame(::loop) } }
        animation = window.requestAnimationFrame(::loop)
    }

    /** Until the operator takes the camera: the transfer named in the URL, else every transfer, in view. */
    fun settle() {
        if (touched || scene.hyperarchy == null || rows.size == 0) return
        val at = scene.selected?.let { scene.at(it) }
        if (at != null) scene.camera = scene.camera.frame(at.x, at.y, at.r * 4, scene.viewport) else scene.fit()
    }

    fun poll() {
        page.submit {
            try {
                val r = fetchJs("/api/transfers")
                if (!truthy(r.ok)) error("HTTP " + str(r.status))
                val list = (reify(str(awaitJs(r.text()))) as? List<*>).orEmpty()
                rows = list.filterIsInstance<Map<*, *>>().map { TransferRow.of(it) }.toSeries()
                TransferGround.show(scene, rows)
                settle(); panel(); dirty = true
                status("live · " + TransferGround.count(rows.size, "transfer"))
            } catch (e: dynamic) { status("transfers unreachable: " + failureText(e)) }
            if (!page.closing) pollTimer = window.setTimeout({ poll() }, POLL_MS)
        }
    }

    fun input() {
        fun at(e: dynamic): DoubleArray { val r = host.getBoundingClientRect(); return doubleArrayOf(num(e.clientX) - r.left, num(e.clientY) - r.top) }
        val opts = obj(); opts.passive = false
        page.on(host, "wheel", { e: dynamic -> e.preventDefault(); val p = at(e); scene.wheel(num(e.deltaY), num(e.deltaMode).toInt(), p[0], p[1]); moved() }, opts)
        page.on(host, "contextmenu", { e: dynamic -> e.preventDefault() })
        page.on(host, "pointerdown", { e: dynamic ->
            val p = at(e); press = p; last = p; turning = num(e.button) == 2.0 || truthy(e.shiftKey)
            host.asDynamic().setPointerCapture(e.pointerId)
        })
        page.on(host, "pointermove", { e: dynamic ->
            val l = last ?: return@on
            val p = at(e)
            if (turning) scene.orbit(p[0] - l[0], p[1] - l[1]) else scene.drag(l[0], l[1], p[0], p[1])
            last = p; moved()
        })
        page.on(host, "pointerup", { e: dynamic ->
            val d = press; val p = at(e); press = null; last = null
            if (d != null && kotlin.math.hypot(p[0] - d[0], p[1] - d[1]) < 3) { picked(scene.click(p[0], p[1])); moved() }
        })
        page.on(document, "keydown", { e: dynamic ->
            val tag = str(e.target?.tagName)
            if (tag == "INPUT" || tag == "SELECT" || tag == "TEXTAREA") return@on
            if (scene.key(str(e.key))) { e.preventDefault(); moved() }
        })
    }

    /** The camera or the path moved: redraw, and keep the view in the URL. */
    fun moved() {
        touched = true; dirty = true
        window.history.replaceState(null, "", window.location.pathname + window.location.search + scene.hash())
    }

    /** A stand was clicked: the transfer it is (or whose source it is) goes in hand and in the URL. */
    fun picked(id: String?) {
        val row = id?.let { i -> rows.view.firstOrNull { it.id == i || i.startsWith(it.id + "/") } }
        scene.selected = row?.id
        val query = URLSearchParams(window.location.search)
        if (row != null) query.set("transfer", row.id) else query.delete("transfer")
        val search = query.toString()
        window.history.replaceState(null, "", window.location.pathname + (if (search.isEmpty()) "" else "?$search") + scene.hash())
        panel()
    }

    fun resize() {
        ratio = window.devicePixelRatio.let { if (it == 0.0) 1.0 else it }
        val w = maxOf(1, host.clientWidth); val h = maxOf(1, host.clientHeight)
        scene.viewport = Viewport(w, h)
        canvas.width = (w * ratio).toInt(); canvas.height = (h * ratio).toInt()
        dirty = true
    }

    fun draw() {
        if (!dirty) return
        dirty = false
        val c = canvas.getContext("2d") as CanvasRenderingContext2D
        c.setTransform(ratio, 0.0, 0.0, ratio, 0.0, 0.0)
        FrameCanvas.draw(c, scene.frame())
    }

    fun status(s: String) { document.getElementById("transfers-status")?.textContent = s }

    /** The transfer in hand, as aria2 lists a download: state, lengths, rates, connections, pieces, error, and what asked for it. */
    fun panel() {
        val el = document.getElementById("transfers-panel") ?: return
        fun e(s: String) = TransferLinkBar.escape(s)
        val r = scene.selected?.let { s -> rows.view.firstOrNull { it.id == s } }
        if (r == null) {
            el.innerHTML = TransferGround.figure(rows).view.joinToString("") { "<p>${e(it)}</p>" } +
                "<p class='dim'>click a column on the ground to hold a transfer</p>"
            return
        }
        val context = r.context?.let { c ->
            val href = TransferLinks.context(c)
            "<dt>asked by</dt><dd>${e(c.kind)} ${e(c.id)}" + (href?.let { " · <a href='${e(it)}'>${e(it)}</a>" } ?: "") + "</dd>"
        } ?: "<dt>asked by</dt><dd class='dim'>none recorded</dd>"
        val sources = r.sources.view.joinToString("") { s ->
            "<li>${e(s.address)} · ${e(s.protocol)}/${e(s.transport)} · ${e(s.state)} · ${e(TransferGround.rate(s.down, s.up))} · ${e(TransferGround.bytes(s.completed))}</li>"
        }
        el.innerHTML = "<h2>${e(r.name.ifEmpty { r.id })}</h2><dl>" +
            "<dt>id</dt><dd>${e(r.id)}</dd><dt>state</dt><dd>${e(r.state)}</dd><dt>protocol</dt><dd>${e(r.protocol)}</dd>" +
            "<dt>done</dt><dd>${e(TransferGround.done(r.completed, r.total))}</dd><dt>rate</dt><dd>${e(TransferGround.rate(r.down, r.up))}</dd>" +
            (r.pieces?.let { p -> "<dt>pieces</dt><dd>${p.have} of ${p.count} × ${e(TransferGround.bytes(p.length))}</dd>" } ?: "") +
            (r.error?.let { "<dt>error</dt><dd class='err'>${e(it)}</dd>" } ?: "") +
            "<dt>class</dt><dd>${e(r.`class` ?: "untyped")}</dd><dt>content</dt><dd>${e(r.content ?: "not yet known")}</dd>" +
            context + "</dl><h3>${e(TransferGround.count(r.sources.size, "connection"))}</h3><ol>$sources</ol>"
    }
}
