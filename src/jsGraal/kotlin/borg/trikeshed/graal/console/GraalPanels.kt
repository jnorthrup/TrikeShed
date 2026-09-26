package borg.trikeshed.graal.console

import borg.trikeshed.web.graal.encUri
import borg.trikeshed.web.graal.entries
import borg.trikeshed.web.graal.fetchJson
import borg.trikeshed.web.graal.fetchResponse
import borg.trikeshed.web.graal.isArray
import borg.trikeshed.web.graal.isFiniteNum
import borg.trikeshed.web.graal.jsonIndent
import borg.trikeshed.web.graal.jsonPost
import borg.trikeshed.web.graal.jsonStringify
import borg.trikeshed.web.graal.methodInit
import borg.trikeshed.web.graal.newObject
import borg.trikeshed.web.graal.nowHms
import borg.trikeshed.web.graal.num
import borg.trikeshed.web.graal.objectKeys
import borg.trikeshed.web.graal.orr
import borg.trikeshed.web.graal.perfNow
import borg.trikeshed.web.graal.s0
import borg.trikeshed.web.graal.str
import borg.trikeshed.web.graal.timeHms
import borg.trikeshed.web.graal.toFixed
import borg.trikeshed.web.graal.truthy
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.EventSource
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.asList
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/* ── HUD vitals ── */
var lastComp = 0.0
val compHist = ArrayList<Double>()
lateinit var sparkCtx: CanvasRenderingContext2D

private fun GraalConsole.bar(id: String): HTMLElement = q(id).querySelector(".bar i") as HTMLElement

fun GraalConsole.pollVitals() {
    scope.launch {
        try {
            val v: dynamic = fetchJson("/api/graal/vitals")
            val g: dynamic = orr(v.graal, newObject())
            q("identSub").textContent = str(orr(g.vmName, "?")) + " " + s0(orr(g.vmVersion, "")) + " · pid " + str(orr(g.pid, "?")) + " · up " +
                floor(num(orr(g.uptimeMs, 0)) / 60000).toLong() + "m" + (if (truthy(v.jfr) && truthy(v.jfr.live)) " · jfr live" else " · jfr OFF")
            val jit: dynamic = orr(v.jit, newObject()); val de: dynamic = orr(v.deopt, newObject()); val gc: dynamic = orr(v.gc, newObject())
            val mem: dynamic = orr(v.memory, newObject()); val cls: dynamic = orr(v.classes, newObject()); val thr: dynamic = orr(v.threads, newObject())
            val cpu: dynamic = orr(v.cpu, newObject()); val pts: dynamic = orr(v.pointcuts, newObject())
            first("cComp").textContent = loc(num(orr(jit.compilations, 0)))
            first("cDeopt").textContent = loc(num(orr(de.count, 0)))
            first("cGc").textContent = str(orr(gc.collections, 0)) + " · " + str(orr(gc.pauseMsTotal, 0))
            val hu = num(orr(mem.heapUsed, 0)); val hm = num(orr(mem.heapMax, 1))
            first("cHeap").textContent = fmtBytes(hu) + "/" + fmtBytes(hm)
            bar("cHeap").style.width = str(min(100.0, 100 * hu / hm)) + "%"
            first("cCpu").textContent = str(borg.trikeshed.web.graal.jsRound(num(orr(cpu.jvm, 0)) * 100)) + "% / " + str(borg.trikeshed.web.graal.jsRound(num(orr(cpu.machine, 0)) * 100)) + "%"
            bar("cCpu").style.width = str(min(100.0, num(orr(cpu.jvm, 0)) * 100)) + "%"
            first("cCls").textContent = loc(num(orr(cls.loaded, 0)))
            first("cThr").textContent = str(orr(thr.live, 0)) + " (" + str(orr(thr.daemon, 0)) + "d)"
            first("cPoint").textContent = loc(num(orr(pts.routes, 0)))
            val comps = num(orr(jit.compilations, 0))
            val rate = max(0.0, comps - lastComp); lastComp = comps
            compHist.add(rate); if (compHist.size > 43) compHist.removeAt(0)
            sparkCtx.clearRect(0.0, 0.0, 86.0, 14.0); sparkCtx.strokeStyle = "#3ddc84"; sparkCtx.beginPath()
            val mx = max(1.0, compHist.maxOrNull() ?: 1.0)
            compHist.forEachIndexed { i, r -> val x = i * 2.0; val y = 13 - 11 * r / mx; if (i != 0) sparkCtx.lineTo(x, y) else sparkCtx.moveTo(x, y) }
            sparkCtx.stroke()
            if (q("gc").style.display == "flex") renderGcLane(gc)
        } catch (_: Throwable) {}
        window.setTimeout({ pollVitals() }, 2000)
    }
}

/* ── R4 client: the GC lane — real occupancy deltas ── */
var lastGcLane: dynamic = null

fun GraalConsole.toggleGc() {
    if (q("gc").style.display == "flex") { hide("gc"); return }
    show("gc"); renderGcLane(lastGcLane)
}

private fun maxOf1(xs: List<Double>): Double = max(1.0, xs.maxOrNull() ?: 1.0)
private fun f2(v: Double): String = toFixed(v, 2)
private fun f1(v: Double): String = toFixed(v, 1)

