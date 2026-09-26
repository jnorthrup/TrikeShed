package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

/**
 * The hover blip: one node read across the planes it lands on, the daemon's own answer
 * (/api/lcnc/blip) — asserted, inferred, graal. Nothing here is authored by the page.
 */
class PanelsBlip(private val p: Panels) {
    private var timer = 0
    private var node: dynamic = null
    private var seq = 0
    private var x = 0.0
    private var y = 0.0
    private var frame = 0
    private var anchor: dynamic = null
    private var geometry: dynamic = null

    private fun el(): dynamic = document.getElementById("blip").asDynamic()

    fun move(e: dynamic) {
        if (node == null || e.currentTarget !== node.el) return
        if (truthy(e.buttons)) { leave(null); return }
        x = num(e.clientX); y = num(e.clientY)
        if (frame == 0 && geometry != null) frame = window.requestAnimationFrame { frame = 0; place() }
    }

    private fun show(html: String) {
        val b = el(); val r = p.viewport.getBoundingClientRect(); val margin = 12.0
        val bounds = obj()
        bounds.left = maxOf(0.0, r.left) + margin; bounds.top = maxOf(0.0, r.top) + margin
        bounds.right = minOf(window.innerWidth.toDouble(), r.right) - margin; bounds.bottom = minOf(window.innerHeight.toDouble(), r.bottom) - margin
        b.innerHTML = "<div class=\"blip-body\">$html</div>"
        b.setAttribute("role", "tooltip"); b.style.display = "block"; b.style.visibility = "hidden"
        b.style.maxWidth = "${maxOf(0.0, num(bounds.right) - num(bounds.left))}px"
        val beside = maxOf(x - num(bounds.left) - 28, num(bounds.right) - x - 28)
        val vertical = maxOf(y - num(bounds.top) - 28, num(bounds.bottom) - y - 28)
        val height = if (beside < minOf(360.0, num(bounds.right) - num(bounds.left))) vertical else 300.0
        b.style.maxHeight = "${maxOf(0.0, minOf(300.0, height, num(bounds.bottom) - num(bounds.top)))}px"
        // Measure once when content arrives, not on every pointer event.
        val g = obj(); g.bounds = bounds; g.w = num(b.offsetWidth); g.h = num(b.offsetHeight); g.subject = node?.el?.getBoundingClientRect()
        geometry = g
        anchor = null; place()
    }

    private fun place() {
        val b = el(); val g = geometry; if (g == null || node == null) return
        val a = anchor
        fun drift(v: Double) = maxOf(-48.0, minOf(48.0, v * .2))
        val preferred: dynamic = if (a != null) objOf { it.x = num(a.left) + drift(x - num(a.x)); it.y = num(a.top) + drift(y - num(a.y)) } else null
        val next = PatchNavigation.callout(x, y, num(g.w), num(g.h), g.bounds, g.subject, preferred)
        if (next == null) { b.style.visibility = "hidden"; return }
        if (a == null) anchor = objOf { it.x = x; it.y = y; it.left = next.x; it.top = next.y }
        b.style.transform = "translate3d(${next.x}px,${next.y}px,0)"
        b.dataset.side = next.side; b.style.setProperty("--tail", "${next.tail}px"); b.style.visibility = "visible"
    }

    fun leave(e: dynamic) {
        if (e != null && e.type == "pointerleave" && e.currentTarget !== node?.el) return
        window.clearTimeout(timer); window.cancelAnimationFrame(frame)
        timer = 0; frame = 0; node = null; anchor = null; geometry = null; seq++
        el().style.display = "none"
    }

    fun enter(n: dynamic, e: dynamic) {
        if (e.pointerType == "touch" || truthy(e.buttons)) return
        leave(null); x = num(e.clientX); y = num(e.clientY); node = n
        val mySeq = seq
        timer = window.setTimeout({
            timer = 0
            launchJs {
                val q = "program=" + encodeURIComponent(p.board.name ?: "") + "&node=" + encodeURIComponent(str(n.id)) + "&type=" + encodeURIComponent(str(n.type))
                val r: dynamic = try { api("GET", "/api/lcnc/blip?$q") } catch (x: CancellationException) { throw x } catch (x: dynamic) { objOf { it.error = "unreachable" } }
                if (mySeq == seq && node === n) render(n, r)
            }
        }, 320)
    }

    fun install() {
        window.addEventListener("resize", { leave(null) })
        window.addEventListener("blur", { leave(null) })
        on(window, "keydown", { e -> if (e.key == "Escape") leave(null) })
        on(p.viewport, "pointerdown", { leave(null) }, true)
        on(p.viewport, "wheel", { leave(null) }, objOf { it.passive = true })
    }

    private fun mb(v0: dynamic): String {
        val v = num(js("Number(v0)"))
        return if (v.isFinite()) (if (v >= 1048576) js("(v/1048576).toFixed(1)").unsafeCast<String>() + " MB" else js("(v/1024).toFixed(0)").unsafeCast<String>() + " KB") else "?"
    }

