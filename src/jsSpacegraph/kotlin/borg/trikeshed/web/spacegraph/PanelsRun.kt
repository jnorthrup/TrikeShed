package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import borg.trikeshed.web.patch.PatchKinds
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

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

suspend fun Panels.runAll(fromId: String?) {
    if (truthy(graph.controls?.inspectionOnly)) { status("Inspection-only construction; execution is disabled"); return }
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
fun Panels.armSources() {
    if (truthy(graph.controls?.inspectionOnly)) return
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

    private fun applyDoc(json: String) {
        restoring = true
        try { p.load(JSON.parse(json)); lastDoc = JSON.stringify(p.serialize()) } finally { restoring = false }
        if (p.autosave) try { localStorage.setItem(STORE_KEY, lastDoc!!) } catch (e: dynamic) {}
        buttons(); launchJs { p.runAll(null) }
    }

    fun undo() {
        if (undo.isEmpty()) { p.status("nothing to undo"); return }
        redo.add(lastDoc!!); applyDoc(undo.removeAt(undo.size - 1))
        p.status("undo · ${undo.size} step" + (if (undo.size == 1) "" else "s") + " back")
    }

    fun redo() {
        if (redo.isEmpty()) { p.status("nothing to redo"); return }
        undo.add(lastDoc!!); applyDoc(redo.removeAt(redo.size - 1))
        p.status("redo · ${redo.size} ahead")
    }
}


fun Panels.load(data: dynamic) {
    shakeOwner.parentRevision++
    for (n in nodes()) { if (truthy(n._timer)) window.clearInterval(n._timer); if (truthy(n._es)) { n._es.close(); n._es = null }; n.el.remove() }
    val g = obj(); g.nodes = jsArray(); g.wires = jsArray(); g.seq = if (truthy(data.seq)) data.seq else 1
    g.controls = if (truthy(data.controls)) spread(data.controls) else undefined
    graph = g
    byId("runBtn").disabled = truthy(graph.controls?.inspectionOnly)
    val savedCamera = data.view
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
    if (savedCamera != null) camera.restoreCameraView(savedCamera)
    applyView(); redraw(); save()
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
suspend fun Panels.syncContracts() {
    try {
        val payload = fetchJson("/api/lcnc/contracts")
        val list = arr(payload.contracts)
        val acc = if (truthy(payload.kindAcceptance)) payload.kindAcceptance else obj()
        kindAcceptance = keysOf(acc).associateWith { k -> arr(acc[k]).map { str(it) } }
        kindRefinements = arr(payload.kindRefinements)
        kindHierarchy = arr(payload.kindHierarchy)
        val m = obj()
        for (c in list) {
            // an EMPTY opts array must not become a select
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
        status("${keysOf(m).size} contracts · ${kindAcceptance.size} semantic kinds · ${kindHierarchy.size} rdfs:subClassOf edges")
    } catch (e: CancellationException) { throw e } catch (e: dynamic) {
        status("contracts fetch failed: " + str(e))
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