fun GraalConsole.renderGcLane(gc: dynamic) {
    if (!truthy(gc)) { q("gcBody").innerHTML = "<div style=\"color:var(--dim)\">waiting for a GC…</div>"; return }
    lastGcLane = orr(gc.lane, newObject())
    val lane: dynamic = lastGcLane
    val occ: dynamic = orr(lane.heapOccupancy, newObject())
    val pools = orr(lane.pools, js("[]")).unsafeCast<Array<dynamic>>(); val phases = orr(lane.phases, js("[]")).unsafeCast<Array<dynamic>>()
    val alloc = orr(lane.allocation, js("[]")).unsafeCast<Array<dynamic>>()
    val recent = orr(occ.recentFreed, js("[]")).unsafeCast<Array<dynamic>>().map { num(it) }
    val mx = maxOf1(recent.map { abs(it) })
    var h = ""
    h += "<div style=\"color:var(--dim);margin-bottom:4px\">whole heap · committed " + fmtBytes(num(orr(occ.committedBytes, 0))) +
        " · matched collections " + str(orr(occ.collectionsMatched, 0)) + " · avg freed/collection " + fmtBytes(num(orr(occ.avgFreedBytes, 0))) + "</div>"
    if (recent.isNotEmpty()) {
        h += "<div style=\"color:var(--dim);margin:2px 0\">freed per collection (last " + recent.size + ")</div>" +
            "<svg width=\"100%\" height=\"34\" style=\"display:block;background:var(--panel2);border-radius:3px\">" +
            recent.mapIndexed { i, f ->
                val w = 100.0 / recent.size
                val x = i * w; val top = 32 - abs(f) / mx * 30; val hh = max(1.0, abs(f) / mx * 30)
                "<rect x=\"" + f2(x) + "\" y=\"" + f2(top) + "\" width=\"" + f2(w * 0.8) + "\" height=\"" + f2(hh) + "\" fill=\"" + (if (f >= 0) "var(--gc)" else "var(--deopt)") + "\" rx=\"1\"/>"
            }.joinToString("") + "</svg>"
    }
    h += "<div style=\"margin:8px 0 2px;color:var(--gc);font-size:10px;letter-spacing:.08em\">POOLS · used / reclaimed / grown</div>"
    if (pools.isEmpty()) h += "<div style=\"color:var(--dim)\">no collection boundary yet</div>"
    val pmx = maxOf1(pools.map { num(orr(it.lastUsedBytes, 0)) })
    for (p in pools) {
        val usedFrac = min(100.0, num(orr(p.lastUsedBytes, 0)) / pmx * 100)
        val recl = min(100.0, num(orr(p.reclaimedBytes, 0)) / (pmx * 2) * 100)
        h += "<div class=\"lanepool\"><span class=\"nm\">" + esc(p.pool) + "</span>" +
            "<span class=\"track\"><i class=\"recl\" style=\"left:0;width:" + f1(recl) + "%;opacity:.5\"></i>" +
            "<i class=\"used\" style=\"left:" + f1(recl) + "%;width:" + f1(usedFrac) + "%\"></i></span>" +
            "<span class=\"num\">" + fmtBytes(num(orr(p.lastUsedBytes, 0))) + " used · <span style=\"color:var(--compile)\">−" + fmtBytes(num(orr(p.reclaimedBytes, 0))) +
            "</span> · <span style=\"color:var(--deopt)\">+" + fmtBytes(num(orr(p.grownBytes, 0))) + "</span></span></div>"
    }
    h += "<div style=\"margin:10px 0 2px;color:var(--gc);font-size:10px;letter-spacing:.08em\">PAUSE BY PHASE</div>"
    if (phases.isEmpty()) h += "<div style=\"color:var(--dim)\">no phase yet</div>"
    val phmx = maxOf1(phases.map { num(orr(it.pauseMsTotal, 0)) })
    for (p in phases.take(14)) {
        val f = min(100.0, num(orr(p.pauseMsTotal, 0)) / phmx * 100)
        h += "<div class=\"gcphase\"><span>" + esc(p.phase) + " <span style=\"opacity:.6\">×" + str(orr(p.count, 0)) + "</span></span>" +
            "<span style=\"flex:1;margin:0 8px;background:var(--line);height:8px;border-radius:2px\"><span style=\"display:block;height:100%;width:" + f1(f) + "%;background:var(--gc);border-radius:2px\"></span></span>" +
            "<b>" + str(orr(p.pauseMsTotal, 0)) + " ms</b></div>"
    }
    if (alloc.isNotEmpty()) {
        h += "<div style=\"margin:10px 0 2px;color:var(--gc);font-size:10px;letter-spacing:.08em\">ALLOCATION ATTRIBUTION (JFR sample) · top " + alloc.size + "</div>"
        val amx = maxOf1(alloc.map { num(orr(it.bytes, 0)) })
        for (a in alloc.take(12)) {
            val f = min(100.0, num(orr(a.bytes, 0)) / amx * 100)
            val c = str(a["class"])
            h += "<div class=\"gcphase\"><span title=\"" + esc(c) + "\">" + esc(if (c.length > 40) c.take(40) + "…" else c) + "</span>" +
                "<span style=\"flex:1;margin:0 8px;background:var(--line);height:8px;border-radius:2px\"><span style=\"display:block;height:100%;width:" + f1(f) + "%;background:var(--cpu);border-radius:2px\"></span></span>" +
                "<b>" + fmtBytes(num(orr(a.bytes, 0))) + "</b></div>"
        }
    }
    q("gcBody").innerHTML = h
}

/* ── the blackboard panel: durable facts — pointcut sites / writes, ACE chunk cache receipts, the raw snapshot ── */
fun GraalConsole.toggleBoard() {
    if (q("board").style.display == "flex") hide("board") else show("board")
    if (q("boardBody").innerHTML == "") boardInit()
    boardRefresh()
}

private const val INPUT = "background:var(--panel2);border:1px solid var(--line);border-radius:4px;color:var(--ink);padding:3px 8px"
private const val SELECT = "background:var(--panel2);border:1px solid var(--line);border-radius:4px;color:var(--ink);padding:3px 6px"

fun GraalConsole.boardInit() {
    q("boardBody").innerHTML =
        "<div style=\"color:var(--dim);font-size:10px;margin-bottom:8px\">CAS / heap terrain = the canvas (X toggles the heap continent) · scoring tape · VM + commit receipts stream in the river (score / vm / commit kinds) · GC lane = G panel — this panel is the durable fact store</div>" +
            "<div style=\"margin-bottom:6px\"><b style=\"color:var(--dim);font-size:10px\">pointcut sites / writes — through /blackboard/assert, the one funnel</b><br>" +
            "<input id=\"pcO\" placeholder=\"owner\" style=\"width:22%;$INPUT\">" +
            "<input id=\"pcM\" placeholder=\"method\" style=\"width:22%;$INPUT\">" +
            "<input id=\"pcS\" placeholder=\"site\" style=\"width:14%;$INPUT\">" +
            "<select id=\"pcE\" style=\"$SELECT\"><option>true</option><option>false</option></select>" +
            " <span class=\"x\" style=\"cursor:pointer\" onclick=\"pcAssert()\">→ assert</span></div>" +
            "<div id=\"pcSites\" style=\"color:var(--dim);font-size:10px;margin-bottom:8px\">no pointcut sites on the board yet</div>" +
            "<hr style=\"border:0;border-top:1px solid var(--line);margin:10px 0\">" +
            "<b style=\"color:var(--dim);font-size:10px\">ACE chunk cache receipts (cache-receipt/ · context-receipt/)</b>" +
            "<div id=\"aceRcpts\" style=\"color:var(--dim);font-size:10px;margin:4px 0 8px\">no cache receipts yet — they land when a context chain assembles</div>" +
            "<hr style=\"border:0;border-top:1px solid var(--line);margin:10px 0\">" +
            "<b style=\"color:var(--dim);font-size:10px\">blackboard snapshot</b> <span class=\"x\" style=\"cursor:pointer\" onclick=\"boardRefresh()\">↻</span>" +
            "<pre id=\"boardSnap\" style=\"white-space:pre-wrap;word-break:break-word;color:var(--dim);font-size:10px;margin:4px 0\"></pre>"
}

