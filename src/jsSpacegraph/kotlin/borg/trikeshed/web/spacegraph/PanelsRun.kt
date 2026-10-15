package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import borg.trikeshed.web.patch.PatchKinds
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

// ── execution ─────────────────────────────────────────────────────────────
fun Panels.inputsOf(id: dynamic): List<dynamic> = arr(graph.wires).filter { it.to[0] == id }

/** ring children run in the daemon's frame; the client walk sequences top-level nodes only */
fun Panels.topo(): List<dynamic> {
    val order = ArrayList<String>(); val mark = HashMap<String, String>()
    fun visit(id: String) {
        if (mark[id] == "t") return; if (mark[id] == "p") throw PatchRefusal("cycle at $id")
        val nd = node(id)
        if (nd == null || truthy(nd._parentScope)) { mark[id] = "t"; return }
        mark[id] = "p"
        for (w in inputsOf(id)) visit(str(w.from[0]))
        // a ring depends on every outside source feeding its interior
        if (truthy(nd.children) && num(nd.children.length) > 0) {
            val dIds = HashSet<String>()
            fun wk(m: dynamic) { for (c in arr(m.children)) { dIds.add(str(c.id)); wk(c) } }
            wk(nd)
            for (w in arr(graph.wires)) if (str(w.to[0]) in dIds && str(w.from[0]) !in dIds) visit(str(w.from[0]))
        }
        mark[id] = "t"; order.add(id)
    }
    for (n in nodes()) if (!truthy(n._parentScope)) visit(str(n.id))
    return order.map { node(it) }
}

suspend fun Panels.runNode(n: dynamic, cache: dynamic): dynamic {
    val attempt = objOf {
        it.node = JSON.stringify(executionNode(n)); it.at = js("new Date().toISOString()")
        it.state = "waiting for inputs"; it.inputs = "{}"
    }
    n._lastAttempt = attempt
    n._lastOut = undefined // A failed or skipped rerun must not seed a later partial run.
    val t = contract(n.type); val r = runners[str(n.type)]
    if (t == null || r == null) { attempt.state = "unavailable runner"; return undefined }
    val inputs = obj()
    for (w in inputsOf(n.id)) {
        if (!wireKindOk(w)) continue // a mismatched cable feeds nothing
        val src = cache[w.from[0]]
        if (src === undefined) { attempt.state = "waiting for upstream " + str(w.from[0]); return undefined }
        inputs[w.to[1]] = src[w.from[1]]
    }
    attempt.inputs = executionPreview(inputs)
    for (name in arr(t.ins).map { str(it) }) if (!name.endsWith("?") && inputs[name] === undefined) {
        attempt.state = "missing input $name"; return undefined
    }
    attempt.state = "running"
    n.el.classList.remove("ok", "err"); n.el.classList.add("running")
    try {
        val out = r.run(n, inputs)
        attempt.state = "returned"; attempt.outputs = executionPreview(out)
        n.el.classList.remove("running"); n.el.classList.add("ok")
        n._lastOut = out // memo for partial runs
        val render = r.render
        if (render != null) render(n, out)
        else if (!truthy(t.sink) && n.type != "display" && out !== undefined && keysOf(out).isNotEmpty()) setResult(n, out)
        return out
    } catch (e: CancellationException) {
        attempt.state = "cancelled"
        n._lastOut = undefined
        n.el.classList.remove("running")
        throw e
    } catch (e: dynamic) {
        attempt.state = "failed"; attempt.error = failureText(e)
        n._lastOut = undefined
        n.el.classList.remove("running"); n.el.classList.add("err")
        setResult(n, "ERROR: " + failureText(e))
        return undefined
    }
}

/** Bounded display only; never used to feed another node or persisted as a receipt. */
fun executionNode(n: dynamic): dynamic = objOf {
    it.id = n.id; it.type = n.type; it.params = n.params; it.subprogram = n.subprogram
    it.children = arr(n.children).map { child -> executionNode(child) }.toTypedArray()
}

fun executionPreview(value: dynamic): String {
    val text = try { if (value === undefined) "undefined" else str(JSON.stringify(value, null, 2)) }
        catch (e: dynamic) { "Value cannot be rendered as JSON" }
    return if (text.length <= 32_768) text else text.take(32_768) + "\n… preview truncated at 32768 characters"
}

