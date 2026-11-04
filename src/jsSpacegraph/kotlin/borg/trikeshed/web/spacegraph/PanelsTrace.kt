package borg.trikeshed.web.spacegraph

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window

/** Bounded presentation of consumed-wire observations from the existing execution owner. */
class PanelsTrace(private val panels: Panels) {
    private class Spark(val index: Int, val wire: dynamic, var count: Int, var sequence: Int,
                        var elapsed: Double, var began: Double = -1.0, var dot: dynamic = null,
                        var label: dynamic = null)
    private val releases = mutableListOf<() -> Unit>()
    private val pending = mutableListOf<Spark>()
    private val visible = mutableListOf<Spark>()
    private var definition: LcncProgram? = null
    private var canvas: dynamic = null
    private var revision = -1
    private var frame = 0
    private var banner: dynamic = null
    private var caption: dynamic = null
    private var run = ""
    private var cid = ""
    private var sequence = 0
    private var displayed = 0
    private var omitted = 0
    private var unmatched = 0
    private var dropped = 0
    private var speed = 1.0
    private var mode = "Waiting for execution observations"
    private val motion = window.matchMedia("(prefers-reduced-motion: reduce)")

    init { panels.page.own { clear() } }

    fun clear() {
        panels.page.cancelFrame(frame); frame = 0
        while (releases.isNotEmpty()) releases.removeAt(releases.lastIndex).invoke()
        for (spark in visible) { spark.dot?.remove(); spark.label?.remove() }
        pending.clear(); visible.clear()
        for (n in panels.nodes()) n.el?.removeAttribute("data-observed-phase")
        banner?.remove(); banner = null; caption = null
        definition = null; canvas = null; run = ""; cid = ""; sequence = 0
        displayed = 0; omitted = 0; unmatched = 0; dropped = 0
    }

    fun start(program: LcncProgram) {
        clear(); definition = program; canvas = panels.graph; revision = panels.documentRevision
        banner = document.createElement("section").asDynamic()
        banner.setAttribute("aria-label", "Observed wire activity")
        banner.style.cssText = "position:fixed;right:16px;bottom:36px;z-index:55;max-width:380px;background:var(--panel,#101620);color:var(--ink,#dce6f3);border:1px solid #57d3ff;border-radius:9px;padding:12px;font:12px/1.5 var(--mono,monospace);box-shadow:0 8px 26px #0006"
        val title = document.createElement("strong").asDynamic(); title.textContent = "OBSERVED TRANSFERS"; banner.appendChild(title)
        caption = document.createElement("div").asDynamic(); caption.setAttribute("role", "status"); banner.appendChild(caption)
        val controls = document.createElement("div").asDynamic(); banner.appendChild(controls)
        val label = document.createElement("label").asDynamic(); label.textContent = "Display speed "; controls.appendChild(label)
        val rate = document.createElement("select").asDynamic(); rate.setAttribute("aria-label", "Activity display speed")
        for (value in s_["0.25", "1", "4"].view) {
            val option = document.createElement("option").asDynamic(); option.value = value; option.textContent = value + "×"; rate.appendChild(option)
        }
        rate.value = "1"; label.appendChild(rate); speed = 1.0
        val changeRate: (dynamic) -> Unit = { speed = num(rate.value).coerceIn(.25, 4.0) }
        panels.page.on(rate, "change", changeRate); releases.add { panels.page.off(rate, "change", changeRate) }
        val stop = document.createElement("button").asDynamic(); stop.textContent = "Hide activity"; controls.appendChild(stop)
        val hide: (dynamic) -> Unit = { clear() }
        panels.page.on(stop, "click", hide); releases.add { panels.page.off(stop, "click", hide) }
        val style = document.createElement("style").asDynamic()
        style.textContent = ".node[data-observed-phase=executing]{outline:2px solid #ffb02e;box-shadow:0 0 18px #ffb02e40}.node[data-observed-phase=before]{outline:2px solid #57d3ff}"
        banner.appendChild(style); document.body!!.appendChild(banner)
        mode = "Waiting for execution observations"; report()
    }

    private fun current(): Boolean {
        val frozen = definition ?: return false
        if (panels.page.closing || panels.graph !== canvas) return false
        if (revision != panels.documentRevision) {
            val same = try { LcncProgramConfix.sameDefinition(frozen,
                LcncProgramConfix.fromJson(str(panels.panelName().value).ifBlank { "canvas" }, JSON.stringify(panels.toConfix()))) }
                catch (_: dynamic) { false }
            if (!same) return false
            revision = panels.documentRevision
        }
        return true
    }

    fun unavailable(reason: String) {
        mode = reason
        for (n in panels.nodes()) n.el?.removeAttribute("data-observed-phase")
        report()
    }

    private fun report() {
        if (caption == null) return
        caption.textContent = mode + (if (run.isEmpty()) "" else " · " + run) +
            "\n" + displayed + " represented · " + omitted + " display omissions · " + unmatched + " unmapped · " + dropped + " owner omissions" +
            "\nMotion lasts 1.2 s at 1×; engine time is unchanged." +
            if (motion.matches) " Reduced motion: static markers." else ""
    }