fun esc9(s: dynamic): String = Projection.escAll(s0(s))

fun GraalConsole.boardRefresh() {
    scope.launch {
        try {
            val b: dynamic = fetchJson("/blackboard/board")
            val board: dynamic = orr(b.board, newObject()); val keys = objectKeys(board)
            (q("cBoard").querySelector("b") as HTMLElement).textContent = keys.size.toString()
            if (q("boardBody").innerHTML == "") return@launch
            val sites = keys.filter { it.startsWith("pointcut/") || it.startsWith("pointcut-def/") }
            q("pcSites").innerHTML = if (sites.isNotEmpty()) sites.take(60).joinToString("<br>") { esc9(it) + " = " + esc9(jsonStringify(board[it])) } else "no pointcut sites on the board yet"
            val ace = keys.filter { it.startsWith("cache-receipt/") || it.startsWith("context-receipt/") }
            q("aceRcpts").innerHTML = if (ace.isNotEmpty()) ace.takeLast(40).reversed().joinToString("<br>") { esc9(it) + " = " + esc9(jsonStringify(board[it])) }
            else "no cache receipts yet — they land when a context chain assembles"
            q("boardSnap").textContent = jsonIndent(board, 1).take(16000)
        } catch (_: Throwable) {}
    }
}

fun GraalConsole.pcAssert() {
    val o = inputValue("pcO").trim(); val m = inputValue("pcM").trim(); val s = inputValue("pcS").trim(); val en = (q("pcE") as HTMLSelectElement).value
    if (o.isEmpty() || m.isEmpty() || s.isEmpty()) return
    val key = "pointcut-def/$o/$m/$s"
    val body: dynamic = newObject(); val v: dynamic = newObject(); v.method = m; v.site = s; v.enabled = en; body[key] = v
    scope.launch {
        window.fetch("/blackboard/assert", jsonPost(JSON.stringify(body))).await()
        window.setTimeout({ boardRefresh() }, 150)
    }
}

/* ── R1 client: the zoom / strength pane — the bytes-prove-the-grouping pane ── */
fun GraalConsole.toggleZoom() {
    if (q("zoom").style.display == "flex") hide("zoom") else show("zoom")
    if (q("zoomBody").innerHTML == "") zoomInit()
}

fun GraalConsole.zoomInit() {
    q("zoomBody").innerHTML =
        "<div style=\"margin-bottom:6px\"><b style=\"color:var(--dim);font-size:10px\">ZOOM · code ring8 (hex or dec)</b><br>" +
            "<input id=\"zoomCode\" placeholder=\"e.g. 3f\" style=\"width:90px;$INPUT\">" +
            " <span class=\"x\" style=\"cursor:pointer\" onclick=\"zoomGo()\">→ ring</span></div>" +
            "<div id=\"zoomOut\" style=\"color:var(--dim)\">enter a ring8 to see its representative fragments</div>" +
            "<hr style=\"border:0;border-top:1px solid var(--line);margin:10px 0\">" +
            "<div style=\"margin-bottom:6px\"><b style=\"color:var(--dim);font-size:10px\">STRENGTH · two cids side by side</b><br>" +
            "<input id=\"strA\" placeholder=\"a cid\" style=\"width:46%;$INPUT\">" +
            "<input id=\"strB\" placeholder=\"b cid\" style=\"width:46%;$INPUT\">" +
            " <span class=\"x\" style=\"cursor:pointer\" onclick=\"strengthGo()\">→ grade</span></div>" +
            "<div id=\"strOut\" style=\"color:var(--dim)\">enter two cids to grade the match</div>" +
            "<hr style=\"border:0;border-top:1px solid var(--line);margin:10px 0\">" +
            "<div style=\"margin-bottom:6px\"><b style=\"color:var(--dim);font-size:10px\">DENSITY · residual per aperture band (LineAperture → zoom bands)</b><br>" +
            "<input id=\"dPath\" placeholder=\"a doc id\" style=\"width:46%;$INPUT\">" +
            "<select id=\"dAperture\" style=\"$SELECT\">" +
            "<option value=\"L0\">L0 macro</option><option value=\"L1\">L1 region</option>" +
            "<option value=\"L2\" selected>L2 focus</option><option value=\"L3\">L3 micro</option></select>" +
            " <span class=\"x\" style=\"cursor:pointer\" onclick=\"densityGo()\">→ density</span></div>" +
            "<div id=\"dOut\" style=\"color:var(--dim)\">enter a doc id to shade its residual density</div>"
}

/* R1: per-ring density shading — residualDensity(probe, aperture) behind a route. */
fun GraalConsole.densityGo() {
    val p = inputValue("dPath").trim()
    if (p.isEmpty()) { q("dOut").textContent = "enter a doc id"; return }
    q("dOut").textContent = "…"
    scope.launch {
        try {
            val r: dynamic = fetchJson("/api/graal/density?path=" + encUri(p) + "&aperture=" + (q("dAperture") as HTMLSelectElement).value)
            if (truthy(r.error)) { q("dOut").textContent = "error: " + str(r.error); return@launch }
            val tot: dynamic = orr(r.totals, newObject())
            val regions = orr(r.regions, js("[]")).unsafeCast<Array<dynamic>>()
            var h = "<div style=\"margin-bottom:4px\"><b>" + regions.size + "</b> regions · " +
                "linked <b style=\"color:var(--commit)\">" + str(tot.linked) + "</b> · " +
                "partial <b style=\"color:var(--warn)\">" + str(tot.partial) + "</b> · " +
                "content-only <b style=\"color:var(--dim)\">" + str(tot.contentOnly) + "</b></div>"
            val sum: Double = js("(tot.linked+tot.partial+tot.contentOnly)||1")
            for (rg in regions.take(32)) {
                h += "<div style=\"display:flex;align-items:center;gap:6px;margin:2px 0\">" +
                    "<span style=\"color:var(--dim);font-size:10px;min-width:44px\">r" + str(rg.region) + "</span>" +
                    "<div style=\"flex:1;height:8px;background:var(--panel2);border-radius:3px;position:relative;overflow:hidden\">" +
                    "<div style=\"position:absolute;inset:0;width:" + str(min(100.0, (num(orr(rg.linked, 0)) * 100) / max(1.0, sum))) + "%;background:var(--commit)\"></div>" +
                    "</div>" +
                    "<span style=\"color:var(--dim);font-size:10px\">" + str(rg.linked) + "/" + str(rg.partial) + "/" + str(rg.contentOnly) + "</span></div>"
            }
            q("dOut").innerHTML = h
        } catch (e: Throwable) { q("dOut").textContent = "density fetch failed: " + errText(e) }
    }
}

