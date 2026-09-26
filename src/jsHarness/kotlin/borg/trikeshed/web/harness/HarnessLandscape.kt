package borg.trikeshed.web.harness

import borg.trikeshed.landscape.ActivityState
import borg.trikeshed.landscape.LandscapeActivity
import borg.trikeshed.landscape.ProgramEvidence
import borg.trikeshed.web.PatchRead
import borg.trikeshed.web.arr
import borg.trikeshed.web.isArray
import borg.trikeshed.web.num
import borg.trikeshed.web.str
import borg.trikeshed.web.typeOf
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * landscape.js: the blackboard's canvas landscape under the patch surface — program territories at
 * overview zoom, the Graal object terrain, per-node overview boxes and labels, activity lamps derived
 * from commonMain [LandscapeActivity], and the object/program-version inspectors.
 */
/** JS Math.round: half rounds up, not to even */
fun jsRound(v: Double): Double = js("Math.round(v)").unsafeCast<Double>()

@OptIn(DelicateCoroutinesApi::class)
object HarnessLandscape {
    private val p get() = HarnessPatch
    private val view: dynamic get() = p.view
    private val viewport: HTMLElement get() = p.viewport
    private val h get() = Harness

    var detailOwner: String? = null
    val details = JsSet<dynamic>()
    const val labelBudget = 48
    var labels = JsMap<dynamic, dynamic>()
    var labelLayer: dynamic = null
    val canvas: dynamic = document.getElementById("landscape")
    var pending = 0
    var terrain: dynamic = null
    var rows: dynamic = js("[]")
    var dbs: dynamic = js("[]")
    var edges: dynamic = js("[]")
    var hits = ArrayList<dynamic>()
    var storeRows: dynamic = js("[]")
    var runtimeRows: dynamic = js("[]")
    var mask = 127
    var heapBusy = false
    var heapLoaded = false
    var heapStatus = "Not loaded"
    var poolStatus = "Not loaded"
    var activityMask = LinkedHashSet(ActivityState.entries.map { stateId(it) })
    var activityNodes = JsMap<dynamic, dynamic>()
    var activityPrograms = JsMap<dynamic, dynamic>()
    var activityFacts = JsMap<dynamic, dynamic>()
    var activityLinks: dynamic = js("[]")
    var activityTimer = 0
    var objectBox: dynamic = js("({x:3000,y:1000,w:2200,h:1600})")
    var detailLevel = 50
    var legend: dynamic = null
    var drawingGeometry: JsMap<dynamic, dynamic>? = null
    var closureKey: String? = null

    fun stateId(s: ActivityState): String = s.name.lowercase()
    fun stateOf(id: dynamic): ActivityState = ActivityState.entries.firstOrNull { stateId(it) == id } ?: ActivityState.UNKNOWN

    /** `LandscapeActivity.states` as the JS rows `{id,label,color,hint}` */
    fun statesJs(): dynamic = ActivityState.entries.map { s -> obj { it.id = stateId(s); it.label = s.label; it.color = s.color; it.hint = s.hint } }.toTypedArray()

    // ── JS board values ⇄ the commonMain fact plane ──
    fun toKotlin(v: dynamic): Any? = when {
        v == null -> null
        isArray(v) -> arr(v).map { toKotlin(it) }
        typeOf(v) == "object" -> { val m = LinkedHashMap<String, Any?>(); for (k in keysOf(v)) m[k] = toKotlin(v[k]); m }
        else -> v
    }

    fun toJs(v: Any?): dynamic = when (v) {
        null -> null
        is Map<*, *> -> { val o = jsObject(); for ((k, x) in v) o[k.toString()] = toJs(x); o }
        is List<*> -> v.map { toJs(it) }.toTypedArray()
        else -> v
    }

    fun evidenceJs(e: ProgramEvidence): dynamic = obj {
        it.state = stateId(e.state); it.reason = e.reason
        if (e.receipt != null) it.receipt = toJs(e.receipt)
        if (e.atMs != null) it.atMs = e.atMs.toDouble()
        it.activeRuns = e.activeRuns
        if (e.lastReceipt != null) it.lastReceipt = toJs(e.lastReceipt)
    }

    fun evidenceOf(e: dynamic): ProgramEvidence = ProgramEvidence(
        stateOf(e.state), str(e.reason ?: ""),
        receipt = if (truthy(e.receipt)) toKotlin(e.receipt).unsafeCast<Map<String, Any?>>() else null,
        atMs = if (e.atMs != null) num(e.atMs).toLong() else null,
        activeRuns = if (truthy(e.activeRuns)) num(e.activeRuns).toInt() else 0,
        lastReceipt = if (truthy(e.lastReceipt)) toKotlin(e.lastReceipt).unsafeCast<Map<String, Any?>>() else null,
    )

