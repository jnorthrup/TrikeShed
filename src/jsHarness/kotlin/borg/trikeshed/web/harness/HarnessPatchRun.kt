package borg.trikeshed.web.harness

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

private val harnessJs: dynamic get() = window.asDynamic().Harness
private val harnessReady: Boolean get() = harnessJs?.ready == true

// ── execution ─────────────────────────────────────────────────────────────
fun HarnessPatch.inputsOf(id: dynamic): List<dynamic> = arr(graph.wires).filter { it.to[0] == id }

/** ring children run in the daemon's frame; the client walk sequences top-level nodes only */
fun HarnessPatch.topo(): List<dynamic> {
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

suspend fun HarnessPatch.runNode(n: dynamic, cache: dynamic): dynamic {
    val t = contract(n.type); val r = runners[str(n.type)]
    if (t == null || r == null) return undefined
    val inputs = obj()
    for (w in inputsOf(n.id)) {
        if (!wireKindOk(w)) continue // a mismatched cable feeds nothing
        val src = cache[w.from[0]]
        if (src === undefined) return undefined // upstream not run
        inputs[w.to[1]] = src[w.from[1]]
    }
    for (name in arr(t.ins).map { str(it) }) if (!name.endsWith("?") && inputs[name] === undefined) return undefined
    n.el.classList.remove("ok", "err"); n.el.classList.add("running")
    try {
        val out = r.run(n, inputs)
        n.el.classList.remove("running"); n.el.classList.add("ok")
        n._lastOut = out // memo for partial runs
        val render = r.render
        if (render != null) render(n, out)
        else if (!truthy(t.sink) && n.type != "display" && out !== undefined && keysOf(out).isNotEmpty()) setResult(n, out)
        return out
    } catch (e: CancellationException) {
        throw e
    } catch (e: dynamic) {
        n.el.classList.remove("running"); n.el.classList.add("err")
        setResult(n, "ERROR: " + failureText(e))
        return undefined
    }
}

/** On the blackboard execution belongs to the daemon: a closure run is the selected program's run. */
suspend fun HarnessPatch.runAll(fromId: String?) {
    if (harnessJs != null) { if (fromId != null) awaitJs(harnessJs.run()); return }
    status("running…")
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
        // seed required inputs outside the closure from the last full run's memo
        for (n in nodes()) if (!order.contains(n) && n._lastOut !== undefined) cache[n.id] = n._lastOut
    }
    for (n in order) { val out = runNode(n, cache); if (out !== undefined) cache[n.id] = out }
    for (w in arr(wiresSvg.asDynamic().querySelectorAll("path.flow"))) w.classList.remove("flow")
    resolveTopLevelOverlaps() // result trees grow nodes — keep them clear of each other
    redraw()
    status("done " + js("new Date().toLocaleTimeString()").unsafeCast<String>())
}