fun GraalConsole.zoomGo() {
    val c = inputValue("zoomCode").trim()
    if (c.isEmpty()) { q("zoomOut").textContent = "enter a ring8"; return }
    q("zoomOut").textContent = "…"
    scope.launch {
        try {
            val r: dynamic = fetchJson("/api/graal/zoom?code=" + encUri(c))
            if (truthy(r.error)) { q("zoomOut").textContent = "error: " + str(r.error); return@launch }
            val reps = orr(r.representatives, js("[]")).unsafeCast<Array<dynamic>>()
            var h = "<div style=\"color:var(--dim);margin-bottom:4px\">ring8 " + str(r.ring8) + " · " + str(r.docs) + " docs · top " + reps.size + " reps</div>"
            for (x in reps.take(24)) {
                h += "<div style=\"padding:2px 0;border-bottom:1px solid var(--line)\"><span class=\"hashchip\" title=\"" + esc(x.id) + "\" onclick=\"navigator.clipboard&&navigator.clipboard.writeText(this.title)\">" + esc(str(x.id).take(44)) + "…</span> " +
                    (if (truthy(x.structuralKey)) "<span style=\"color:var(--dim)\">" + esc(str(x.structuralKey).take(28)) + "</span>" else "") +
                    "<a style=\"color:var(--commit);margin-left:6px\" href=\"" + str(x.cas) + "\" target=\"_blank\" rel=\"noopener\">bytes</a></div>"
            }
            q("zoomOut").innerHTML = h
        } catch (e: Throwable) { q("zoomOut").textContent = "zoom fetch failed: " + errText(e) }
    }
}

fun GraalConsole.strengthGo() {
    val a = inputValue("strA").trim(); val b = inputValue("strB").trim()
    if (a.isEmpty() || b.isEmpty()) { q("strOut").textContent = "enter two cids"; return }
    q("strOut").textContent = "…"
    scope.launch {
        try {
            val r: dynamic = fetchJson("/api/graal/strength?a=" + encUri(a) + "&b=" + encUri(b))
            if (truthy(r.error)) { q("strOut").textContent = "error: " + str(r.error); return@launch }
            val color = when (r.confidence) { "CONFIRMED" -> "var(--compile)"; "PROVISIONAL" -> "var(--gc)"; "CANDIDATE" -> "var(--cpu)"; else -> "var(--deopt)" }
            q("strOut").innerHTML = "<div style=\"font-size:15px;color:" + color + "\">" + esc(r.confidence) + " <span style=\"color:var(--dim)\">ramp " + str(orr(r.ramp, 0)) + "</span></div>" +
                "<table class=\"kv\">" +
                "<tr><td>linked / partial / contentOnly</td><td>" + str(orr(r.linked, 0)) + " / " + str(orr(r.partial, 0)) + " / " + str(orr(r.contentOnly, 0)) + "</td></tr>" +
                "<tr><td>proximity</td><td>" + esc(orr(r.proximity, "")) + "</td></tr>" +
                "<tr><td>a bytes</td><td>" + fmtBytes(num(orr(r.aBytes, 0))) + "</td></tr>" +
                "<tr><td>b bytes</td><td>" + fmtBytes(num(orr(r.bBytes, 0))) + "</td></tr></table>"
        } catch (e: Throwable) { q("strOut").textContent = "strength fetch failed: " + errText(e) }
    }
}

/* ── VMs ── */
var vmSheet: dynamic = js("({columns:[],rows:[]})")

fun GraalConsole.pollVms() {
    scope.launch {
        try {
            val r = fetchResponse("/api/vm")
            if (r.ok) {
                vmSheet = r.json().await(); vmSheet.rows = orr(vmSheet.rows, js("[]"))
                first("cVm").textContent = str(vmSheet.rows.length)
                if (q("vms").style.display == "flex") renderVms()
            } else first("cVm").textContent = "–"
        } catch (_: Throwable) { first("cVm").textContent = "–" }
        window.setTimeout({ pollVms() }, 5000)
    }
}

fun vmOut(x: dynamic) { (document.getElementById("vmOut") as HTMLElement).textContent = if (borg.trikeshed.web.graal.typeOf(x) == "string") str(x) else jsonIndent(x, 1) }

/* ── VM run visibility: a live log fed by the SSE 'vm' events, plus busy state on the calling button ── */
val vmLog = ArrayList<dynamic>()

private fun vmLogHtml(): String = vmLog.joinToString("") { x ->
    "<div class=\"vmline " + str(x.vmKind) + "\"><span class=\"t\">" + timeHms(orr(x.at, js("Date.now()"))) + "</span><span class=\"k\">" + str(x.vmKind) + "</span><span>" + esc(orr(x.vmId, "")) +
        "</span><span style=\"color:var(--dim)\">" + esc(vmDetail(x)) + "</span></div>"
}

fun vmLine(m: dynamic) {
    vmLog.add(0, m); if (vmLog.size > 200) vmLog.removeAt(vmLog.size - 1)
    val el = document.getElementById("vmLog") as HTMLElement? ?: return
    el.innerHTML = vmLogHtml()
}

fun vmDetail(x: dynamic): String = when (x.vmKind) {
    "spawned" -> str(x.facet) + " · " + str(x.trust)
    "evaluated" -> (if (truthy(x.nanos)) str(borg.trikeshed.web.graal.jsRound(num(x.nanos) / 1e6)) + "ms · " else "") + "cid " + s0(orr(x.resultCid, "")).take(14) + "…"
    "revoked" -> s0(orr(x.reason, ""))
    "landed" -> str(x.root) + "." + str(x.property) + " = " + s0(orr(x.value, "")).take(60)
    else -> ""
}