    /** run receipts carry their stale marker as `stale`, as `{...r, stale}` did */
    private fun runPlane(board: dynamic): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for (key in keysOf(board)) {
            if (!key.startsWith("lcnc/run/")) continue
            val r = board[key]
            if (!truthy(r) || !truthy(r.programKey)) continue
            val fields = toKotlin(r).unsafeCast<LinkedHashMap<String, Any?>>()
            fields["stale"] = toKotlin(board["lcnc/stale/" + str(r.runId)] ?: null)
            m[key] = fields
            val staleKey = "lcnc/stale/" + str(r.runId)
            if (truthy(board[staleKey])) m[staleKey] = toKotlin(board[staleKey])
        }
        return m
    }

    private fun programEntry(entry: dynamic): Map<String, Any?>? {
        if (!truthy(entry)) return null
        val m = LinkedHashMap<String, Any?>()
        m["programCid"] = entry.programCid
        val inspectionOnly = entry.document?.controls?.inspectionOnly
        if (inspectionOnly != null) m["document"] = mapOf("controls" to mapOf("inspectionOnly" to inspectionOnly))
        return m
    }

    private fun referencePlane(board: dynamic): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        for (key in keysOf(board)) {
            val v = board[key]
            if (!truthy(v) || typeOf(v) != "object") continue
            val f = LinkedHashMap<String, Any?>()
            if (v.angular != null) f["angular"] = v.angular
            if (v.jobId != null) f["jobId"] = v.jobId
            m[key] = f
        }
        return m
    }

    fun refreshActivity() {
        val board = h.board
        val runs = LandscapeActivity.latest(runPlane(board)); val now = Date.now()
        activityPrograms = JsMap(); activityNodes = JsMap(); activityFacts = JsMap()
        for (key in h.programs()) {
            val name = key.substring(13)
            activityPrograms.set(name, evidenceJs(LandscapeActivity.program(name, programEntry(board[key]), runs, h.drafts.has(name), h.live, now)))
        }
        for (n in p.nodes()) {
            val program = activityPrograms.get(n._program) ?: obj { it.state = "unknown"; it.reason = "No program evidence" }
            activityNodes.set(n.id, evidenceJs(LandscapeActivity.node(str(if (truthy(n._localId)) n._localId else n.id), evidenceOf(program),
                timerArmed = truthy(n._timer), eventSubscriptionOpen = n._es?.readyState == 1)))
        }
        for (key in keysOf(board)) if (!key.startsWith("lcnc/")) {
            val v = board[key]
            val fact = if (truthy(v) && typeOf(v) == "object") mapOf("event" to v.event, "atMs" to v.atMs) else null
            activityFacts.set(key, evidenceJs(LandscapeActivity.fact(key, fact)))
        }
        activityLinks = LandscapeActivity.references(referencePlane(board)).map { r -> obj { it.from = r.from; it.to = r.to; it.kind = r.kind } }.toTypedArray()
        fun paint(el: dynamic, evidence: dynamic, label: String, inspect: () -> Unit) {
            if (!truthy(el) || !truthy(evidence)) return
            val state = stateOf(evidence.state)
            el.dataset.activity = evidence.state; el.style.setProperty("--activity-color", state.color)
            el.classList.toggle("activity-muted", !activityMask.contains(str(evidence.state)))
            var lamp = el.querySelector(":scope > .activity-lamp")
            if (lamp == null) {
                lamp = document.createElement("button").asDynamic(); lamp.className = "activity-lamp"; lamp.textContent = "●"
                lamp.addEventListener("pointerdown", { e: dynamic -> e.stopPropagation() })
                lamp.addEventListener("click", { e: dynamic -> e.stopPropagation(); inspect() }); el.append(lamp)
            }
            lamp.title = state.label + ": " + str(evidence.reason); lamp.setAttribute("aria-label", label + ": " + state.label + ". Inspect activity")
        }
        for (n in p.nodes()) paint(n.el, activityNodes.get(n.id), str(n.type)) { inspectActivity(str(n._program), n.id) }
        for (el in arr(document.querySelectorAll(".territory[data-territory]"))) {
            val key = str(el.dataset.territory)
            if (key.startsWith("lcnc/program/")) paint(el, activityPrograms.get(key.substring(13)), key.substring(13)) { inspectActivity(key.substring(13)) }
        }
        for (el in arr(document.querySelectorAll(".fact-row[data-key],.fact-cell[data-key]"))) {
            val evidence = activityFacts.get(el.dataset.key) ?: continue
            el.dataset.activity = evidence.state; el.style.setProperty("--activity-color", stateOf(evidence.state).color)
            el.classList.toggle("activity-muted", !activityMask.contains(str(evidence.state))); el.title = str(el.dataset.key) + "\n" + str(evidence.reason)
        }
        val counts = LinkedHashMap<String, Int>(); for (s in ActivityState.entries) counts[stateId(s)] = 0
        for (e in activityPrograms.valueList() + activityNodes.valueList() + activityFacts.valueList()) counts[str(e.state)] = (counts[str(e.state)] ?: 0) + 1
        for (el in arr(document.querySelectorAll("#activityCategories output"))) el.textContent = (counts[str(el.dataset.state)] ?: 0).toString()
        val selection = document.getElementById("activitySelection").asDynamic()
        val current = if (h.selected != null) activityPrograms.get(h.selected) else null
        if (selection != null) {
            selection.textContent = (h.selected ?: "No program") + ": " + (if (current != null) stateOf(current.state).label else "Unknown")
            selection.title = current?.reason ?: "No selected program"; selection.disabled = h.selected == null
        }
        schedule()
    }

    fun activityVisible(node: dynamic): Boolean = activityMask.contains(str(activityNodes.get(node?.id)?.state ?: "unknown"))

    fun inspectActivity(program: String, nodeId: dynamic = null) {
        val evidence = if (nodeId != null) activityNodes.get(nodeId) else activityPrograms.get(program)
        h.beginInspection(program + (if (nodeId != null) " / " + str(nodeId) else ""), "Activity evidence"); h.rawSheets(true)
        val summary = obj {
            it.state = evidence?.state ?: "unknown"; it.reason = evidence?.reason; it.atMs = evidence?.atMs
            it.programCid = h.board["lcnc/program/$program"]?.programCid; it.stale = evidence?.receipt?.stale ?: null
            it.receipt = evidence?.receipt ?: null; it.lastOtherVersionReceipt = evidence?.lastReceipt ?: null
        }
        byId("factValue").textContent = jsonString(summary, true)
        if (evidence?.state == "stale" && truthy(evidence.receipt?.runId)) {
            val button = document.createElement("button").asDynamic(); button.className = "terrain-ref"; button.textContent = "Rebuild"
            button.title = "Re-run this exact program version over the inputs as they are now"
            val receipt = evidence.receipt
            button.addEventListener("click", { GlobalScope.launch { try { h.rebuild(receipt) } catch (e: CancellationException) { throw e } catch (e: Throwable) { h.message(errorMessage(e)) } } })
            byId("factInspector").append(button)
        }
    }

    fun positionObjects() {
        val x = h.programRight() + 1760
        if (num(objectBox.x) == x) return
        objectBox = obj { it.x = x; it.y = 0; it.w = 3200; it.h = 2400 }
        terrain?.setRows(rows, objectBox, dbs)
    }

    fun schedule() { if (pending != 0) return; pending = window.requestAnimationFrame { pending = 0; draw() } }

    fun viewPercent(): Double = jsRound(max(0.0, min(100.0, num(view.z) * 100)) * 100) / 100
    fun detailVisible(): Boolean = viewPercent() >= 100 - detailLevel

    fun updateDetailIndicator() {
        val percent = viewPercent(); val cutoff = 100 - detailLevel
        for ((id, value) in listOf("viewPercent" to "${jsString(percent)}%", "detailCutoff" to "$cutoff%")) {
            val el = document.getElementById(id); if (el != null && el.textContent != value) el.textContent = value
        }
        val meter = document.getElementById("viewability").asDynamic()
        if (meter != null) { meter.value = percent; meter.low = cutoff; meter.title = if (percent >= cutoff) "Rendered" else "Overview" }
    }

    fun setDetail(value: dynamic, persist: Boolean = true) {
        if (value == null || value == "" || !jsNumber(value).isFinite()) return
        detailLevel = max(0, min(100, jsRound(jsNumber(value)).toInt()))
        val slider = document.getElementById("detailLevel").asDynamic(); val output = document.getElementById("detailValue")
        if (slider != null) { slider.value = detailLevel.toString(); slider.setAttribute("aria-valuetext", "$detailLevel% detail; view cutoff ${100 - detailLevel}%") }
        if (output != null) output.textContent = "$detailLevel%"
        if (persist) try { localStorage.setItem("blackboard.detail", detailLevel.toString()) } catch (_: Throwable) {}
        updateDetailIndicator(); schedule()
    }

    fun detailFor(node: dynamic, @Suppress("UNUSED_PARAMETER") box: dynamic, absorbed: Boolean): Boolean {
        if (detailOwner != h.selected) { detailOwner = h.selected; details.asDynamic().clear() }
        val held = details.has(node.id)
        val resolved = !absorbed && detailVisible()
        // Other programs may resolve too; selection governs mutation, not visibility.
        // A live edit keeps its original element when zooming back into the overview.
        val active = document.activeElement
        val editing = held && truthy(node.el) && active != null && truthy(node.el.contains(active))
        if (resolved || editing) details.add(node.id) else details.asDynamic().delete(node.id)
        return details.has(node.id)
    }

    class Label(val id: dynamic, val text: String, val x: Double, val y: Double, val w: Double, val h: Double)
    class LabelCandidate(val node: dynamic, val box: dynamic, val clip: dynamic)

    fun labelLayout(candidates: List<LabelCandidate>, measure: (String) -> Double): List<Label> {
        val placed = ArrayList<Label>()
        val sorted = candidates.sortedWith(compareBy<LabelCandidate>(
            { if (it.node._program == h.selected) 0 else 1 },
            { if (labels.has(it.node.id)) 0 else 1 },
            { -(num(it.box.w) * num(it.box.h)) },
        ))
        for (c in sorted) {
            if (placed.size >= labelBudget) break
            val node = c.node; val box = c.box; val clip = c.clip
            val text = if (truthy(node.params?.archiveCid)) (str(node.params.archivePath ?: "").replace(Regex("/$"), "").split("/").last().ifEmpty { "Archive" })
                else if (node.type == "scope.in") "in: " + str(if (truthy(node.params?.name)) node.params.name else "?")
                else if (node.type == "scope.out") "yield: " + str(if (truthy(node.params?.name)) node.params.name else "?")
                else str(node.type)
            val w = min(208.0, min(measure(text) + 8, num(clip.right) - num(clip.left))); val hh = 18.0
            if (w < 32 || num(clip.bottom) - num(clip.top) < hh) continue
            val x = max(num(clip.left), min(num(box.x), num(clip.right) - w))
            for (top in listOf(num(box.y) - hh, num(box.y) + num(box.h) + 2)) {
                val y = max(num(clip.top), min(top, num(clip.bottom) - hh))
                if (placed.any { q -> x < q.x + q.w + 4 && x + w + 4 > q.x && y < q.y + q.h + 3 && y + hh + 3 > q.y }) continue
                placed.add(Label(node.id, text, x, y, w, hh)); break
            }
        }
        return placed
    }

    /** Territory names orient the distant overview; node labels bridge only unresolved boxes with room for text. */
    fun labelFor(box: dynamic, detail: Boolean, absorbed: Boolean, onScreen: Boolean): Boolean =
        onScreen && !absorbed && !detail && num(box.w) >= 48 && num(box.h) >= 20

    fun drawLabels(candidates: List<LabelCandidate>, ctx: dynamic) {
        ctx.save(); ctx.font = "12px system-ui"
        val placed = labelLayout(candidates) { text -> num(ctx.measureText(text).width) }
        ctx.restore()
        if (placed.isEmpty() && labels.size == 0) return
        if (labelLayer == null) {
            labelLayer = document.createElement("div"); labelLayer.id = "landscape-labels"
            labelLayer.setAttribute("aria-hidden", "true"); viewport.appendChild(labelLayer)
        }
        val next = JsMap<dynamic, dynamic>()
        for (q in placed) {
            var el = labels.get(q.id)
            if (el == null) { el = document.createElement("span").asDynamic(); el.dataset.nodeId = q.id; labelLayer.appendChild(el) }
            if (el.textContent != q.text) el.textContent = q.text
            el.style.transform = "translate(${q.x}px,${q.y}px)"; el.style.width = "${q.w}px"
            next.set(q.id, el)
        }
        for ((id, el) in labels.entryList()) if (!next.has(id)) el.remove()
        labels = next
    }

    /** Read all layout before changing visibility/inert: interleaving forced a style flush per node. */
    fun measure(vr: dynamic): JsMap<dynamic, dynamic> {
        val geometry = JsMap<dynamic, dynamic>()
        for (n in p.nodes()) if (truthy(n.el)) {
            val rect = n.el.getBoundingClientRect()
            geometry.set(n, obj { it.rect = rect; it.host = n._childHost?.getBoundingClientRect() })
        }
        fun intersect(a: dynamic, b: dynamic): dynamic = obj {
            it.left = max(num(a.left), num(b.left)); it.top = max(num(a.top), num(b.top)); it.right = min(num(a.right), num(b.right)); it.bottom = min(num(a.bottom), num(b.bottom))
        }
        fun clip(n: dynamic): dynamic {
            val g = geometry.get(n) ?: return vr
            if (truthy(g.clip)) return g.clip
            val parent = if (n._parentScope != null) geometry.get(n._parentScope) else null
            g.clip = if (truthy(parent?.host)) intersect(clip(n._parentScope), parent.host) else vr
            return g.clip
        }
        for (n in geometry.keyList()) clip(n)
        return geometry
    }

    fun visibleClosure(node: dynamic): dynamic {
        val measured = drawingGeometry
        if (detailVisible()) return node
        if (node?._program == h.selected && details.has(node.id)) return node
        var result = node; var parent = node?._parentScope
        while (truthy(parent)) {
            val r = measured?.get(parent)?.rect ?: parent.el.getBoundingClientRect()
            if (num(r.width) < 420 || num(r.height) < 240) result = parent
            parent = parent._parentScope
        }
        return result
    }

    fun draw() {
        if (!h.ready) return
        updateDetailIndicator()
        val width = num(viewport.clientWidth); val height = num(viewport.clientHeight); val dpr = if (window.devicePixelRatio > 0) window.devicePixelRatio else 1.0
        if (num(canvas.width) != jsRound(width * dpr) || num(canvas.height) != jsRound(height * dpr)) {
            canvas.width = jsRound(width * dpr); canvas.height = jsRound(height * dpr)
        }
        val ctx = canvas.getContext("2d"); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, width, height)
        val z = num(view.z); val vx = num(view.x); val vy = num(view.y)
        fun screen(b: dynamic): dynamic = obj { it.x = num(b.x) * z + vx; it.y = num(b.y) * z + vy; it.w = num(b.w) * z; it.h = num(b.h) * z }
        hits = ArrayList()
        for ((key, box) in h.positions.entryList()) {
            val b = screen(box); if (num(b.x) > width || num(b.y) > height || num(b.x) + num(b.w) < 0 || num(b.y) + num(b.h) < 0) continue
            val program = key.startsWith("lcnc/program/")
            if (z < .45) {
                ctx.fillStyle = if (program) "#1b3738" else if (key == "narsese") "#3a3624" else "#222a30"; ctx.fillRect(b.x, b.y, b.w, b.h)
                ctx.strokeStyle = if (h.flashes.has(key)) "#e0c26d" else if (program) "#639d96" else "#7f8990"; ctx.lineWidth = 1; ctx.strokeRect(b.x, b.y, b.w, b.h)
                if (num(b.w) > 75 && num(b.h) > 22) { ctx.fillStyle = "#d8e5e2"; ctx.font = "12px system-ui"; ctx.fillText((if (program) key.substring(13) else key).take(floor(num(b.w) / 7).toInt()), num(b.x) + 5, num(b.y) + 16) }
                if (program) {
                    val evidence = activityPrograms.get(key.substring(13))
                    if (evidence != null && activityMask.contains(str(evidence.state))) { ctx.fillStyle = stateOf(evidence.state).color; ctx.fillRect(b.x, b.y, min(num(b.w), 8.0), min(num(b.h), 8.0)) }
                }
            }
            hits.add(obj { it.box = box; it.key = key })
        }
        if (terrain != null) terrain.draw(obj { it.s = z; it.ox = -vx / z; it.oy = -vy / z }, width, height)
        val byNodeId = JsMap<dynamic, dynamic>(); for (n in p.nodes()) byNodeId.set(n.id, n)
        val vr = viewport.getBoundingClientRect().asDynamic()
        val geometry = measure(vr); val visibility = ArrayList<Pair<dynamic, Boolean>>(); val labelCandidates = ArrayList<LabelCandidate>()
        drawingGeometry = geometry
        try {
            ctx.strokeStyle = "#81aaa8"; ctx.lineWidth = .7
            for (wire in p.wires()) {
                val from = byNodeId.get(wire.from[0]); val to = byNodeId.get(wire.to[0]); if (!truthy(from?.el) || !truthy(to?.el)) continue
                val closure = visibleClosure(from)
                if (closure === from || closure !== visibleClosure(to) || !truthy(closure._childHost)) continue
                val a = geometry.get(from).rect; val b = geometry.get(to).rect; val clip = geometry.get(closure).host
                if (num(clip.right) < num(vr.left) || num(clip.left) > num(vr.right) || num(clip.bottom) < num(vr.top) || num(clip.top) > num(vr.bottom)) continue
                ctx.save(); ctx.beginPath(); ctx.rect(num(clip.left) - num(vr.left), num(clip.top) - num(vr.top), clip.width, clip.height); ctx.clip()
                ctx.beginPath(); ctx.moveTo(num(a.right) - num(vr.left), num(a.top) + num(a.height) / 2 - num(vr.top)); ctx.lineTo(num(b.left) - num(vr.left), num(b.top) + num(b.height) / 2 - num(vr.top)); ctx.stroke(); ctx.restore()
            }
            for (n in p.nodes()) {
                if (!truthy(n.el)) continue
                val g = geometry.get(n); val rect = g.rect; val clip = g.clip
                val box = obj { it.x = (num(rect.left) - num(vr.left) - vx) / z; it.y = (num(rect.top) - num(vr.top) - vy) / z; it.w = num(rect.width) / z; it.h = num(rect.height) / z }
                val b = screen(box); val scope = truthy(n.children?.length)
                val absorbed = visibleClosure(n) !== n
                val onScreen = num(rect.left) < num(clip.right) && num(rect.top) < num(clip.bottom) && num(rect.right) > num(clip.left) && num(rect.bottom) > num(clip.top) &&
                    num(clip.right) > num(clip.left) && num(clip.bottom) > num(clip.top)
                // Off screen is not eligible either, so panning releases what leaves the view.
                val detail = detailFor(n, b, absorbed || !onScreen)
                visibility.add(Pair(n, detail))
                if (labelFor(b, detail, absorbed, onScreen)) labelCandidates.add(LabelCandidate(n, b, obj {
                    it.left = max(0.0, num(clip.left) - num(vr.left)); it.top = max(0.0, num(clip.top) - num(vr.top))
                    it.right = min(width, num(clip.right) - num(vr.left)); it.bottom = min(height, num(clip.bottom) - num(vr.top))
                }))
                if (detail || !onScreen) continue
                val size = min(num(b.w), num(b.h))
                // The same interior survives at every depth, down to a pixel.
                if (absorbed && size < 1.5) continue
                val hue = terrain?.hueOf(str(n._program ?: n.type)) ?: 175
                ctx.save()
                if (!activityVisible(n)) ctx.globalAlpha = .16
                ctx.beginPath(); ctx.rect(num(clip.left) - num(vr.left), num(clip.top) - num(vr.top), num(clip.right) - num(clip.left), num(clip.bottom) - num(clip.top)); ctx.clip()
                val type = str(n.type)
                ctx.fillStyle = if (scope) "hsl(${jsString(hue)} 23% 28%)" else if (type.startsWith("beliefs.")) "#b2a270" else if (type.startsWith("vm.")) "#ad93c2" else "#669b9c"
                if (size < 4) ctx.fillRect(num(b.x) + num(b.w) / 2 - 1, num(b.y) + num(b.h) / 2 - 1, 2, 2)
                else { ctx.fillRect(b.x, b.y, b.w, b.h); ctx.strokeStyle = if (scope) "#8ac5bb" else "#28383b"; ctx.strokeRect(b.x, b.y, b.w, b.h) }
                val activity = activityNodes.get(n.id)
                if (activity != null && size >= 4) { ctx.fillStyle = stateOf(activity.state).color; ctx.fillRect(b.x, b.y, min(4.0, num(b.w)), min(8.0, num(b.h))) }
                ctx.restore()
                hits.add(obj { it.box = box; it.node = n })
            }
            if (z < .45) for ((key, box) in h.positions.entryList()) {
                val b = screen(box); if (num(b.w) < 75 || num(b.h) < 22 || num(b.x) > width || num(b.y) > height || num(b.x) + num(b.w) < 0 || num(b.y) + num(b.h) < 0) continue
                val program = key.startsWith("lcnc/program/"); ctx.fillStyle = "#152124"; ctx.fillRect(b.x, b.y, min(num(b.w), 330.0), 20)
                ctx.fillStyle = "#d8e5e2"; ctx.font = "12px system-ui"; ctx.fillText((if (program) key.substring(13) else key).take(floor(min(num(b.w), 330.0) / 7).toInt()), num(b.x) + 5, num(b.y) + 14)
            }
            // These links have explicit references: program.ref names and object DAG edges.
            ctx.strokeStyle = "#ac9dc5"; ctx.lineWidth = 1; ctx.setLineDash(arrayOf(4, 5))
            for (n in p.nodes()) {
                val target = n.params?.program ?: n.subprogram
                if (!truthy(target)) continue
                val to = h.positions.get("lcnc/program/" + str(target)); val from = h.positions.get("lcnc/program/" + str(n._program))
                if (to == null || from == null || to === from) continue
                val a = screen(from); val b = screen(to); ctx.beginPath(); ctx.moveTo(num(a.x) + num(a.w), num(a.y) + num(a.h) / 2)
                ctx.quadraticCurveTo(max(num(a.x) + num(a.w), num(b.x) + num(b.w)) + 50, (num(a.y) + num(b.y)) / 2, num(b.x) + num(b.w), num(b.y) + num(b.h) / 2); ctx.stroke()
            }
            for (edge in arr(edges)) {
                val source = terrain?.nodeFor(edge.from); val target = terrain?.nodeFor(edge.to)
                if (!truthy(terrain?.visible(source)) || !truthy(terrain.visible(target))) continue
                val from = source.rect; val to = target.rect; if (!truthy(from) || !truthy(to)) continue
                val a = screen(from); val b = screen(to); ctx.beginPath(); ctx.moveTo(num(a.x) + num(a.w) / 2, num(a.y) + num(a.h) / 2); ctx.lineTo(num(b.x) + num(b.w) / 2, num(b.y) + num(b.h) / 2); ctx.stroke()
            }
            ctx.setLineDash(arrayOf<Int>())
            val key = str(h.selected) + ":" + p.nodes().joinToString("|") { str(visibleClosure(it)?.id) }
            if (closureKey != key) { closureKey = key; p.redraw() }
            for ((n, detail) in visibility) {
                val value = if (detail) "visible" else "hidden"; val inert = n._program != h.selected
                if (n.el.style.visibility != value) n.el.style.visibility = value
                if (n.el.inert != inert) n.el.inert = inert
            }
            drawLabels(labelCandidates, ctx)
        } finally { drawingGeometry = null }
    }

    suspend fun refresh() {
        initLegend()
        try {
            val response = fetchResponse("/api/graal/map"); if (!response.ok) throw Error(response.status.toString())
            val map = response.jsonValue(); storeRows = map.rows ?: js("[]"); dbs = map.dbs ?: js("[]")
            positionObjects()
            if (terrain == null) terrain = createTerrain()
            updateTerrain()
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { h.message("Object terrain unavailable: " + errorMessage(e)) }
        // Runtime diagnostics must not delay blackboard connection or accepted work.
        if (!heapLoaded) GlobalScope.launch { refreshHeap() }
    }

    private fun createTerrain(): dynamic = window.asDynamic().createGraalTerrain(obj { it.canvas = canvas; it.invalidate = { schedule() } })

    fun updateTerrain() {
        rows = storeRows.concat(runtimeRows)
        terrain?.setRows(rows, objectBox, dbs)
        terrain?.setMask(mask)
        h.applyTerrainBookmark()
        updateLegend(); h.schedule(); schedule()
    }

    fun initLegend() {
        if (legend != null) return
        legend = document.getElementById("topologyLegend"); if (legend == null) return
        val topology = window.asDynamic().GraalTopology
        var detail: dynamic = detailLevel
        try { detail = localStorage.getItem("blackboard.detail") ?: detail } catch (_: Throwable) {}
        setDetail(detail, false)
        val detailSlider = document.getElementById("detailLevel").asDynamic()
        detailSlider?.addEventListener("input", { setDetail(detailSlider.value, false) })
        detailSlider?.addEventListener("change", { setDetail(detailSlider.value) })
        try {
            val saved: dynamic = JSON.parse(localStorage.getItem("graal.topology.mask") ?: "null")
            if (js("Number.isInteger(saved)") == true && num(saved) >= 0 && num(saved) <= num(topology.all)) mask = num(saved).toInt()
        } catch (_: Throwable) {}
        try {
            val saved: dynamic = JSON.parse(localStorage.getItem("graal.activity.mask") ?: "null")
            if (isArray(saved)) activityMask = LinkedHashSet(arr(saved).map { str(it) }.filter { id -> ActivityState.entries.any { stateId(it) == id } })
        } catch (_: Throwable) {}
        val items = byId("topologyCategories")
        for (c in arr(topology.categories)) {
            val label = document.createElement("label").asDynamic(); label.title = c.hint
            val input = document.createElement("input").asDynamic(); input.type = "checkbox"; input.value = c.id
            input.checked = (mask and num(topology.bit(c.id)).toInt()) != 0; input.setAttribute("aria-label", c.label)
            input.addEventListener("change", { setCategory(str(c.id), input.checked == true) })
            val swatch = document.createElement("i").asDynamic(); swatch.style.background = c.color; swatch.setAttribute("aria-hidden", "true")
            val name = document.createElement("span").asDynamic(); name.textContent = c.label
            val count = document.createElement("output").asDynamic(); count.dataset.category = c.id
            label.append(input, swatch, name, count); items.append(label)
        }
        val activityItems = byId("activityCategories")
        byId("activitySelection").addEventListener("click", { inspectActivity(str(h.selected)) })
        for (s in ActivityState.entries) {
            val id = stateId(s)
            val label = document.createElement("label").asDynamic(); label.title = s.hint
            val input = document.createElement("input").asDynamic(); input.type = "checkbox"; input.checked = activityMask.contains(id); input.setAttribute("aria-label", s.label)
            input.addEventListener("change", {
                if (input.checked == true) activityMask.add(id) else activityMask.remove(id)
                try { localStorage.setItem("graal.activity.mask", JSON.stringify(activityMask.toTypedArray())) } catch (_: Throwable) {}
                refreshActivity(); p.redraw()
            })
            val swatch = document.createElement("i").asDynamic(); swatch.style.background = s.color; swatch.setAttribute("aria-hidden", "true")
            val name = document.createElement("span").asDynamic(); name.textContent = s.label
            val count = document.createElement("output").asDynamic(); count.dataset.state = id
            label.append(input, swatch, name, count); activityItems.append(label)
        }
        refreshActivity()
        activityTimer = window.setInterval({ if (h.ready) refreshActivity() }, 1000)
        byId("refreshHeap").addEventListener("click", { GlobalScope.launch { refreshHeap() } })
        updateLegend()
    }

    fun setCategory(id: String, enabled: Boolean) {
        val bit = num(window.asDynamic().GraalTopology.bit(id)).toInt(); mask = if (enabled) mask or bit else mask and bit.inv()
        try { localStorage.setItem("graal.topology.mask", JSON.stringify(mask)) } catch (_: Throwable) {}
        terrain?.setMask(mask); updateLegend(); schedule()
    }

    fun updateLegend() {
        if (legend == null) return
        val topology = window.asDynamic().GraalTopology
        val counts = LinkedHashMap<String, Int>(); for (c in arr(topology.categories)) counts[str(c.id)] = 0
        var visible = 0
        for (row in arr(rows)) {
            val id = str(topology.category(row)); counts[id] = (counts[id] ?: 0) + 1
            if ((mask and num(topology.bit(id)).toInt()) != 0) visible++
        }
        for (el in arr(legend.querySelectorAll("output[data-category]"))) el.textContent = (counts[str(el.dataset.category)] ?: 0).toString()
        for (el in arr(legend.querySelectorAll("#topologyCategories input[type=checkbox]"))) el.checked = (mask and num(topology.bit(el.value)).toInt()) != 0
        byId("topologyCount").textContent = "$visible / " + arr(rows).size
        byId("heapAvailability").textContent = heapStatus
        byId("poolAvailability").textContent = poolStatus
        byId("refreshHeap").asDynamic().disabled = heapBusy
    }

    suspend fun refreshHeap() {
        if (heapBusy) return
        heapBusy = true; heapStatus = "Heap: loading"; poolStatus = "Pools: loading"; updateLegend()
        suspend fun read(path: String): dynamic {
            val controller = AbortController(); val timer = window.setTimeout({ controller.abort() }, 10000)
            try {
                val response = fetchResponse(path, requestInit(signal = controller.signal)); if (!response.ok) throw Error("HTTP " + response.status)
                val data: dynamic = JSON.parse(readText(response))
                if (!truthy(data) || typeOf(data) != "object" || isArray(data)) throw Error("invalid measurement payload")
                return data
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                if (controller.signal.aborted) throw Error("request timed out (10s)"); throw e
            } finally { window.clearTimeout(timer) }
        }
        var heap: dynamic = jsObject(); var vitals: dynamic = jsObject()
        // Independent sources: a failed histogram must not hide available pool data.
        try {
            heap = read("/api/graal/heap")
            heapStatus = if (isArray(heap.rows) && num(heap.rows.length) > 0) "Live histogram: " + Date(num(heap.atMs)).toLocaleTimeString() else "Live histogram unavailable"
            if (!isArray(heap.allocation) || num(heap.allocation.length) == 0.0) heapStatus += "; no allocation samples"
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { heapStatus = "Heap unavailable: " + errorMessage(e) }
        try {
            vitals = read("/api/graal/vitals")
            poolStatus = if (truthy(vitals.gc?.lane?.pools?.length)) "GC pool samples: " + Date(num(vitals.gc.lane.atMs)).toLocaleTimeString() else "GC pool samples unavailable"
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { poolStatus = "Pools unavailable: " + errorMessage(e) }
        runtimeRows = window.asDynamic().GraalTopology.heapRows(heap, vitals); heapLoaded = true; heapBusy = false
        if (terrain == null) terrain = createTerrain()
        updateTerrain()
    }

    suspend fun inspect(id: String) {
        val runtime = terrain?.nodeFor(id)?.detail
        if (truthy(runtime?.runtime) && runtime.category == "allocation") {
            window.asDynamic().AllocationInspector.open(runtime.name, obj { it.kind = runtime.category; it.bytes = runtime.sampledBytes ?: runtime.bytes }); return
        }
        if (truthy(runtime?.runtime)) {
            val category = arr(window.asDynamic().GraalTopology.categories).firstOrNull { it.id == runtime.category }
            h.beginInspection(str(runtime.name), if (category != null) str(category.label) else "Runtime", "Measured snapshot")
            byId("factValue").textContent = jsonString(runtime, true)
            h.rawSheets(true); return
        }
        val signal = h.beginInspection(id, "Object", "Loading")
        val dialog = byId("factInspector"); val body = byId("factValue")
        dialog.querySelector("#factFilePreview")?.remove()
        try {
            val response = fetchResponse("/api/graal/doc?id=" + encodeURIComponent(id), requestInit(signal = signal)); if (!response.ok) throw Error(response.status.toString())
            val doc: dynamic = JSON.parse(readText(response)); if (signal.aborted) return
            body.textContent = jsonString(doc, true)
            GlobalScope.launch { h.loadSheets(listOf(Harness.SheetSource("/api/graal/sheet?id=" + encodeURIComponent(id))), id) }
            val attachment = doc._attachments?.content
            if (truthy(attachment)) {
                val preview = document.createElement("div").asDynamic(); preview.id = "factFilePreview"; body.after(preview)
                if (truthy(doc._graal?.previewBlocked) || truthy(attachment.previewBlocked)) {
                    preview.textContent = "Content preview blocked for credential-bearing document"
                } else if (window.asDynamic().GraalFileViewer != null) {
                    window.asDynamic().GraalFileViewer.render(preview, obj {
                        it.id = id; it.cid = attachment.cid; it.type = attachment.content_type; it.url = "/api/graal/content?id=" + encodeURIComponent(id); it.signal = signal
                    }).unsafeCast<Promise<dynamic>>().await()
                } else try {
                    val content = fetchResponse("/api/graal/content?id=" + encodeURIComponent(id), requestInit(signal = signal))
                    if (content.ok) {
                        val type = content.headers.get("content-type") ?: ""
                        val texty = type.startsWith("text/") || Regex("json|javascript|xml").containsMatchIn(type) || Regex("\\.(kt|kts|py|md|txt|java|rs|sh|js|html|css)$").containsMatchIn(id)
                        val bytes = readBytes(content, if (texty) 131072 else 2048)
                        if (signal.aborted) return
                        preview.textContent = if (texty) js("new TextDecoder()").decode(bytes) else js("Array.from(bytes,function(b){return b.toString(16).padStart(2,'0')})").join(" ")
                    }
                } catch (e: CancellationException) { throw e } catch (e: Throwable) { if (signal.aborted) return; preview.textContent = "Content preview unavailable: " + errorMessage(e) }
            } else if (truthy(doc._graal?.previewBlocked)) {
                val preview = document.createElement("div").asDynamic(); preview.id = "factFilePreview"; body.after(preview)
                preview.textContent = "Content preview blocked for credential-bearing document"
            }
            val dagResponse = fetchResponse("/api/graal/dag?id=" + encodeURIComponent(id), requestInit(signal = signal))
            if (dagResponse.ok) {
                val dag: dynamic = JSON.parse(readText(dagResponse)); if (signal.aborted) return
                edges = arr(dag.edges).take(256).map { e -> val c: dynamic = js("Object.assign({}, e)"); c.from = id; c }.toTypedArray()
                for (edge in arr(edges)) {
                    val button = el("button", "terrain-ref", str(edge.kind) + " → " + str(edge.to)).asDynamic()
                    button.dataset.relation = if (edge.kind == "pointcut-source") "association" else "reference"
                    if (button.dataset.relation == "association") button.title = "Class-name association, not causal support"
                    button.addEventListener("click", { GlobalScope.launch { inspect(str(edge.to)) } }); dialog.append(button)
                }
            }
            schedule()
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { if (!signal.aborted) { body.textContent = "Object read failed: " + errorMessage(e); h.rawSheets(true) } }
    }

    suspend fun inspectCid(cid: String, programKey: String? = null) {
        val signal = h.beginInspection(cid, "Immutable program version", "Loading")
        val body = byId("factValue")
        try {
            val url = "/api/lcnc/content?cid=" + encodeURIComponent(cid) + (if (!programKey.isNullOrEmpty()) "&key=" + encodeURIComponent(programKey) else "")
            val response = fetchResponse(url, requestInit(signal = signal)); if (!response.ok) throw Error(response.status.toString())
            val text = readText(response); if (signal.aborted) return
            body.textContent = text
            h.loadSheets(listOf(Harness.SheetSource("$url&view=sheet")), cid)
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { if (!signal.aborted) { body.textContent = "Version read failed: " + errorMessage(e); h.rawSheets(true) } }
    }

    suspend fun readBytes(response: dynamic, limit: Int = 1048576): dynamic = PatchRead.bytes(response, limit)
    suspend fun readText(response: dynamic, limit: Int = 1048576): String = PatchRead.text(response, limit)

    fun hit(e: dynamic): dynamic {
        val r = viewport.getBoundingClientRect(); val x = (num(e.clientX) - r.left - num(view.x)) / num(view.z); val y = (num(e.clientY) - r.top - num(view.y)) / num(view.z)
        val o = terrain?.hit(x, y); if (truthy(o)) return obj { it.`object` = o }
        return hits.lastOrNull { hh -> x >= num(hh.box.x) && y >= num(hh.box.y) && x <= num(hh.box.x) + num(hh.box.w) && y <= num(hh.box.y) + num(hh.box.h) }
    }

    /** `window.Landscape` and `window.LandscapeActivity`: live accessors over this object. */
    fun facade(): dynamic {
        val o: dynamic = jsObject()
        fun prop(name: String, get: () -> dynamic, set: ((dynamic) -> Unit)? = null) {
            val d: dynamic = jsObject(); d.enumerable = true; d.configurable = true; d.get = get
            if (set != null) d.set = set
            js("Object").defineProperty(o, name, d)
        }
        prop("detailOwner", { detailOwner }); prop("details", { details }); prop("labelBudget", { labelBudget }); prop("labels", { labels })
        prop("canvas", { canvas }); prop("pending", { pending }); prop("terrain", { terrain }); prop("rows", { rows }); prop("dbs", { dbs })
        prop("edges", { edges }, { edges = it }); prop("hits", { hits.toTypedArray() }); prop("storeRows", { storeRows }); prop("runtimeRows", { runtimeRows })
        prop("mask", { mask }); prop("heapBusy", { heapBusy }); prop("heapLoaded", { heapLoaded }); prop("heapStatus", { heapStatus }); prop("poolStatus", { poolStatus })
        prop("activityMask", { activityMask.toTypedArray() }); prop("activityNodes", { activityNodes }); prop("activityPrograms", { activityPrograms })
        prop("activityFacts", { activityFacts }); prop("activityLinks", { activityLinks }); prop("objectBox", { objectBox }, { objectBox = it })
        prop("detailLevel", { detailLevel }); prop("legend", { legend }); prop("drawingGeometry", { drawingGeometry }); prop("closureKey", { closureKey })
        o.refreshActivity = { refreshActivity() }
        o.activityVisible = { n: dynamic -> activityVisible(n) }
        o.inspectActivity = { program: String, nodeId: dynamic -> inspectActivity(program, nodeId) }
        o.positionObjects = { positionObjects() }
        o.schedule = { schedule() }
        o.viewPercent = { viewPercent() }
        o.detailVisible = { detailVisible() }
        o.updateDetailIndicator = { updateDetailIndicator() }
        o.setDetail = { value: dynamic, persist: dynamic -> setDetail(value, persist != false) }
        o.detailFor = { node: dynamic, box: dynamic, absorbed: Boolean -> detailFor(node, box, absorbed) }
        o.labelFor = { box: dynamic, detail: Boolean, absorbed: Boolean, onScreen: Boolean -> labelFor(box, detail, absorbed, onScreen) }
        o.measure = { vr: dynamic -> measure(vr) }
        o.draw = { draw() }
        o.refresh = { GlobalScope.promise { refresh() } }
        o.updateTerrain = { updateTerrain() }
        o.initLegend = { initLegend() }
        o.setCategory = { id: String, enabled: Boolean -> setCategory(id, enabled) }
        o.updateLegend = { updateLegend() }
        o.refreshHeap = { GlobalScope.promise { refreshHeap() } }
        o.inspect = { id: String -> GlobalScope.promise { inspect(id) } }
        o.inspectCid = { cid: String, programKey: String? -> GlobalScope.promise { inspectCid(cid, programKey) } }
        o.readBytes = { r: dynamic, limit: Int? -> GlobalScope.promise { readBytes(r, limit ?: 1048576) } }
        o.readText = { r: dynamic, limit: Int? -> GlobalScope.promise { readText(r, limit ?: 1048576) } }
        o.hit = { e: dynamic -> hit(e) }
        return o
    }

    fun activityFacade(): dynamic {
        val o: dynamic = jsObject()
        o.states = statesJs()
        o.latest = { board: dynamic ->
            val m = JsMap<dynamic, dynamic>()
            for ((k, list) in LandscapeActivity.latest(runPlane(board))) m.set(k, list.map { r -> toJs(r.fields + ("key" to r.key)) }.toTypedArray())
            m
        }
        o.program = { name: String, entry: dynamic, runs: dynamic, draft: Boolean, connected: Boolean, now: dynamic ->
            val latest = LinkedHashMap<String, List<borg.trikeshed.landscape.RunReceipt>>()
            for ((k, list) in (runs.unsafeCast<JsMap<String, dynamic>>()).entryList()) latest[k] = arr(list).map { r ->
                val f = toKotlin(r).unsafeCast<Map<String, Any?>>()
                borg.trikeshed.landscape.RunReceipt(str(r.key), f, (f["stale"] as? Map<*, *>)?.entries?.associate { (a, b) -> a.toString() to b })
            }
            evidenceJs(LandscapeActivity.program(name, programEntry(entry), latest, draft, connected, if (now == undefined) Date.now() else num(now)))
        }
        o.node = { n: dynamic, program: dynamic ->
            evidenceJs(LandscapeActivity.node(str(if (truthy(n._localId)) n._localId else n.id), evidenceOf(program), truthy(n._timer), n._es?.readyState == 1))
        }
        o.fact = { key: String, value: dynamic -> evidenceJs(LandscapeActivity.fact(key, if (truthy(value) && typeOf(value) == "object") mapOf("event" to value.event, "atMs" to value.atMs) else null)) }
        o.references = { board: dynamic -> LandscapeActivity.references(referencePlane(board)).map { r -> obj { it.from = r.from; it.to = r.to; it.kind = r.kind } }.toTypedArray() }
        return o
    }
}