/** The reporting boundary's omission envelope, not a domain field such as agent.run's Boolean. */
fun executionOmitted(value: dynamic): Boolean = value != null && typeOf(value.truncated) == "string" &&
    isArray(value.keys) && keysOf(value).size == 2

/** One authored program, one daemon admission. Rendering never invokes another runner. */
suspend fun Panels.runProgram(debug: Boolean = false) {
    if (truthy(graph.controls?.inspectionOnly)) { status("Inspection-only construction; execution is disabled"); return }
    if (programRunning) { status("A program run is already awaiting its receipt"); return }
    stopSources()
    if (liveRuns > 0) { status("Sources stopped; wait for the current live sweep to finish, then choose Run"); return }
    val document = JSON.stringify(toConfix())
    val request = JSON.stringify(objOf {
        it.name = str(panelName().value).ifBlank { "canvas" }
        it.document = JSON.parse(document); it.inputs = obj()
        if (debug) it.debugToken = debugger.start()
    })
    for (n in nodes()) {
        n._lastOut = undefined; n.el.classList.remove("ok", "err")
        n._sheets = null; n._sheetRequest = null; n._sheetError = null; n._cur = null; n._orch = null
        n._groups = null; n._cols = null; n._lastMove = null; n._heap = null; n._standings = null
        for (el in arr(n.el.querySelectorAll(".result,.csheet,.kboard,.kboard-status,canvas.heapmap,.heapstat"))) el.remove()
        n.el.querySelector(".gaugeval")?.textContent = "—"
    }
    programRunning = true
    byId("runBtn").disabled = true; byId("liveBtn")?.disabled = true
    byId("debugBtn")?.disabled = true
    status("Running the authored program once · sources stopped · awaiting durable receipt…")
    try {
        val response = coroutineScope {
            val observer = if (debug) launch { debugger.observe() } else null
            try { fetchJs("/api/lcnc/run", jsonInit("POST", request)) }
            finally { observer?.cancelAndJoin() }
        }
        val result: dynamic = JSON.parse(PatchRead.text(response))
        // Failures after admission have receipts too. Never retry through individual node runners.
        if (result?.receiptCid != null && result.runId != null && result.programCid != null) {
            lastProgramRun = result
            var displayError = ""
            try { if (JSON.stringify(toConfix()) == document) programOutputs(result, document) }
            catch (e: dynamic) { displayError = " · canvas output display failed: " + failureText(e) }
            facets.tab = "execution"
            if (facets.drawer == null) facets.toggle() else facets.show("execution")
            status("Program " + str(result.status) + " · " + str(result.runId) + " · receipt in Evidence; live sources remain stopped" + displayError)
        } else {
            status("Program request returned HTTP " + str(response.status) + " without a receipt: " +
                str(result?.error ?: result?.detail ?: "unrecognized response") + ". No automatic retry.")
        }
    } catch (e: CancellationException) {
        status("Program response interrupted; daemon outcome is unknown. Inspect run records before retrying.")
        throw e
    } catch (e: dynamic) {
        status("Program response unavailable; daemon outcome is unknown: " + failureText(e) +
            ". Inspect run records before retrying; no automatic retry.")
    } finally {
        programRunning = false
        byId("runBtn").disabled = truthy(graph.controls?.inspectionOnly)
        byId("liveBtn")?.disabled = truthy(graph.controls?.inspectionOnly)
        byId("debugBtn")?.disabled = truthy(graph.controls?.inspectionOnly)
        if (debug) debugger.finish()
    }
}