var vmBusy: String? = null
fun vmBusyStart(label: String) { vmBusy = label; (document.getElementById("vmSpinner") as HTMLElement?)?.style?.display = "inline-block"; vmOut("$label…") }
fun vmBusyEnd() { vmBusy = null; (document.getElementById("vmSpinner") as HTMLElement?)?.style?.display = "none" }

suspend fun GraalConsole.vmPost(path: String, body: dynamic): Boolean = try {
    val r = window.fetch(path, jsonPost(JSON.stringify(body ?: newObject()))).await()
    val t = r.text().await()
    vmOut(r.status.toString() + " " + t.take(600)); pollVms(); r.ok
} catch (e: Throwable) { vmOut("failed: " + errText(e)); false }

fun GraalConsole.vmSpawn() {
    val id = inputValue("vmId").ifEmpty { "vm-" + (js("Date.now()%10000") as Number).toString() }
    vmBusyStart("spawning $id")
    val stmts = inputValue("vmStmts")
    val body: dynamic = newObject(); body.id = id; body.facet = (q("vmFacet") as HTMLSelectElement).value
    body.statements = num(if (stmts.isEmpty()) 10000 else stmts); body.wallMillis = 15000
    scope.launch { vmPost("/api/vm/spawn", body); vmBusyEnd() }
}

fun GraalConsole.vmEval(id: String) {
    vmBusyStart("evaluating on $id")
    val body: dynamic = newObject(); body.source = (q("vmSrc") as HTMLTextAreaElement).value.ifEmpty { "1+1" }; body.name = "console"
    scope.launch { vmPost("/api/vm/" + encUri(id) + "/eval", body); vmBusyEnd() }
}

fun GraalConsole.vmRevoke(id: String) {
    vmBusyStart("revoking $id")
    val body: dynamic = newObject(); body.reason = "console"
    scope.launch { vmPost("/api/vm/" + encUri(id) + "/revoke", body); vmBusyEnd() }
}

fun GraalConsole.hermesRunner() { window.location.href = "/hermes" }

fun GraalConsole.renderVms() {
    val cols = orr(vmSheet.columns, js("[]")).unsafeCast<Array<dynamic>>().map { str(it.name) }
    val rows = orr(vmSheet.rows, js("[]")).unsafeCast<Array<Array<dynamic>>>()
    q("vmsBody").innerHTML =
        "<div class=\"vmctl\"><input id=\"vmId\" placeholder=\"id\" size=\"8\"><select id=\"vmFacet\"><option value=\"js\">js</option><option value=\"python\">python</option></select>" +
            "<input id=\"vmStmts\" value=\"10000\" size=\"7\" title=\"statement budget\"><button onclick=\"vmSpawn()\">spawn</button>" +
            "<button class=\"vmbtn\" onclick=\"hermesRunner()\" title=\"open the real supervised Hermes xterm-256color console\">▣ hermes xterm256</button></div>" +
            (if (rows.isNotEmpty()) "<table><tr>" + cols.joinToString("") { "<th>" + esc(it) + "</th>" } + "<th></th></tr>" +
                rows.joinToString("") { r ->
                    val id: dynamic = r[0]
                    "<tr>" + r.joinToString("") { v -> "<td>" + esc(s0(v).take(26)) + "</td>" } +
                        "<td><a class=\"vmbtn\" href=\"/vm-terminal?id=" + encUri(str(id)) + "\">terminal</a> <button class=\"vmbtn\" onclick=\"vmEval('" + esc(id) + "')\">eval</button> <button class=\"vmbtn\" onclick=\"vmRevoke('" + esc(id) + "')\">revoke</button></td></tr>"
                } + "</table>"
            else "<i style=\"color:var(--dim)\">no generic guests — Hermes has its own xterm256 panel</i>") +
            "<div class=\"vmctl\" style=\"margin-top:8px\"><textarea id=\"vmSrc\" placeholder=\"source to eval on a guest…\"></textarea><span id=\"vmSpinner\" class=\"vmspin\" style=\"display:none\" title=\"run in flight\"></span></div>" +
            "<div id=\"vmOut\"></div><div style=\"color:var(--dim);font-size:10px;margin:6px 0 2px\">run log</div><div id=\"vmLog\"></div>"
    q("vmLog").innerHTML = vmLogHtml()
}

fun GraalConsole.toggleVms() {
    if (q("vms").style.display == "flex") { hide("vms"); return }
    renderVms(); show("vms")
}

/* ── the hermes VT: a home-grown terminal over HermesCapsule (no PTY, no CDN — offline-safe) ── */
var vtId: String? = null
var vtPoll: Int? = null
var vtLastLen = -1

fun GraalConsole.vtOpen(id: String) {
    vtId = id; q("vtTitle").textContent = "hermes vt — $id"; q("vt").classList.remove("dead")
    q("vtScroll").textContent = ""; vtLastLen = -1; show("vt"); q("vtInput").focus()
    vtPoll?.let { window.clearInterval(it) }
    vtPoll = window.setInterval({ vtTick() }, 350); vtTick()
}

fun GraalConsole.vtTick() {
    val id = vtId ?: return
    scope.launch {
        try {
            val j: dynamic = fetchJson("/api/graal/capsule/" + encUri(id) + "/output")
            if (j.text != undefined && num(j.text.length).toInt() != vtLastLen) {
                vtLastLen = num(j.text.length).toInt()
                val el = q("vtScroll"); val atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 6
                el.textContent = str(j.text)
                if (atBottom) el.scrollTop = el.scrollHeight.toDouble()
            }
            if (j.alive == false) { q("vt").classList.add("dead"); vtPoll?.let { window.clearInterval(it) }; vtPoll = null }
        } catch (_: Throwable) {}
    }
}

fun GraalConsole.vtSend() {
    val inp = q("vtInput") as HTMLInputElement; val text = inp.value; inp.value = ""
    val id = vtId
    if (id == null || text.isEmpty()) return
    val body: dynamic = newObject(); body.text = text
    scope.launch {
        window.fetch("/api/graal/capsule/" + encUri(id) + "/stdin", jsonPost(JSON.stringify(body))).await()
        vtTick()
    }
}