    fun observe(runId: String, programCid: String, snapshot: dynamic, replay: Boolean = false) {
        if (!current()) { clear(); return }
        if (snapshot.observationSchema != "lcnc-observations/1") { unavailable("Wire observations unavailable for this receipt version"); return }
        if (!Regex("sha256:[0-9a-f]{64}").matches(programCid) || runId.isBlank()) { unavailable("Execution identity unavailable"); return }
        if (run.isNotEmpty() && (run != runId || cid != programCid)) { unavailable("Execution identity changed; activity stopped"); return }
        run = runId; cid = programCid
        mode = if (replay) "Recorded playback · no execution" else "Observed execution · delayed display"
        dropped = maxOf(dropped, num(snapshot.observationsDropped ?: 0).toInt())
        for (event in arr(snapshot.observations)) {
            val ordinal = num(event.sequence)
            if (!ordinal.isFinite() || ordinal <= sequence || ordinal > Int.MAX_VALUE || ordinal != ordinal.toInt().toDouble()) continue
            sequence = ordinal.toInt()
            if (event.phase != "consumed" || event.wire == null) continue
            if (event.documentCid != cid) { unmatched++; continue }
            val wire = event.wire
            val at = num(wire.index)
            if (!at.isFinite() || at < 0 || at > Int.MAX_VALUE || at != at.toInt().toDouble()) { unmatched++; continue }
            val index = at.toInt(); val actual = arr(panels.graph.wires).getOrNull(index)
            if (actual == null || actual.from[0] != wire.fromNode || actual.from[1] != wire.fromPort ||
                actual.to[0] != wire.toNode || actual.to[1] != wire.toPort) { unmatched++; continue }
            val elapsed = num(event.elapsedNanos)
            val existing = pending.firstOrNull { it.index == index }
            if (existing != null) { existing.count++; existing.sequence = sequence; existing.elapsed = elapsed }
            else if (pending.size < 256) pending.add(Spark(index, wire, 1, sequence, elapsed))
            else omitted++
        }
        for (n in panels.nodes()) n.el?.removeAttribute("data-observed-phase")
        if (!replay && str(snapshot.state) in _s["running", "paused", "pause-requested", "cancel-requested"]) {
            val site = snapshot.activeSite ?: snapshot.site
            if (site != null) {
                val node = panels.node(site.nodeId)
                if (node != null && site.documentCid == cid) node.el.setAttribute("data-observed-phase", str(site.phase))
            }
        }
        report()
        if (frame == 0 && pending.isNotEmpty()) frame = panels.page.frame(::draw)
    }

    private fun draw(now: Double) {
        frame = 0
        if (!current()) { clear(); return }
        while (pending.isNotEmpty() && visible.size < 128) {
            val spark = pending.removeAt(0); spark.began = now; visible.add(spark); displayed += spark.count
        }
        val iterator = visible.iterator()
        while (iterator.hasNext()) {
            val spark = iterator.next()
            val fraction = ((now - spark.began) / (1200.0 / speed)).coerceIn(0.0, 1.0)
            if (fraction >= 1) { spark.dot?.remove(); spark.label?.remove(); iterator.remove(); continue }
            val path = panels.wiresSvg.querySelector("[data-wire-index='${spark.index}']").asDynamic()
            if (path == null) continue
            val length = num(path.getTotalLength()); if (!length.isFinite() || length <= 0) continue
            val point = path.getPointAtLength(length * if (motion.matches) .5 else fraction)
            if (spark.dot == null || !truthy(spark.dot.isConnected)) {
                spark.dot = panels.svg("circle"); spark.dot.setAttribute("r", "5")
                spark.dot.setAttribute("data-observed-wire", str(spark.index))
                spark.dot.setAttribute("data-observed-count", str(spark.count))
                spark.dot.style.cssText = "fill:#80f4ff;filter:drop-shadow(0 0 5px #57d3ff);pointer-events:none"
                val title = panels.svg("title")
                title.textContent = str(spark.wire.fromNode) + "." + str(spark.wire.fromPort) + " → " + str(spark.wire.toNode) + "." + str(spark.wire.toPort) +
                    " · " + spark.count + " observed selections · through sequence " + spark.sequence +
                    if (spark.elapsed.isFinite()) " · last observed at " + fixed(spark.elapsed / 1_000_000, 3) + " ms from walk start" else ""
                spark.dot.appendChild(title); panels.wiresSvg.appendChild(spark.dot)
                spark.label = panels.svg("text"); spark.label.style.cssText = "fill:#bffaff;font-size:12px;pointer-events:none"
                spark.label.textContent = if (spark.count > 1) "×" + spark.count else ""; panels.wiresSvg.appendChild(spark.label)
            }
            spark.dot.setAttribute("cx", point.x); spark.dot.setAttribute("cy", point.y)
            spark.label.setAttribute("x", num(point.x) + 8); spark.label.setAttribute("y", num(point.y) - 8)
        }
        report()
        if (pending.isNotEmpty() || visible.isNotEmpty()) frame = panels.page.frame(::draw)
    }
}