/** Complete root-frame outputs may seed later gestures; omission markers and nested traces may not. */
suspend fun Panels.programOutputs(run: dynamic, document: String) {
    val outputs = run.outputs ?: return
    val canvas = graph
    val projected = nodes().filter { !truthy(it._parentScope) }
    fun current(): Boolean = graph === canvas && JSON.stringify(toConfix()) == document
    // Seed complete outputs before asynchronous sheet reads; renderers never dispatch execution.
    for (n in projected) {
        val out = outputs[n.id]
        if (out == null || typeOf(out) != "object" || isArray(out)) continue
        if (executionOmitted(out)) {
            setResult(n, "Program output omitted: " + str(out.truncated) + ". Inspect the receipt; this value cannot feed a live input.")
            continue
        }
        n._lastOut = out
    }
    val failures = ArrayList<String>()
    try { for (n in projected) {
        if (!current()) break
        if (truthy(n._parentScope) || n._lastOut === undefined) continue
        val out = n._lastOut
        n.el.classList.remove("err", "running"); n.el.classList.add("ok")
        try {
            when (str(n.type)) {
                "sheet.concentric" -> loadConcentric(n, out.rendered, out.byStatus, out.byPriority, out.orchestration, ::current)
                "dom.board" -> {
                    // Gestures mutate their page-owned view, never the retained output/memo.
                    n._groups = JSON.parse<dynamic>(JSON.stringify(out.rendered ?: obj()))
                    n._cols = out.columns
                    renderGroupedBoard(n)
                }
                "display" -> setResult(n, out.x)
                "gauge" -> {
                    val value = out.x
                    n.el.querySelector(".gaugeval").textContent = if (typeOf(value) == "number" && num(value) > 9999)
                        fixed(num(value) / 1e6, 1) + "M" else str(value)
                    n.el.querySelector(".gaugelabel").textContent = p(n, "label")
                }
                "graal.heap" -> { n._heap = out.heap; renderHeap(n) }
                "mux.standings" -> { n._standings = out.standings; renderStandings(n) }
                "slider" -> {
                    n._sval = out.value
                    renderSliderNode(n)
                    n._srange.value = out.value; n._slab.textContent = str(out.value)
                }
                else -> {
                    val render = runners[str(n.type)]?.render
                    if (render != null) render(n, out)
                    else if (keysOf(out).isNotEmpty()) setResult(n, out)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: dynamic) {
            if (current() && truthy(n.el.isConnected)) {
                val failure = str(n.id) + ": " + failureText(e)
                failures.add(failure)
                setResult(n, "Program output retained; presentation failed: $failure")
            }
        }
    } } finally {
        if (!current()) for (n in projected) {
            if (n._lastOut === outputs[n.id]) n._lastOut = undefined
        }
    }
    // Rich projections grow their nodes just as live results do. Keep controls reachable.
    if (current()) resolveTopLevelOverlaps()
    redraw()
    if (failures.isNotEmpty()) throw PatchRefusal(failures.joinToString("; "))
}

suspend fun Panels.runAll(fromId: String?, automatic: Boolean = false) {
    if (truthy(graph.controls?.inspectionOnly)) { status("Inspection-only construction; execution is disabled"); return }
    if (automatic && truthy(graph.controls?.humanOversight)) { status("Loaded for review — choose Run to execute"); return }
    if (programRunning) { status("Live trigger not admitted while awaiting the program receipt; it will not be replayed"); return }
    liveRuns++
    try {
        status("running live…")
        for (w in arr(wiresSvg.asDynamic().querySelectorAll("path.live"))) w.classList.add("flow")
        var order: List<dynamic>
        try { order = topo() } catch (e: CancellationException) { throw e } catch (e: dynamic) { status(failureText(e)); return }
        if (fromId != null) { // restrict to downstream closure of fromId
            val keep = HashSet<String>(); keep.add(fromId); var grew = true
            while (grew) { grew = false; for (w in arr(graph.wires)) if (str(w.from[0]) in keep && str(w.to[0]) !in keep) { keep.add(str(w.to[0])); grew = true } }
            order = order.filter { str(it.id) in keep }
        }
        val cache = obj()
        if (fromId != null) {
            // seed required inputs outside the closure from the last live sweep's memo
            for (n in nodes()) if (!order.contains(n) && n._lastOut !== undefined) cache[n.id] = n._lastOut
        }
        for (n in order) { val out = runNode(n, cache); if (out !== undefined) cache[n.id] = out }
        resolveTopLevelOverlaps() // result trees grow nodes — keep them clear of each other
        redraw()
        val failures = order.count { truthy(it.el.classList.contains("err")) }
        status(if (failures > 0) "Live sweep has $failures failed node(s) — inspect the marked nodes"
            else "Live sweep done " + js("new Date().toLocaleTimeString()").unsafeCast<String>())
    } finally {
        liveRuns--
        for (w in arr(wiresSvg.asDynamic().querySelectorAll("path.flow"))) w.classList.remove("flow")
    }
}

/** Loading under human oversight cannot arm timers/SSE; explicit Live can. */
fun Panels.armSources(automatic: Boolean = false) {
    if (programRunning || truthy(graph.controls?.inspectionOnly) || automatic && truthy(graph.controls?.humanOversight)) return
    for (n in nodes()) {
        if (truthy(n._parentScope)) continue // the daemon owns the frame
        if (truthy(contract(n.type)?.source) && n.type == "timer" && !truthy(n._timer)) {
            val sec = num(p(n, "seconds")); val s = maxOf(1.0, if (sec.isNaN() || sec == 0.0) 5.0 else sec)
            n._timer = window.setInterval({ launchJs { runAll(str(n.id)) } }, (s * 1000).toInt())
        }
        if (n.type == "graal.events" && !truthy(n._es)) {
            // pointcut-driven dataflow: every matching graal event re-fires this node's downstream
            n._es = jsNew(js("EventSource"), "/api/graal/events")
            n._es.onmessage = { ev: dynamic ->
                val f = p(n, "filter")
                if (!(truthy(f) && !str(ev.data ?: "").contains(str(f)))) {
                    n._lastEvent = try { JSON.parse(str(ev.data)) } catch (x: dynamic) { ev.data }
                    launchJs { runAll(str(n.id)) }
                }
            }
        }
        if (n.type == "vm.events" && !truthy(n._es)) {
            // sub-VM lifecycle dataflow: spawn/eval/revoke events re-fire this node's downstream
            n._es = jsNew(js("EventSource"), "/api/vm/events")
            n._es.onmessage = { ev: dynamic ->
                val d: dynamic = try { JSON.parse(str(ev.data)) } catch (x: dynamic) { null }
                if (d != null && !(truthy(p(n, "vmId")) && d.vmId != p(n, "vmId")) && !(truthy(p(n, "kind")) && d.kind != p(n, "kind"))) {
                    n._lastEvent = d
                    launchJs { runAll(str(n.id)) }
                }
            }
        }
    }
}

fun Panels.stopSources() {
    for (n in nodes()) {
        if (truthy(n._timer)) { window.clearInterval(n._timer); n._timer = null }
        if (truthy(n._es)) { n._es.close(); n._es = null }
    }
    status("sources stopped")
}

// ── persistence ───────────────────────────────────────────────────────────
/** the tree survives the round trip: a ring emits its children recursively (one identity, no shadow copy) */
fun nodeDoc(n: dynamic): dynamic {
    val d = obj(); d.id = n.id; d.type = n.type; d.x = n.x; d.y = n.y; d.params = n.params; d.collapsed = truthy(n.collapsed)
    d.subprogram = if (truthy(n.subprogram)) n.subprogram else undefined
    d.children = if (truthy(n.children) && num(n.children.length) > 0) arr(n.children).map { nodeDoc(it) }.toTypedArray() else undefined
    return d
}

fun Panels.serialize(): dynamic {
    val d = obj(); d.view = view; d.nodes = nodes().filter { !truthy(it._parentScope) }.map { nodeDoc(it) }.toTypedArray()
    d.wires = graph.wires; d.seq = graph.seq; d.controls = graph.controls
    return d
}

private const val STORE_KEY = "trikeshed.panels.v3"

fun Panels.saveDoc() {
    documentRevision++
    facets.changed()
    // History first: a ?load= view is ephemeral for the SCRATCH, but undo is not autosave.
    history.record(JSON.stringify(serialize()))
    refreshRdf()
    window.dispatchEvent(jsNew(js("CustomEvent"), "lcnc:shadow-update"))
    if (!autosave) return
    try { localStorage.setItem(STORE_KEY, JSON.stringify(serialize())) } catch (e: dynamic) {}
}

/**
 * undo / redo: every mutation funnels through save(), so history records the TRANSITION there. A burst
 * of keystrokes in one field collapses into a single step (same node/wire shape inside 600ms).
 */
class PanelsHistory(private val p: Panels) {
    val undo = ArrayList<String>(); val redo = ArrayList<String>()
    private val max = 200
    var restoring = false
    var lastDoc: String? = null
    private var lastPushMs = 0.0

    private fun shapeKey(o: dynamic): String =
        arr(o.nodes).joinToString(",") { str(it.id) + ":" + str(it.type) } + "|" + arr(o.wires).joinToString(",") { str(it.from) + ">" + str(it.to) }

    fun record(cur: String) {
        if (restoring || lastDoc == null || lastDoc == cur) { lastDoc = cur; buttons(); return }
        val t = now()
        var sameShape = false
        try { sameShape = undo.isNotEmpty() && shapeKey(JSON.parse(lastDoc!!)) == shapeKey(JSON.parse(cur)) } catch (e: dynamic) {}
        if (!(sameShape && t - lastPushMs < 600)) {
            undo.add(lastDoc!!); if (undo.size > max) undo.removeAt(0)
            lastPushMs = t
        }
        redo.clear(); lastDoc = cur; buttons()
    }

    fun buttons() {
        val u = byId("undoBtn"); val r = byId("redoBtn")
        if (u != null) { u.disabled = undo.isEmpty(); u.title = "undo (${undo.size}) ⌘Z" }
        if (r != null) { r.disabled = redo.isEmpty(); r.title = "redo (${redo.size}) ⇧⌘Z" }
    }

    private fun applyDoc(json: String): Boolean {
        restoring = true
        try {
            if (!p.load(JSON.parse(json))) return false
            lastDoc = JSON.stringify(p.serialize())
        } finally { restoring = false }
        if (p.autosave) try { localStorage.setItem(STORE_KEY, lastDoc!!) } catch (e: dynamic) {}
        launchJs { p.runAll(null, automatic = true) }
        return true
    }

    fun undo() {
        if (undo.isEmpty()) { p.status("nothing to undo"); return }
        val previous = lastDoc!!
        if (!applyDoc(undo.last())) return
        undo.removeAt(undo.size - 1); redo.add(previous); buttons()
        p.status("undo · ${undo.size} step" + (if (undo.size == 1) "" else "s") + " back")
    }

    fun redo() {
        if (redo.isEmpty()) { p.status("nothing to redo"); return }
        val previous = lastDoc!!
        if (!applyDoc(redo.last())) return
        redo.removeAt(redo.size - 1); undo.add(previous); buttons()
        p.status("redo · ${redo.size} ahead")
    }
}


fun Panels.load(data: dynamic): Boolean {
    loadFailure(data)?.let { return refuseLoad(it) }
    byId("panelLoadRefusal")?.remove()
    shakeOwner.parentRevision++
    for (n in nodes()) { if (truthy(n._timer)) window.clearInterval(n._timer); if (truthy(n._es)) { n._es.close(); n._es = null }; n.el.remove() }
    val g = obj(); g.nodes = jsArray(); g.wires = jsArray(); g.seq = if (truthy(data.seq)) data.seq else 1
    g.controls = if (truthy(data.controls)) spread(data.controls) else undefined
    graph = g
    byId("runBtn").disabled = programRunning || truthy(graph.controls?.inspectionOnly)
    byId("liveBtn")?.disabled = programRunning || truthy(graph.controls?.inspectionOnly)
    byId("debugBtn")?.disabled = programRunning || truthy(graph.controls?.inspectionOnly)
    val savedCamera = data.view
    // three passes: CLONE the tree (children stay the SAME objects flattened into G.nodes), BUILD, MOUNT
    fun cloneNode(sn: dynamic): dynamic {
        val c = spread(sn); c.params = spread(sn.params); c.children = arr(sn.children).map { cloneNode(it) }.toTypedArray(); return c
    }
    val built = ArrayList<dynamic>()
    for (sn in arr(data.nodes)) {
        val n = cloneNode(sn)
        buildNode(n); built.add(n)
        fun flatten(nd: dynamic) {
            for (c in arr(nd.children)) {
                buildNode(c); built.add(c)
                flatten(c)
            }
        }
        flatten(n)
    }
    graph.nodes = built.toTypedArray()
    mountChildren()
    graph.wires = arr(data.wires).map { w ->
        val copy = spread(w); copy.from = arr(w.from).copyOf(); copy.to = arr(w.to).copyOf(); copy
    }.toTypedArray()
    val bad = arr(graph.wires).count { !wireKindOk(it) }
    if (bad > 0) status("$bad kind-unsafe cable" + (if (bad > 1) "s" else "") + " (red, dashed) — they feed nothing until reconnected")
    if (savedCamera != null) camera.restoreCameraView(savedCamera)
    applyView(); redraw(); save()
    return true
}

/** Server-shape (LcncProgramConfix) → client-shape; children map RECURSIVELY. */
fun fromConfix(doc: dynamic): dynamic {
    fun nodeOf(n: dynamic): dynamic {
        val d = obj(); d.id = n.id; d.type = n.type; d.x = if (truthy(n.x)) n.x else 0; d.y = if (truthy(n.y)) n.y else 0
        d.params = if (truthy(n.params)) n.params else obj(); d.collapsed = truthy(n.collapsed)
        d.subprogram = if (truthy(n.subprogram)) n.subprogram else undefined
        d.children = if (truthy(n.children) && num(n.children.length) > 0) arr(n.children).map { nodeOf(it) }.toTypedArray() else undefined
        return d
    }
    val r = obj(); r.controls = doc.controls
    r.view = if (truthy(doc.view)) objOf {
        it.x = if (truthy(doc.view.x)) doc.view.x else 0; it.y = if (truthy(doc.view.y)) doc.view.y else 0
        it.z = if (truthy(doc.view.z)) doc.view.z else if (truthy(doc.view.zoom)) doc.view.zoom else 1
    } else undefined
    r.seq = if (truthy(doc.seq)) doc.seq else 1
    r.nodes = arr(doc.nodes).map { nodeOf(it) }.toTypedArray()
    r.wires = arr(doc.wires).map { w ->
        val x = obj()
        x.from = if (truthy(w.from)) w.from else arrayOf(w.fromNode, w.fromPort)
        x.to = if (truthy(w.to)) w.to else arrayOf(w.toNode, w.toPort)
        x
    }.toTypedArray()
    return r
}

fun Panels.toConfix(): dynamic {
    val d = serialize(); val r = obj()
    r.name = if (truthy(panelName().value)) panelName().value else "canvas"
    r.nodes = d.nodes; r.wires = d.wires; r.controls = d.controls; r.view = objOf { it.x = view.x; it.y = view.y; it.z = view.z }
    return r
}

// ── the blackboard entry: cables typed, violations the daemon recorded ──
suspend fun Panels.syncBoard(name: String?) {
    board = Panels.Board(if (name.isNullOrEmpty()) null else name)
    if (name.isNullOrEmpty()) { redraw(); return }
    try {
        val resp = fetchJs("/api/panels/" + encodeURIComponent(name) + "?entry=1")
        if (!truthy(resp.ok)) { redraw(); return }
        val e = awaitJs(resp.json())
        for (c in arr(e.cables)) board.cables[PatchKinds.cableKey(str(c.from[0]), str(c.from[1]), str(c.to[0]), str(c.to[1]))] = c.type
        for (v in arr(e.violations)) board.violations[PatchKinds.cableKey(str(v.fromNode), str(v.fromPort), str(v.toNode), str(v.toPort))] = str(if (truthy(v.detail)) v.detail else v.rule)
        val typed = board.cables.values.count { it != null }
        val line = "from the board: $typed cable" + (if (typed == 1) "" else "s") + " typed" + (if (board.violations.isNotEmpty()) " · ${board.violations.size} refused by the daemon" else "")
        val st = statusText(); status((if (st.isNotEmpty()) "$st · " else "") + line)
    } catch (x: CancellationException) { throw x } catch (x: dynamic) {}
    redraw()
}

/** ── contract hydration: the vocabulary is server-owned ── */
suspend fun Panels.syncContracts(announce: Boolean = true): Boolean {
    try {
        val response = fetchJs("/api/lcnc/contracts")
        require(truthy(response.ok)) { "HTTP ${str(response.status)}" }
        val payload = awaitJs(response.json())
        require(payload != null && isArray(payload.contracts) && num(payload.contracts.length) > 0) { "No contract vocabulary returned" }
        val list = arr(payload.contracts)
        val acc = if (truthy(payload.kindAcceptance)) payload.kindAcceptance else obj()
        val acceptance = keysOf(acc).associateWith { k -> arr(acc[k]).map { str(it) } }
        val refinements = arr(payload.kindRefinements)
        val hierarchy = arr(payload.kindHierarchy)
        val m = obj()
        for (c in list) {
            require(c != null && typeOf(c.type) == "string" && str(c.type).isNotBlank() && m[c.type] == null) { "Invalid or duplicate node contract" }
            // an EMPTY opts array must not become a select
            c.ins = if (truthy(c.inputs)) c.inputs else jsArray(); c.outs = if (truthy(c.outputs)) c.outputs else jsArray()
            val ps = obj()
            val raw = if (truthy(c.params)) c.params else obj()
            for (k in keysOf(raw)) {
                val d = if (truthy(raw[k])) raw[k] else obj()
                val q = obj()
                q.v = d.v ?: ""
                q.optional = truthy(d.optional)
                q.opts = if (truthy(d.opts) && num(d.opts.length) > 0) d.opts else undefined
                q.optsFrom = if (truthy(d.optsFrom)) d.optsFrom else undefined
                q.ta = if (truthy(d.ta)) d.ta else undefined; q.ph = if (truthy(d.ph)) d.ph else undefined
                q.cols = if (truthy(d.cols) && num(d.cols.length) > 0) d.cols else undefined
                ps[k] = q
            }
            c.params = ps
            m[c.type] = c
        }
        contracts = m
        kindAcceptance = acceptance; kindRefinements = refinements; kindHierarchy = hierarchy
        // Fallback parity: every hydrated type with no explicit browser runner dispatches to the daemon
        for (t in keysOf(m)) if (!runners.containsKey(t)) runners[t] = serverRun(t)
        for (t in arrayOf("document.curate", "document.tasks", "document.sheet")) runners[t]?.render = { n, out ->
            renderDocumentReview(n, out)
        }
        runners["nars.reteFire"]?.render = { n, out -> renderSimulation(n, out) }
        // result.confirm is a SINK whose server runner answers with a ready HTML card (x)
        val confirm = runners["result.confirm"]
        if (confirm != null && confirm.render == null) confirm.render = { n, out ->
            val html = if (truthy(out) && typeOf(out.x) == "string") out.x else ""
            var el = n.el.querySelector(".result")
            if (el == null) { el = document.createElement("div").asDynamic(); el.className = "result"; n.el.appendChild(el); on(el, "pointerdown", { e -> e.stopPropagation() }) }
            el.innerHTML = html // server-authored; content/error are escaped there
        }
        // Background vocabulary hydration must not replace a task refusal or run outcome.
        if (announce) status("${keysOf(m).size} contracts · ${kindAcceptance.size} semantic kinds · ${kindHierarchy.size} rdfs:subClassOf edges")
        return true
    } catch (e: CancellationException) { throw e } catch (e: dynamic) {
        if (announce) status("contracts fetch failed: " + failureText(e))
        return false
    }
}

// ── the treeshake / layout owners of this canvas ──
class PanelsShakeOwner(private val p: Panels) : PatchShakeOwner {
    override var shaking = false
    override var parentRevision = 0
    override val selected: String get() = str(p.panelName().value ?: "").trim().ifEmpty { p.board.name ?: "canvas" }
    override fun parentTarget(): dynamic = objOf { it.handle = objOf { h -> h.nodeId = null } }
    override fun document(): dynamic {
        val d = p.serialize(); val r = obj(); r.name = selected; r.nodes = d.nodes; r.wires = d.wires; r.controls = d.controls; return r
    }
    override fun message(text: String) = p.status(text)
}

class PanelsLayoutOwner(private val p: Panels) : PatchLayoutOwner {
    var parentId: dynamic = null
    override val selected: String? get() = p.shakeOwner.selected
    override val parentRevision: Int get() = p.shakeOwner.parentRevision
    override fun parentTarget(): dynamic {
        val sel = if (truthy(document.body!!.classList.contains("sg-spatial"))) p.workspace?.selected else parentId
        var parent = p.node(sel)
        if (parent != null && !truthy(parent._childHost)) parent = parent._parentScope
        if (!truthy(parent)) parent = null
        val t = obj()
        t.handle = objOf { it.program = selected; it.nodeId = parent?.id }
        t.node = parent
        t.origin = objOf { it.x = 0; it.y = 0 }
        t.nodes = p.nodes().filter { (if (truthy(it._parentScope)) it._parentScope else null) === parent }.toTypedArray()
        return t
    }
    override fun document(): dynamic = p.shakeOwner.document()
    override suspend fun layoutHints(document: dynamic, parentId: dynamic): Array<dynamic> = p.layout.hints(document, parentId)
    override fun layoutNodeId(id: dynamic): String = str(id)
    override fun resizeParent(parent: dynamic) { for (child in arr(parent.children)) p.growRingWorldFor(child) }
}