    private fun render(n: dynamic, r: dynamic) {
        fun row(cls: String, html: String) = "<div class=\"row $cls\">$html</div>"
        fun none(cls: String, txt: String) = row("$cls none", esc(txt))
        val boardName = p.board.name
        var h = "<div class=\"bt\">${esc(n.type)}<i>${esc(n.id)}${if (!boardName.isNullOrEmpty()) " · " + esc(boardName) else ""}</i></div>"
        if (truthy(r.error)) { show(h + row("i refused", esc(r.error))); return }
        // asserted
        val a = if (truthy(r.asserted)) r.asserted else obj(); val af = arr(a.facts)
        h += "<h6 class=\"asserted\">asserted · ${af.size} fact${if (af.size == 1) "" else "s"} · actor lcnc</h6>"
        if (!truthy(a.onPlane)) h += none("a", if (!boardName.isNullOrEmpty()) "not on the plane: no panels fact for this node" else "not on the plane yet — store the construction to publish it")
        for (f in af) {
            val x = if (truthy(f.fields)) f.fields else obj()
            h += if (x.kind == "node") row("a", "<b>node</b> ${esc(x.node)} : ${esc(x.type)}${if (truthy(x.parent)) " <s>in ${esc(x.parent)}</s>" else ""}")
            else if (x.kind == "cable") row("a", "<b>cable</b> ${esc(x.fromNode)}.${esc(x.fromPort)} → ${esc(x.toNode)}.${esc(x.toPort)} : ${esc(if (truthy(x.type)) x.type else "untyped")}")
            else row("a", esc(f.id))
        }
        // inferred
        val inf = if (truthy(r.inferred)) r.inferred else obj()
        val vio = arr(inf.violations); val voc = arr(inf.vocabulary); val prods = arr(inf.productions)
        val hit = prods.filter { truthy(it.matched) }
        h += "<h6 class=\"inferred\">inferred · ${vio.size} refusal${if (vio.size == 1) "" else "s"} · ${voc.size} vocabulary row${if (voc.size == 1) "" else "s"} · ${hit.size}/${prods.size} productions</h6>"
        fun orEmpty(v: dynamic) = if (truthy(v)) v else ""
        for (v in vio) {
            val x = if (truthy(v.fields)) v.fields else obj()
            h += row("i refused", "<b>refused</b> ${esc(orEmpty(x.rule))} ${esc(orEmpty(x.fromNode))}.${esc(orEmpty(x.fromPort))} → ${esc(orEmpty(x.toNode))}.${esc(orEmpty(x.toPort))} <s>${esc(orEmpty(x.detail))}</s>")
        }
        for (v in voc) {
            val bs = arr(v.bindings).joinToString(", ") { m -> arr(js("Object.values(m)")).joinToString(" ") { str(it) } }
            h += row("i", "<b>${esc(str(v.pattern).split(" ")[0].substring(1))}</b> ${esc(bs)}")
        }
        if (prods.isNotEmpty()) h += row("i", if (hit.isNotEmpty()) "<b>watched by</b> ${hit.joinToString(", ") { esc(it.ruleId) }}"
            else "<s>no production's interests hit this node (${prods.map { pr -> esc(arr(pr.interests).joinToString(" ") { str(it) }) }.distinct().joinToString(" · ")})</s>")
        if (vio.isEmpty() && voc.isEmpty() && prods.isEmpty()) h += none("i", "nothing inferred")
        // graal
        val gr = if (truthy(r.graal)) r.graal else obj(); val gk = arr(gr.keyed); val gj = arr(gr.jvm)
        h += "<h6 class=\"graal\">graal · ${gk.size} keyed to this node · ${gj.size} jvm-wide</h6>"
        if (gk.isEmpty()) h += none("g", "no graal fact names this node (no pointcut has landed on it)")
        for (f in gk) {
            val x = if (truthy(f.fields)) f.fields else obj()
            h += row("g", "<b>${esc(orEmpty(x.kind))}</b> ${esc(f.id)} <s>${esc(if (truthy(x.facet)) x.facet else if (truthy(x.method)) x.method else orEmpty(x["class"]))}</s>")
        }
        for (f in gj) {
            val x = if (truthy(f.fields)) f.fields else obj()
            if (x.kind == "memory") h += row("g", "<b>memory</b> heap ${mb(x.heapUsed)} / ${mb(x.heapMax)} · metaspace ${mb(x.metaspaceUsed)}")
            else if (x.kind == "gc") h += row("g", "<b>${esc(x.collector)}</b> ${esc(x.collections)} collections · ${esc(x.pauseMsTotal)} ms · last ${esc(orEmpty(x.lastCause))}")
            else if (x.kind == "jit") h += row("g", "<b>jit</b> ${esc(x.compilations)} compilations · ${esc(x.osr)} osr · ${mb(x.compiledBytes)} code")
        }
        if (gj.isEmpty()) h += none("g", "jvm tick not on the plane yet (graal facts land after the couch reconcile)")
        show(h)
    }
}