fun GraalConsole.vtKill() {
    val id = vtId ?: return
    scope.launch {
        window.fetch("/api/graal/capsule/" + encUri(id) + "/kill", methodInit("POST")).await()
        vtTick()
    }
}

/* ── occupied repos: "projects to research/run" — arbitrary git worktrees the daemon absorbs and watches ── */
var projSheet: Array<dynamic> = emptyArray()

fun GraalConsole.pollProjects(again: Boolean = true) {
    scope.launch { pollProjectsNow(); if (again) window.setTimeout({ pollProjects() }, 6000) }
}

suspend fun GraalConsole.pollProjectsNow() {
    try {
        val r = fetchResponse("/api/graal/occupy")
        if (r.ok) {
            val j: dynamic = r.json().await(); projSheet = orr(j.repos, js("[]")).unsafeCast<Array<dynamic>>()
            if (q("vms").style.display == "flex") renderVms()
        }
    } catch (_: Throwable) {}
}

fun GraalConsole.occupyRepo() {
    val el = q("occPath") as HTMLInputElement; val path = el.value.trim()
    if (path.isEmpty()) return
    q("occOut").textContent = "occupying $path…"
    scope.launch {
        try {
            val body: dynamic = newObject(); body.path = path
            val r = window.fetch("/api/graal/occupy", jsonPost(JSON.stringify(body))).await()
            val j: dynamic = r.json().await()
            if (r.ok) { river("commit", "occupy " + str(j.id)); el.value = ""; q("occOut").textContent = ""; pollProjectsNow() }
            else q("occOut").textContent = "failed: " + str(orr(j.error, r.status))
        } catch (e: Throwable) { q("occOut").textContent = "failed: " + errText(e) }
    }
}

fun GraalConsole.releaseRepo(id: String) {
    q("occOut").textContent = "releasing $id…"
    scope.launch {
        try {
            val r = window.fetch("/api/graal/occupy/" + encUri(id) + "/release", methodInit("POST")).await()
            if (r.ok) { river("deopt", "release $id"); q("occOut").textContent = ""; pollProjectsNow() }
            else q("occOut").textContent = "release failed: " + r.status
        } catch (e: Throwable) { q("occOut").textContent = "failed: " + errText(e) }
    }
}

fun projectsSection(): String =
    "<div style=\"border-top:1px solid var(--line);margin-top:10px;padding-top:8px\">" +
        "<div style=\"color:var(--dim);font-size:10px;margin-bottom:4px\">projects to research/run — point this at any local git repo (a clone or a <code>git worktree</code> checkout); its worktree is absorbed into the store under <code>repos/&lt;id&gt;/</code> and watched live, so VM guests can browse and run against it same as the daemon's own tree. Release stops the watcher; already-absorbed content stays.</div>" +
        "<div class=\"vmctl\"><input id=\"occPath\" placeholder=\"/absolute/path/to/repo\" style=\"width:260px\"><button onclick=\"occupyRepo()\">occupy</button></div>" +
        "<div id=\"occOut\" style=\"color:var(--dim);font-size:10px;margin:2px 0 6px\"></div>" +
        (if (projSheet.isNotEmpty()) "<table><tr><th>id</th><th>path</th><th>paths</th><th>last reconcile</th><th></th></tr>" +
            projSheet.joinToString("") { p ->
                "<tr><td>" + esc(p.id) + "</td><td style=\"max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap\" title=\"" + esc(p.path) + "\">" + esc(p.path) +
                    "</td><td style=\"text-align:right\">" + borg.trikeshed.web.graal.loc(num(orr(p.paths, 0))) + "</td><td>" + (if (truthy(p.lastReconcileMs)) timeHms(p.lastReconcileMs) else "") +
                    "</td><td><button class=\"vmbtn\" onclick=\"releaseRepo('" + esc(p.id) + "')\">release</button></td></tr>"
            } + "</table>"
        else "<i style=\"color:var(--dim)\">no repos occupied — the primary worktree and ~/.hermes are watched by the daemon itself and aren't listed here</i>") +
        "</div>"

fun GraalConsole.togglePoints() {
    if (q("points").style.display == "flex") { hide("points"); return }
    scope.launch {
        try {
            val r: dynamic = fetchJson("/api/graal/pointcuts")
            val routes = orr(r.routes, js("[]")).unsafeCast<Array<dynamic>>()
            q("pointsBody").innerHTML = if (routes.isNotEmpty())
                "<table><tr><th>route</th><th>facet</th><th>class.method@bci</th><th>prop</th><th>value</th></tr>" +
                    routes.joinToString("") { x ->
                        "<tr><td>" + str(x.route) + "</td><td>" + s0(orr(x.facet, "")) + "</td><td>" + s0(orr(x.className, "")) + "." + s0(orr(x.methodName, "")) + "@" + s0(orr(x.bci, "")) +
                            "</td><td>" + s0(orr(x.property, "")) + "</td><td>" + s0(orr(x.value, "")) + "</td></tr>"
                    } + "</table>"
            else "<i>no pointcut landings yet — the routes appear as the instrumented code runs</i>"
        } catch (e: Throwable) { q("pointsBody").textContent = "fetch failed: " + errText(e) }
        show("points")
    }
}

/* ── AOT ── */
var aotState: dynamic = newObject()

suspend fun GraalConsole.loadAotNow() {
    try {
        aotState = fetchJson("/api/graal/aot")
        first("cAot").textContent = if (truthy(aotState.exists)) fmtBytes(num(orr(aotState.bytes, 0))) else if (truthy(aotState.cacheOutput)) "record" else "off"
    } catch (e: Throwable) { aotState = newObject(); aotState.error = errText(e); first("cAot").textContent = "–" }
}

fun GraalConsole.loadAot() { scope.launch { loadAotNow() } }