/** arm sources (timers + SSE). Called by ▶ run AND after every load path. */
fun HarnessPatch.armSources() {
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

fun HarnessPatch.stopSources() {
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

fun HarnessPatch.serialize(): dynamic {
    if (harnessReady) return harnessJs.document()
    val d = obj(); d.view = view; d.nodes = nodes().filter { !truthy(it._parentScope) }.map { nodeDoc(it) }.toTypedArray()
    d.wires = graph.wires; d.seq = graph.seq
    return d
}

private const val STORE_KEY = "trikeshed.panels.v3"

fun HarnessPatch.saveDoc() {
    if (harnessReady) harnessJs.changed()
    // History first: a ?load= view is ephemeral for the SCRATCH, but undo is not autosave.
    recordHistory(JSON.stringify(serialize()))
    refreshRdf()
    if (!autosave) return
    try { localStorage.setItem(STORE_KEY, JSON.stringify(serialize())) } catch (e: dynamic) {}
}

/** a burst of keystrokes in one field collapses into a single step (same node/wire shape inside 600ms) */
fun shapeKey(o: dynamic): String =
    arr(o.nodes).joinToString(",") { str(it.id) + ":" + str(it.type) } + "|" + arr(o.wires).joinToString(",") { str(it.from) + ">" + str(it.to) }

/** undo / redo: every mutation funnels through save(), so history records the TRANSITION there */
fun HarnessPatch.recordHistory(cur: String) {
    if (restoring || lastDoc == null || lastDoc == cur) { lastDoc = cur; histButtons(); return }
    val t = now()
    var sameShape = false
    try { sameShape = num(undoStack.length) > 0 && shapeKey(JSON.parse(str(lastDoc))) == shapeKey(JSON.parse(cur)) } catch (e: dynamic) {}
    if (!(sameShape && t - lastPushMs < 600)) {
        undoStack.push(lastDoc); if (num(undoStack.length) > UNDO_MAX) undoStack.shift()
        lastPushMs = t
    }
    redoStack.length = 0; lastDoc = cur; histButtons()
}

fun HarnessPatch.histButtons() {
    val u = document.querySelector("#undoBtn").asDynamic(); val r = document.querySelector("#redoBtn").asDynamic()
    if (u != null) { u.disabled = num(undoStack.length) == 0.0; u.title = "undo (${undoStack.length}) ⌘Z" }
    if (r != null) { r.disabled = num(redoStack.length) == 0.0; r.title = "redo (${redoStack.length}) ⇧⌘Z" }
}

fun HarnessPatch.applyDoc(json: String) {
    restoring = true
    try { load(JSON.parse(json)); lastDoc = JSON.stringify(serialize()) } finally { restoring = false }
    if (autosave) try { localStorage.setItem(STORE_KEY, str(lastDoc)) } catch (e: dynamic) {}
    histButtons(); launchJs { runAll(null) }
}

fun HarnessPatch.undo() {
    if (num(undoStack.length) == 0.0) { status("nothing to undo"); return }
    redoStack.push(lastDoc); applyDoc(str(undoStack.pop()))
    val n = num(undoStack.length).toInt()
    status("undo · $n step" + (if (n == 1) "" else "s") + " back")
}

fun HarnessPatch.redo() {
    if (num(redoStack.length) == 0.0) { status("nothing to redo"); return }
    undoStack.push(lastDoc); applyDoc(str(redoStack.pop()))
    status("redo · ${redoStack.length} ahead")
}

fun HarnessPatch.load(data: dynamic) {
    if (harnessReady) { harnessJs.replaceSelected(data); return }
    for (n in nodes()) { if (truthy(n._timer)) window.clearInterval(n._timer); if (truthy(n._es)) { n._es.close(); n._es = null }; n.el.remove() }
    val g = obj(); g.nodes = jsArray(); g.wires = jsArray(); g.seq = if (truthy(data.seq)) data.seq else 1
    graph = g
    if (truthy(data.view)) view = data.view
    // three passes: CLONE the tree (children stay the SAME objects flattened into G.nodes), BUILD, MOUNT
    fun cloneNode(sn: dynamic): dynamic {
        val c = spread(sn); c.params = spread(sn.params); c.children = arr(sn.children).map { cloneNode(it) }.toTypedArray(); return c
    }
    val built = ArrayList<dynamic>()
    for (sn in arr(data.nodes)) {
        if (contract(sn.type) == null) continue
        val n = cloneNode(sn)
        buildNode(n); built.add(n)
        fun flatten(nd: dynamic) {
            for (c in arr(nd.children)) {
                if (contract(c.type) == null) continue
                buildNode(c); built.add(c)
                flatten(c)
            }
        }
        flatten(n)
    }
    graph.nodes = built.toTypedArray()
    mountChildren()
    graph.wires = arr(data.wires).filter { w -> node(w.from[0]) != null && node(w.to[0]) != null }.toTypedArray()
    val bad = arr(graph.wires).count { !wireKindOk(it) }
    if (bad > 0) status("$bad kind-unsafe cable" + (if (bad > 1) "s" else "") + " (red, dashed) — they feed nothing until reconnected")
    camera.applyView(); redraw(); save()
}

/** Server-shape (LcncProgramConfix) → client-shape; children map RECURSIVELY; flat fromNode/fromPort wires accepted. */
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

fun HarnessPatch.toConfix(): dynamic {
    val d = serialize(); val r = obj()
    r.name = if (truthy(panelName().value)) panelName().value else "canvas"
    r.nodes = d.nodes; r.wires = d.wires; r.controls = d.controls; r.view = objOf { it.x = view.x; it.y = view.y; it.z = view.z }
    return r
}

// ── lanes: HYDRATED from /api/lcnc/concentric (ConcentricSurface.LANE_ASSEMBLAGE) ──
suspend fun HarnessPatch.syncLanes() {
    try {
        val r = fetchJson("/api/lcnc/concentric")
        lanes = arr(r.lanes).map { l ->
            val o = obj(); o.id = l.id; o.match = if (truthy(l.match)) l.match else jsArray(); o.element = l.element; o.seed = l.seed; o.emit = l.emit; o
        }.toTypedArray()
        if (num(lanes.length) == 0.0) status("lane assemblage empty — /api/lcnc/concentric served no lanes")
    } catch (e: CancellationException) { throw e } catch (e: dynamic) { status("lanes fetch failed: " + str(e)) }
}

/** The ONE author the canvas reads is lcnc/vocabulary on the blackboard. */
suspend fun boardVocabulary(): dynamic {
    val snap = fetchJson("/blackboard/board")
    val payload = (if (truthy(snap.board)) snap.board else obj())["lcnc/vocabulary"]
    if (!truthy(payload)) throw Error("lcnc/vocabulary missing")
    return payload
}

/** contract hydration: the vocabulary is board-owned; an SSE payload applies directly */
suspend fun HarnessPatch.syncContracts(given: dynamic) {
    try {
        val payload = if (truthy(given)) given else boardVocabulary()
        val list = arr(payload.contracts)
        kindAcceptance = if (truthy(payload.kindAcceptance)) payload.kindAcceptance else obj()
        acceptance = keysOf(kindAcceptance).associateWith { k -> arr(kindAcceptance[k]).map { str(it) } }
        kindRefinements = if (truthy(payload.kindRefinements)) payload.kindRefinements else jsArray()
        kindHierarchy = if (truthy(payload.kindHierarchy)) payload.kindHierarchy else jsArray()
        val m = obj()
        for (c in list) {
            // Normalize wire shape → renderer shape; an EMPTY opts array must not become a select
            c.ins = if (truthy(c.inputs)) c.inputs else jsArray(); c.outs = if (truthy(c.outputs)) c.outputs else jsArray()
            val ps = obj()
            val raw = if (truthy(c.params)) c.params else obj()
            for (k in keysOf(raw)) {
                val d = if (truthy(raw[k])) raw[k] else obj()
                val q = obj()
                q.v = d.v ?: ""
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
        // Fallback parity: every hydrated type with no explicit browser runner dispatches to the daemon
        for (t in keysOf(m)) if (!runners.containsKey(t)) runners[t] = serverRun(t)
        // result.confirm is a SINK whose server runner answers with a ready HTML card (x)
        val confirm = runners["result.confirm"]
        if (confirm != null && confirm.render == null) confirm.render = { n, out ->
            val html = if (truthy(out) && typeOf(out.x) == "string") out.x else ""
            var el = n.el.querySelector(".result")
            if (el == null) { el = document.createElement("div").asDynamic(); el.className = "result"; n.el.appendChild(el); on(el, "pointerdown", { e -> e.stopPropagation() }) }
            el.innerHTML = html // server-authored; content/error are escaped there
        }
        status("${keysOf(m).size} contracts · ${keysOf(kindAcceptance).size} semantic kinds · ${kindHierarchy.length} rdfs:subClassOf edges")
    } catch (e: CancellationException) { throw e } catch (e: dynamic) {
        status("board vocabulary failed: " + str(e))
    }
}