fun GraalConsole.toggleAot() {
    if (q("aot").style.display == "flex") { hide("aot"); return }
    scope.launch {
        loadAotNow()
        val s: dynamic = aotState
        q("aotBody").innerHTML = "<table class=\"kv\">" + entries(s).joinToString("") { "<tr><td>" + esc(it[0]) + "</td><td>" + esc(it[1]) + "</td></tr>" } + "</table>" +
            (if (truthy(s.exists)) "<div style=\"margin-top:8px\"><a href=\"/api/graal/aot/blob\" style=\"color:var(--commit)\">download opaque HotSpot archive</a> · <button onclick=\"captureAot()\">capture into Couch/CAS</button></div>"
            else "<div style=\"margin-top:8px;color:var(--dim)\">" + (if (truthy(s.error)) "AOT state unavailable" else if (truthy(s.cacheOutput)) "Recording configured; archive pending" else if (truthy(s.cacheInput)) "Configured archive unavailable" else "AOT is not configured for this process") + "</div>") +
            "<div style=\"margin-top:8px;color:var(--dim)\">The archive is opaque VM state. Decompilation uses its mated .class blobs through java.lang.classfile, never fake parsing of the .aot container.</div>"
        show("aot")
    }
}

fun GraalConsole.captureAot() {
    scope.launch {
        val r = window.fetch("/api/graal/aot/capture", methodInit("POST")).await()
        val x: dynamic = r.json().await()
        if (r.ok) {
            river("commit", "AOT " + str(x.id))
            q("aotBody").insertAdjacentHTML("beforeend", "<div style=\"color:var(--compile);margin-top:6px\">captured " + chip(x.cid) + " · " + fmtBytes(num(x.bytes)) + "</div>")
            loadMap()
        } else q("aotBody").insertAdjacentHTML("beforeend", "<div style=\"color:var(--deopt);margin-top:6px\">capture failed: " + esc(orr(x.error, r.status)) + "</div>")
    }
}

/* ── the flourish feed ── */
val COLORS = mapOf("build" to "#68c8c1", "compile" to "var(--compile)", "deopt" to "var(--deopt)", "gc" to "var(--gc)", "commit" to "var(--commit)", "cpu" to "var(--cpu)", "vm" to "var(--vm)")
val PING = mapOf("build" to "#68c8c1", "compile" to "#3ddc84", "deopt" to "#ff4f58", "gc" to "#ffb02e", "commit" to "#3fd0ff")

fun GraalConsole.river(kind: String, text: String) {
    val d = document.createElement("div") as HTMLElement; d.className = "evt"; d.style.borderLeftColor = COLORS[kind] ?: "var(--dim)"
    d.asDynamic().dataset.kind = kind
    val time = document.createElement("small") as HTMLElement; time.textContent = nowHms()
    val tag = document.createElement("b") as HTMLElement; tag.style.color = COLORS[kind] ?: "#fff"; tag.textContent = kind
    d.append(time, " ", tag, " ", text)
    val r = q("river"); r.prepend(d)
    val rows = r.querySelectorAll(if (kind == "build") ".evt[data-kind=\"build\"]" else ".evt:not([data-kind=\"build\"])").asList()
    for (i in (if (kind == "build") 8 else 26) until rows.size) (rows[i] as HTMLElement).remove()
}

fun GraalConsole.flash(chip: HTMLElement, cls: String) {
    if (reduced) return
    chip.classList.add(cls); window.setTimeout({ chip.classList.remove(cls) }, 450)
}

/* Seismic coalescing: a re-absorption storm is ONE seismic event per top territory with a magnitude; quiet commits still ping one-by-one. */
class Quake(val n: TerrainNode?, var count: Int, val kind: String, val color: String?, val label: String, val territory: String)

val quakeBuf = LinkedHashMap<String, Quake>()
var quakeTimer: Int? = null
var recentCommits = 0

fun GraalConsole.topOf(n: TerrainNode?): TerrainNode? { var t = n; while (t != null && t.parent != null && t.parent !== root) t = t.parent; return t ?: n }

fun GraalConsole.quake(e: dynamic, n: TerrainNode?, kind: String = "commit") {
    recentCommits++
    val color = PING[kind]
    val label = (if (truthy(e.deleted)) "deleted " else "") + (if (e.phase == "compiled") "compiler output" else if (e.phase == "staged") "staged output" else kind)
    if (recentCommits <= 12) { river(kind, label + " " + str(e.id)); pingAt(n, color ?: "undefined"); return }
    val t = if (kind == "commit") topOf(n ?: root) else ((if (n?.leaf == true) n.parent else n) ?: root)
    val territory = if (t === root) "/" else pathOf(t); val k = "$label:$territory"
    val qk = quakeBuf[k] ?: Quake(t, 0, kind, color, label, territory)
    qk.count++; quakeBuf[k] = qk
    if (quakeTimer == null) quakeTimer = window.setTimeout({
        for (x in quakeBuf.values) {
            river(x.kind, loc(x.count) + " " + x.label + " in " + x.territory)
            pingAt(x.n, x.color ?: "undefined", x.count.toString())
            if (x.kind == "commit" && !reduced && x.count > 200) shake = min(shake + 4, 8.0)
        }
        quakeBuf.clear(); quakeTimer = null
    }, 900)
}

fun GraalConsole.touchDocument(e: dynamic) {
    if (borg.trikeshed.web.graal.typeOf(e.id) != "string") return
    val id = str(e.id)
    heat[id] = perfNow()
    val n = nodeFor(id); heatUp(n)
    if (e.kind == "commit") {
        if (truthy(e.deleted) || !byIdMap.containsKey(id)) requestMapRefresh()
        if (byIdMap.containsKey(id) && isFiniteNum(e.seq) && n != null) {
            val seq = num(e.seq).toLong()
            n.seq = seq; maxSeq = max(maxSeq, seq)
            var p = n.parent
            while (p != null) { p.maxSeq = max(p.maxSeq, seq); p = p.parent }
        }
    }
    quake(e, n, if (e.kind == "class-update") "build" else "commit")
}

fun GraalConsole.touchCompilation(e: dynamic) {
    // Short method labels are not identities: use the declaring class supplied by JFR.
    for (n in classNodes[s0(e.className)] ?: emptyList()) heatUp(n)
}

fun GraalConsole.connectSse() {
    val es = EventSource("/api/graal/events")
    es.onmessage = { m: MessageEvent ->
        try {
            val e: dynamic = JSON.parse<dynamic>(str(m.data))
            when (e.kind) {
                "vm" -> { vmLine(e); river(if (e.vmKind == "revoked") "deopt" else "commit", "vm " + str(e.vmKind) + " " + s0(orr(e.vmId, "")) + " — " + vmDetail(e)) }
                "compile" -> { touchCompilation(e); river("compile", str(orr(e.method, "?")) + " L" + str(if (e.level == null) "?" else e.level) + " " + fmtBytes(num(orr(e.codeSize, 0)))); flash(q("cComp"), "flash-compile") }
                "deopt" -> {
                    river("deopt", str(orr(e.reason, "?")) + " → " + str(orr(e.action, "?")) + " #" + s0(if (e.compileId == null) "" else e.compileId))
                    flash(q("cDeopt"), "flash-deopt"); shake = min(shake + 5, 9.0); pingAt(nodeFor("pointcut"), PING.getValue("deopt"), if (e.reason == null) null else str(e.reason))
                }
                "gc" -> { river("gc", str(orr(e.name, "?")) + " " + s0(orr(e.cause, "")) + " " + str(orr(e.pauseMs, 0)) + "ms"); flash(q("cGc"), "flash-gc") }
                "class-update", "commit" -> touchDocument(e)
                "score" -> river("compile", "scoring session " + s0(orr(e.session, "")).take(12) + "… seq=" + str(if (e.corpusSeq == null) "?" else e.corpusSeq) + " " + str(if (e.scores == null) 0 else e.scores) + " scores — " + str(orr(e.confidence, "?")))
                "cpu" -> {}
                else -> river(str(orr(e.kind, "?")), JSON.stringify(e).take(90))
            }
        } catch (_: Throwable) {}
    }
    es.onerror = { es.close(); window.setTimeout({ connectSse() }, 3000) }
}

/* ── boot: map, product rows, heap continent ── */
suspend fun productRows(): List<Array<dynamic>> {
    val rows = ArrayList<Array<dynamic>>(); productDocs.clear()
    try {
        val b: dynamic = fetchJson("/api/board")
        val cards: dynamic = orr(b.cards, if (truthy(b.board)) b.board.cards else null) ?: js("[]")
        for (c in (if (isArray(cards)) cards else js("[]")).unsafeCast<Array<dynamic>>()) {
            val col = str(orr(orr(orr(c.column, c.columnId), c.status), "inbox"))
            val title = str(orr(orr(c.title, c.id), "card")).take(60)
            val id = ProductIds.card(col, title)
            rows.add(arrayOf(id, 64 + (num(s0(orr(c.description, "")).length).takeIf { it != 0.0 } ?: 64.0), 0, 1))
            val pd: dynamic = newObject(); pd.kind = "card"; pd.title = title; pd.column = col; pd.card = c
            productDocs[id] = pd
        }
    } catch (_: Throwable) {}
    try {
        val s: dynamic = fetchJson("/api/jules/surface")
        val sess: dynamic = orr(if (truthy(s.surface)) s.surface.sessions else null, orr(s.sessions, js("[]")))
        for (x in (if (isArray(sess)) sess else js("[]")).unsafeCast<Array<dynamic>>()) {
            val nm = str(orr(orr(orr(x.title, x.id), x.sessionId), "session")).take(50)
            val id = ProductIds.session(nm)
            rows.add(arrayOf(id, 256, 0, 1))
            val pd: dynamic = newObject(); pd.kind = "session"; pd.title = nm; pd.session = x
            productDocs[id] = pd
        }
    } catch (_: Throwable) {}
    return rows
}

var mapTimer: Int? = null
var mapBusy = false
var mapAgain = false
var mapUrgent = false
var heapTimer: Int? = null
var heapBusy = false

fun GraalConsole.requestMapRefresh() {
    if (mapBusy) { mapAgain = true; return }
    if (mapUrgent) return
    mapUrgent = true; mapTimer?.let { window.clearTimeout(it) }; mapTimer = window.setTimeout({ loadMap() }, 750)
}

fun GraalConsole.loadMap() { scope.launch { loadMapNow() } }

suspend fun GraalConsole.loadMapNow() {
    if (mapBusy) { mapAgain = true; return }
    mapTimer?.let { window.clearTimeout(it) }; mapUrgent = false; mapBusy = true
    var mapErr = ""
    try {
        val m: dynamic = fetchJson("/api/graal/map")
        PROJECT_DBS = orr(m.dbs, js("[]")).unsafeCast<Array<String>>().toSet()
        val prod = productRows()
        lastRows = orr(m.rows, js("[]")).unsafeCast<Array<dynamic>>() + prod.map { it.asDynamic() }.toTypedArray()
    } catch (e: Throwable) { mapErr = errText(e) }
    if (lastRows == null) lastRows = emptyArray()
    if (lastRows!!.isEmpty()) crumb("map fetch failed: " + mapErr.ifEmpty { "empty" })
    try { buildAll() } finally {
        mapBusy = false; mapTimer = window.setTimeout({ loadMap() }, if (mapAgain) 750 else 30000); mapAgain = false
    }
}

fun GraalConsole.loadHeap() {
    if (heapBusy) return
    heapTimer?.let { window.clearTimeout(it) }; heapBusy = true
    scope.launch {
        try {
            val h: dynamic = fetchJson("/api/graal/heap"); heapRows = heapContinentRows(h)
            if (HEAP_TERRAIN) buildAll()
        } catch (_: Throwable) { if (heapRows == null) heapRows = emptyArray() }
        finally { heapBusy = false; heapTimer = window.setTimeout({ loadHeap() }, 30000) }
    }
}

/* R1: the JVM heap continent as treemap rows — per-class live-set (GC class histogram) and JFR allocation samples. */
fun heapContinentRows(h: dynamic): Array<dynamic> {
    val rows = ArrayList<dynamic>()
    for (r in orr(h.rows, js("[]")).unsafeCast<Array<dynamic>>()) {
        val b = num(orr(r.bytes, 0)); if (b == 0.0 || b.isNaN()) continue
        val id = "heap-live/" + str(r["class"])
        rows.add(arrayOf<dynamic>(id, b, 0, 1, 0))
        val leaf: dynamic = newObject(); leaf.kind = "live"; leaf.cls = r["class"]; leaf.count = orr(r.count, 0); leaf.bytes = b
        heapLeaves[id] = leaf
    }
    for (a in orr(h.allocation, js("[]")).unsafeCast<Array<dynamic>>()) {
        val b = num(orr(a.bytes, 0)); if (b == 0.0 || b.isNaN()) continue
        val id = "heap-alloc/" + str(a["class"])
        rows.add(arrayOf<dynamic>(id, b, 0, 1, 0))
        val leaf: dynamic = newObject(); leaf.kind = "alloc"; leaf.cls = a["class"]; leaf.bytes = b
        heapLeaves[id] = leaf
    }
    return rows.toTypedArray()
}
