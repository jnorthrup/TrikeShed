package borg.trikeshed.web.spacegraph

import borg.trikeshed.landscape.LandscapeNavigation
import borg.trikeshed.web.*
import borg.trikeshed.web.patch.PatchKinds
import borg.trikeshed.web.patch.PatchRing
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * The /panels construction surface: graph state G, the camera `view`, node DOM, wires, rings.
 * Graph nodes stay plain JS objects ({id,type,x,y,params,children,collapsed,subprogram} plus the
 * page-owned el/_parentScope/_childHost/_ringWorld/_view/_timer/_es/_lastOut) so serialize() is the
 * same document the daemon reads.
 */
class Panels : PatchSurface {
    /** wires: {from:[nodeId,port], to:[nodeId,port]} */
    override var graph: dynamic = objOf { it.nodes = jsArray(); it.wires = jsArray(); it.seq = 1 }
    override val view: dynamic = objOf { it.x = 60; it.y = 60; it.z = 1 }
    override val world: HTMLElement = byId("world")
    override val wiresSvg: Element = byId("wires")
    override val viewport: HTMLElement = byId("viewport")
    val menu: HTMLElement = byId("menu")
    val mm: dynamic = byId("minimap")
    val mmx: dynamic = mm.getContext("2d")

    var contracts: dynamic = obj()
    var kindAcceptance: Map<String, List<String>> = emptyMap()
    var kindRefinements: Array<dynamic> = emptyArray()
    var kindHierarchy: Array<dynamic> = emptyArray()
    val runners: MutableMap<String, Runner> = runners(this)
    var lanes: List<dynamic> = emptyList()
    var board: Board = Board(null)
    var diveStack: MutableList<Pair<String, dynamic>> = ArrayList()
    var autosave = true

    val history by lazy { PanelsHistory(this) }
    val shakeOwner by lazy { PanelsShakeOwner(this) }
    val layoutOwner by lazy { PanelsLayoutOwner(this) }
    var workspace: SpaceGraphWorkspace? = null

    val camera = PatchCamera(this)
    val shake = PatchShake(this, camera)
    val layout = PatchLayout(this)

    class Board(val name: String?) {
        val cables = LinkedHashMap<String, dynamic>()
        val violations = LinkedHashMap<String, String>()
    }

    // ── PatchSurface ──
    override fun redraw() = redrawWires()
    override fun save() = saveDoc()
    override fun status(text: String) { byId("status").textContent = text }
    override fun blipLeave() = blip.leave(null)
    override fun applyWireBox() = PatchNavigation.projectWires(wiresSvg, viewport, view)
    override fun fitToContent() = fit()
    override fun ringScaleOf(node: dynamic): Double {
        var s = 1.0; var q = node._parentScope
        while (truthy(q)) { s *= if (truthy(q._view) && truthy(q._view.z)) num(q._view.z) else 1.0; q = q._parentScope }
        return s
    }
    override fun resizeParentFrames(parent: dynamic) { for (child in arr(parent.children)) growRingWorldFor(child) }

    fun p(n: dynamic, k: String): dynamic = n.params[k]
    fun contract(type: dynamic): dynamic = contracts[type]
    fun statusText(): String = str(byId("status").textContent)
    fun panelName(): dynamic = byId("panelName")

    val blip = PanelsBlip(this)

    // ── lanes ─────────────────────────────────────────────────────────────
    /** HYDRATED from /api/lcnc/concentric (ConcentricSurface.LANE_ASSEMBLAGE) — this page authors NO lane schema. */
    suspend fun syncLanes() {
        try {
            val r = fetchJson("/api/lcnc/concentric")
            lanes = arr(r.lanes).map { l ->
                val o = obj(); o.id = l.id; o.match = if (truthy(l.match)) l.match else jsArray(); o.element = l.element; o.seed = l.seed; o.emit = l.emit; o
            }
            if (lanes.isEmpty()) status("lane assemblage empty — /api/lcnc/concentric served no lanes")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: dynamic) { status("lanes fetch failed: " + str(e)) }
    }

    fun laneOf(n: dynamic): dynamic {
        for (l in lanes) if (arr(l.match).any { pfx -> str(n.type).startsWith(str(pfx)) }) return l
        return lanes.firstOrNull { it.id == "fanout" } ?: lanes.lastOrNull()
    }

    /** ring depth: how many scope ancestors enclose this node (D0 = root canvas) */
    fun ringDepth(n: dynamic): Int { var d = 0; var q = n._parentScope; while (truthy(q)) { d++; q = q._parentScope }; return d }

    fun layoutRing(n: dynamic) { layout.ringLayout(n, applyRingView = { applyRingView(it) }) }

    fun applyRingView(n: dynamic) {
        if (!truthy(n._view)) { val v = obj(); v.x = 0; v.y = 0; v.z = 1; n._view = v }
        val v = n._view
        if (!truthy(n._ringWorld)) return
        // An elastic frame cannot hold anything ABOVE or LEFT of itself: hold the edge; the frame grows instead.
        if (num(v.x) < 0) v.x = 0
        if (num(v.y) < 0) v.y = 0
        n._ringWorld.style.transform = "translate(${v.x}px,${v.y}px) scale(${v.z})"
        syncRingFrame(n)
    }

    /**
     * A child dragged inside a ring walks out of the ring's clipping box. Grow the world to hold what
     * is moving, and place nothing. Grow only, never shrink. Walks outward.
     */
    fun growRingWorldFor(child: dynamic) {
        var m = child
        while (truthy(m) && truthy(m._parentScope)) {
            val parent = m._parentScope; val rw = parent._ringWorld
            if (!truthy(rw)) break
            val w = if (truthy(m.el) && truthy(m.el.offsetWidth)) num(m.el.offsetWidth) else 210.0
            val h = if (truthy(m.el) && truthy(m.el.offsetHeight)) num(m.el.offsetHeight) else 96.0
            val needW = (if (truthy(m.x)) num(m.x) else 0.0) + w + PatchRing.EDGE
            val needH = (if (truthy(m.y)) num(m.y) else 0.0) + h + PatchRing.EDGE
            val curW = parseFloat0(rw.style.width); val curH = parseFloat0(rw.style.height)
            var grew = false
            if (needW > curW) { rw.style.width = "${kotlin.math.ceil(needW).toInt()}px"; grew = true }
            if (needH > curH) { rw.style.height = "${kotlin.math.ceil(needH).toInt()}px"; grew = true }
            if (grew) syncRingFrame(parent)
            m = parent
        }
    }

    fun syncRingFrame(n: dynamic) {
        val host = n._childHost; val rw = n._ringWorld
        if (!truthy(host) || !truthy(rw)) return
        val v = if (truthy(n._view)) n._view else objOf { it.x = 0; it.y = 0; it.z = 1 }
        val z = if (truthy(v.z)) num(v.z) else 1.0
        val w = parseFloat0(rw.style.width); val h = parseFloat0(rw.style.height)
        if (w == 0.0 || h == 0.0) return
        // min-* and not width/height, so this is a FLOOR: a ring dragged bigger keeps its size
        val (fw, fh) = PatchRing.frameFloor(borg.trikeshed.web.patch.RingCamera(num(v.x), num(v.y), z), w, h)
        host.style.minWidth = "${js("Math.round")(fw)}px"
        host.style.minHeight = "${js("Math.round")(fh)}px"
    }

    /** ring chrome rides the tree: depth tab + edge captions recompute once parents are linked */
    fun refreshRingChrome(n: dynamic) {
        val d = ringDepth(n)
        val lbl = n.el?.querySelector(":scope > .ringlbl")
        if (lbl != null) lbl.innerHTML = "◍ D$d <i>LcncScopeFrame${if (d > 0) " r$d" else " root"} · bindings·outputs</i>"
        val t = contract(n.type) ?: objOf { it.ins = jsArray(); it.outs = jsArray() }
        val kids = arr(n.children)
        val inNames = kids.filter { it.type == "scope.in" }.map { c -> if (truthy(c.params) && truthy(c.params.name)) str(c.params.name) else "?" }
        val outNames = kids.filter { it.type == "scope.out" }.map { c -> if (truthy(c.params) && truthy(c.params.name)) str(c.params.name) else "?" }
        val seedEl = n.el?.querySelector(":scope > .seed")
        if (seedEl != null) seedEl.textContent = (arr(t.ins).map { str(it) } + inNames.map { "in:$it" }).joinToString(" · ")
        val emitEl = n.el?.querySelector(":scope > .emit")
        if (emitEl != null) emitEl.textContent = (arr(t.outs).map { str(it) } + outNames.map { "out:$it" }).joinToString(" · ")
    }

    fun mountChildren() {
        // parent links first (children arrays ship inside the program doc)
        for (n in nodes()) for (c in arr(n.children)) c._parentScope = n
        for (n in nodes()) if (truthy(n._childHost)) refreshRingChrome(n)
        for (n in nodes()) if (truthy(n._childHost) && !truthy(n._parentScope)) layoutRing(n)
        resolveTopLevelOverlaps()
    }

    /** rings grow; push a colliding top-level sibling right of the grown square — authored x-order kept */
    fun resolveTopLevelOverlaps() {
        val tops = nodes().filter { !truthy(it._parentScope) && truthy(it.el) }
        class R(val x: Double, val y: Double, val w: Double, val h: Double)
        fun rect(n: dynamic) = R(num(n.x), num(n.y), if (truthy(n.el.offsetWidth)) num(n.el.offsetWidth) else 200.0, if (truthy(n.el.offsetHeight)) num(n.el.offsetHeight) else 100.0)
        for (pass in 0 until 3) {
            var moved = false
            val sorted = tops.sortedBy { num(it.x) }
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                val a = rect(sorted[i]); val b = rect(sorted[j])
                if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) {
                    sorted[j].x = a.x + a.w + 40; sorted[j].el.style.left = "${sorted[j].x}px"; moved = true
                }
            }
            if (!moved) break
        }
    }

    /** the spine: lane boxes over their top-level members, measured from what is drawn */
    fun drawSpine() {
        val sp: HTMLElement = (document.getElementById("spine") as HTMLElement?)
            ?: (document.createElement("div") as HTMLElement).also { it.id = "spine"; world.appendChild(it) }
        sp.innerHTML = ""
        val byLane = LinkedHashMap<String, ArrayList<dynamic>>()
        for (n in nodes()) {
            if (truthy(n._parentScope)) continue
            val l = laneOf(n) ?: continue
            byLane.getOrPut(str(l.id)) { ArrayList() }.add(n)
        }
        val pad = 20.0
        for (l in lanes) {
            val ns = (byLane[str(l.id)] ?: continue).sortedBy { num(it.x) }
            if (ns.isEmpty()) continue
            fun w(n: dynamic) = if (truthy(n.el) && truthy(n.el.offsetWidth)) num(n.el.offsetWidth) else 190.0
            fun h(n: dynamic) = if (truthy(n.el) && truthy(n.el.offsetHeight)) num(n.el.offsetHeight) else 96.0
            val x0 = ns.minOf { num(it.x) } - pad; val x1 = ns.maxOf { num(it.x) + w(it) } + pad
            val y0 = ns.minOf { num(it.y) } - pad; val y1 = ns.maxOf { num(it.y) + h(it) } + pad
            val d = document.createElement("div").asDynamic(); d.className = "lane"
            d.style.left = "${x0}px"; d.style.top = "${y0}px"; d.style.width = "${x1 - x0}px"; d.style.height = "${y1 - y0}px"
            val b = document.createElement("b").asDynamic(); b.textContent = str(l.id) + " · " + str(l.element).split(" ·")[0]
            d.appendChild(b); sp.appendChild(d)
        }
    }

    fun redrawConcentric() { drawSpine(); drawMinimap() }

    /** A fresh id is one the board does not already hold (presets name their own nodes). */
    fun mintId(): String {
        val taken = HashSet<String>()
        fun walk(ns: dynamic) { for (n in arr(ns)) { taken.add(str(n.id)); walk(n.children) } }
        walk(graph.nodes)
        var id: String
        do { id = "n" + str(graph.seq); graph.seq = num(graph.seq) + 1 } while (id in taken)
        return id
    }

    fun addNode(type: String, x: Double, y: Double, params: dynamic = null): dynamic {
        val t = contract(type) ?: return null
        val n = obj(); n.id = mintId(); n.type = type; n.x = x; n.y = y; n.params = obj()
        for (k in keysOf(if (truthy(t.params)) t.params else obj())) n.params[k] = (if (params != null) params[k] else null) ?: t.params[k].v
        graph.nodes.push(n); buildNode(n); redraw(); save(); return n
    }

    fun topRingOf(s: dynamic): dynamic { var q = s; while (truthy(q._parentScope)) q = q._parentScope; return q }

    /** double-click inside a ring's square adds a node INTO that ring */
    fun addChildNode(scope: dynamic, type: String): dynamic {
        val t = contract(type) ?: return null
        val n = obj(); n.id = mintId(); n.type = type; n.x = 0; n.y = 0; n.params = obj(); n._parentScope = scope
        for (k in keysOf(if (truthy(t.params)) t.params else obj())) n.params[k] = t.params[k].v
        if (!truthy(scope.children)) scope.children = jsArray()
        scope.children.push(n)
        graph.nodes.push(n); buildNode(n)
        refreshRingChrome(scope)
        layoutRing(topRingOf(scope))
        resolveTopLevelOverlaps()
        redraw(); save(); return n
    }

    // ── kinds: SERVED by Kotlin; the browser only performs lookup ──
    fun kindOf(type: dynamic, dir: String, port: String): String? {
        val c = contract(type) ?: return null
        val m = (if (dir == "out") c.outputKinds else c.inputKinds) ?: obj()
        val k = m[PatchKinds.bare(port)]
        return if (truthy(k)) str(k) else null
    }

    fun portClass(type: dynamic, dir: String, port: String): String = PatchKinds.portClass(kindOf(type, dir, port), kindAcceptance)

    fun kindsCompatible(a: String?, b: String?): Boolean = PatchKinds.compatible(a, b, kindAcceptance)

    fun refinedLiteralKind(nd: dynamic, port: String, declared: String): String {
        for (r in kindRefinements) {
            if (r.nodeType != nd.type || r.outputPort != PatchKinds.bare(port)) continue
            val raw = (if (truthy(nd.params)) nd.params else obj())[port] ?: ""
            val value: dynamic = try { JSON.parse(str(raw)) } catch (e: dynamic) { continue }
            val keys = arr(r.jsonArrayObjectRequiredKeys)
            if (isArray(value) && num(value.length) > 0 && arr(value).all { row ->
                    truthy(row) && typeOf(row) == "object" && !isArray(row) && keys.all { k -> js("Object.prototype.hasOwnProperty.call(row,k)").unsafeCast<Boolean>() }
                }) return str(r.kind)
        }
        return declared
    }

    /** node-aware kind: rings declare PER-NAME ports through their scope.in/scope.out children */
    fun nodeKindOf(nd: dynamic, dir: String, port: String): String? {
        val bare = PatchKinds.bare(port)
        if ((nd.type == "scope.in" && dir == "out" && bare == "value") || (nd.type == "scope.out" && dir == "in" && bare == "value")) {
            val declared = if (truthy(nd.params) && truthy(nd.params.kind)) str(nd.params.kind) else ""
            return declared.ifEmpty { "*" }
        }
        val k = kindOf(nd.type, dir, port)
        if (k != null) return if (dir == "out") refinedLiteralKind(nd, port, k) else k
        if (nd.type == "scope") {
            // PROGRESSIVE TYPING: a ring parameter is GENERIC until its scope.in/scope.out declares a `kind`.
            if (!(truthy(nd.children) && num(nd.children.length) > 0) && truthy(nd.params) && truthy(nd.params.program)) return "*"
            val want = if (dir == "out") "scope.out" else "scope.in"
            var declared: String? = null; var hit = false
            fun wk(m: dynamic) {
                for (c in arr(m.children)) {
                    if (c.type == want && PatchKinds.bare(if (truthy(c.params) && truthy(c.params.name)) str(c.params.name) else "") == bare) {
                        hit = true; val dk = if (truthy(c.params) && truthy(c.params.kind)) str(c.params.kind) else ""; if (dk.isNotEmpty()) declared = dk
                    }
                    wk(c)
                }
            }
            wk(nd)
            if (hit) return declared ?: "*"
        }
        return null
    }

    /** a cable is type-safe when Kotlin's served relation accepts it */
    fun wireKindOk(w: dynamic): Boolean {
        val s = node(w.from[0]); val d = node(w.to[0])
        if (s == null || d == null) return false
        return kindsCompatible(nodeKindOf(s, "out", str(w.from[1])), nodeKindOf(d, "in", str(w.to[1])))
    }

    fun scopePath(n: dynamic): List<String> { val path = ArrayList<String>(); var q = n._parentScope; while (truthy(q)) { path.add(0, str(q.id)); q = q._parentScope }; return path }

    /** Shared authoring operation: both projections obey the same kind and containment rules. */
    fun connectPanelPorts(from: dynamic, to: dynamic): Boolean {
        val src = node(from[0]); val dst = node(to[0])
        if (src == null || dst == null || src === dst) return false
        val sk = nodeKindOf(src, "out", str(from[1])); val dk = nodeKindOf(dst, "in", str(to[1]))
        if (!kindsCompatible(sk, dk)) {
            status("kind mismatch: " + str(src.type) + "." + str(from[1]) + " emits " + (sk ?: "untyped") + ", " + str(dst.type) + "." + str(to[1]) + " wants " + (dk ?: "untyped")); return false
        }
        if (!PatchKinds.reaches(scopePath(src), scopePath(dst))) {
            status("ring boundary: data flows lateral or inward — yields leave through scope.out"); return false
        }
        graph.wires = arr(graph.wires).filter { w -> !(w.to[0] == to[0] && w.to[1] == to[1]) }.toTypedArray()
        graph.wires.push(wire(from[0], from[1], to[0], to[1])); redraw(); save(); return true
    }

    fun removeNode(id: dynamic) {
        val n = node(id) ?: return
        // a ring takes its whole subtree with it
        val drop = HashSet<String>()
        fun collect(m: dynamic) { drop.add(str(m.id)); for (c in arr(m.children)) collect(c) }
        collect(n)
        for (m in nodes()) {
            if (str(m.id) !in drop) continue
            if (truthy(m._timer)) { window.clearInterval(m._timer); m._timer = null }
            if (truthy(m._es)) { m._es.close(); m._es = null }
        }
        val ps = n._parentScope
        if (truthy(ps) && truthy(ps.children)) {
            ps.children = arr(ps.children).filter { it.id != n.id }.toTypedArray(); refreshRingChrome(ps); layoutRing(topRingOf(ps)); resolveTopLevelOverlaps()
        }
        n.el.remove()
        graph.nodes = nodes().filter { str(it.id) !in drop }.toTypedArray()
        graph.wires = arr(graph.wires).filter { w -> str(w.from[0]) !in drop && str(w.to[0]) !in drop }.toTypedArray()
        redraw(); save()
    }

    // ── wires ─────────────────────────────────────────────────────────────
    override fun portCenter(nodeId: String, dir: String, name: String): dynamic {
        val n = node(nodeId) ?: return null
        // a ring's DOM contains its children's ports — resolve the node's OWN port
        var el: dynamic = null
        for (cand in arr(n.el.querySelectorAll(".port[data-dir=\"$dir\"][data-port=\"$name\"]"))) {
            if (cand.closest(".node") === n.el) { el = cand; break }
        }
        if (el == null) return null
        val r = el.getBoundingClientRect(); val w = world.getBoundingClientRect()
        val o = obj()
        o.x = (num(r.left) + num(r.width) / 2 - w.left) / num(view.z)
        o.y = (num(r.top) + num(r.height) / 2 - w.top) / num(view.z)
        return o
    }

    private class WireBox(val x: Int, val y: Int, val w: Int, val h: Int)
    private var wireBox: WireBox? = null

    /** The wire backdrop's box in world units: grown, never shrunk, from coordinates already in hand. */
    private fun growWireBox(pts: List<dynamic>): Boolean {
        val pad = 900.0
        var x0 = Double.POSITIVE_INFINITY; var y0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        for (n in nodes()) {
            if (truthy(n._parentScope)) continue
            val x = if (truthy(n.x)) num(n.x) else 0.0; val y = if (truthy(n.y)) num(n.y) else 0.0
            x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x + 320); y1 = maxOf(y1, y + 220)
        }
        for (pt in pts) { x0 = minOf(x0, num(pt.x)); y0 = minOf(y0, num(pt.y)); x1 = maxOf(x1, num(pt.x)); y1 = maxOf(y1, num(pt.y)) }
        if (!x0.isFinite()) { x0 = 0.0; y0 = 0.0; x1 = 1200.0; y1 = 800.0 }
        x0 -= pad; y0 -= pad; x1 += pad; y1 += pad
        val b = wireBox
        if (b != null && x0 >= b.x && y0 >= b.y && x1 <= b.x + b.w && y1 <= b.y + b.h) return false
        val nx = if (b != null) minOf(b.x, kotlin.math.floor(x0).toInt()) else kotlin.math.floor(x0).toInt()
        val ny = if (b != null) minOf(b.y, kotlin.math.floor(y0).toInt()) else kotlin.math.floor(y0).toInt()
        val nx1 = if (b != null) maxOf(b.x + b.w, kotlin.math.ceil(x1).toInt()) else kotlin.math.ceil(x1).toInt()
        val ny1 = if (b != null) maxOf(b.y + b.h, kotlin.math.ceil(y1).toInt()) else kotlin.math.ceil(y1).toInt()
        wireBox = WireBox(nx, ny, maxOf(1200, nx1 - nx), maxOf(800, ny1 - ny))
        return true
    }

    fun svg(tag: String): dynamic = document.createElementNS("http://www.w3.org/2000/svg", tag).asDynamic()

    fun redrawWires() {
        val ends = ArrayList<dynamic>()
        if (growWireBox(emptyList())) applyWireBox()
        wiresSvg.innerHTML = ""
        redrawConcentric()
        val wires = arr(graph.wires)
        wires.forEachIndexed { idx, wr ->
            val a = portCenter(str(wr.from[0]), "out", str(wr.from[1])); val b = portCenter(str(wr.to[0]), "in", str(wr.to[1]))
            if (a == null || b == null) return@forEachIndexed
            ends.add(a); ends.add(b)
            val path = svg("path")
            PatchNavigation.wireCurve(path, a, b, viewport, view); path.classList.add("live")
            val srcN = node(wr.from[0])
            val tc = PatchKinds.kindColor[if (srcN != null) portClass(srcN.type, "out", str(wr.from[1])) else ""]
            if (tc != null) path.style.stroke = tc
            if (str(wr.from[0]) in shake.starved) path.classList.add("starved")
            if (!wireKindOk(wr)) {
                path.classList.add("mismatch")
                val t = svg("title")
                t.textContent = "kind mismatch — this cable feeds nothing: " + arr(wr.from).joinToString(".") { str(it) } + " → " + arr(wr.to).joinToString(".") { str(it) }
                path.appendChild(t)
            }
            val bk = PatchKinds.cableKey(str(wr.from[0]), str(wr.from[1]), str(wr.to[0]), str(wr.to[1]))
            val refused = board.violations[bk]
            if (refused != null) {
                path.classList.add("refused")
                val t = svg("title"); t.textContent = "refused by the daemon: $refused"; path.appendChild(t)
            }
            on(path, "pointerdown", { e -> e.stopPropagation(); graph.wires.splice(idx, 1); redraw(); save() })
            wiresSvg.appendChild(path)
            if (board.cables.containsKey(bk)) {
                val ty = board.cables[bk]
                val label = svg("text")
                label.classList.add("wiretype"); if (refused != null) label.classList.add("refused")
                label.setAttribute("x", (num(a.x) + num(b.x)) / 2); label.setAttribute("y", (num(a.y) + num(b.y)) / 2 - 4); label.setAttribute("text-anchor", "middle")
                label.textContent = if (ty == null) "unresolved" else ty
                val tt = svg("title")
                tt.textContent = (if (ty == null) "unresolved on the board" else "typed on the board: " + str(ty)) + (if (refused != null) " — refused: $refused" else ""); label.appendChild(tt)
                wiresSvg.appendChild(label)
            }
        }
        // endpoints are the truth: a cable into a deeply nested, zoomed ring can sit outside the prediction
        if (ends.isNotEmpty() && growWireBox(ends)) applyWireBox()
        shake.positionVerdicts()
    }

    // ── camera ────────────────────────────────────────────────────────────
    fun applyView() = camera.applyView()

    /** Frame every top-level node (F). */
    fun fit() {
        camera.killMomentum()
        val tops = nodes().filter { !truthy(it._parentScope) && truthy(it.el) }
        if (tops.isEmpty()) return
        var x0 = Double.POSITIVE_INFINITY; var y0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        for (n in tops) {
            x0 = minOf(x0, num(n.x)); y0 = minOf(y0, num(n.y))
            x1 = maxOf(x1, num(n.x) + (if (truthy(n.el.offsetWidth)) num(n.el.offsetWidth) else 190.0))
            y1 = maxOf(y1, num(n.y) + (if (truthy(n.el.offsetHeight)) num(n.el.offsetHeight) else 90.0))
        }
        val r = viewport.getBoundingClientRect(); val pad = 48.0
        val z = minOf(LandscapeNavigation.detailZoom, maxOf(LandscapeNavigation.minZoom, minOf((r.width - pad * 2) / maxOf(1.0, x1 - x0), (r.height - pad * 2) / maxOf(1.0, y1 - y0))))
        view.z = z
        view.x = (r.width - (x1 - x0) * z) / 2 - x0 * z
        view.y = (r.height - (y1 - y0) * z) / 2 - y0 * z
        applyView(); camera.saveCameraSoon()
    }

    // ── minimap: world overview + click-to-jump ──
    fun drawMinimap() {
        mmx.clearRect(0, 0, 168, 112)
        // fractal containment: one inset frame per dive level — the crumb, drawn
        val depth = diveStack.size
        for (i in 0..depth) {
            val ins = 2 + i * 5
            mmx.strokeStyle = if (i == depth) "#b07aff" else "#232a38"
            mmx.strokeRect(ins + .5, ins + .5, 168 - 2 * ins - 1, 112 - 2 * ins - 1)
        }
        val tops = nodes().filter { !truthy(it._parentScope) && truthy(it.el) }
        if (tops.isEmpty()) return
        val pad0 = 2 + depth * 5 + 3
        var minX = 1e9; var minY = 1e9; var maxX = -1e9; var maxY = -1e9
        for (n in tops) { minX = minOf(minX, num(n.x)); minY = minOf(minY, num(n.y)); maxX = maxOf(maxX, num(n.x) + 200); maxY = maxOf(maxY, num(n.y) + 120) }
        val pad = 40.0
        val s = minOf((168.0 - 2 * pad0) / (maxX - minX + 2 * pad), (112.0 - 2 * pad0) / (maxY - minY + 2 * pad))
        val ox = pad0 - (minX - pad) * s; val oy = pad0 - (minY - pad) * s
        for (n in tops) {
            mmx.fillStyle = if (truthy(n.el.classList.contains("err"))) "#ff4f58" else if (truthy(n.el.classList.contains("ok"))) "#3ddc84" else "#7b8496"
            mmx.fillRect(num(n.x) * s + ox, num(n.y) * s + oy, maxOf(3.0, 180 * s), maxOf(2.0, 80 * s))
        }
        val vw = num(viewport.clientWidth) / num(view.z); val vh = num(viewport.clientHeight) / num(view.z)
        mmx.strokeStyle = "#f29111"; mmx.lineWidth = 1
        mmx.strokeRect((-num(view.x) / num(view.z)) * s + ox, (-num(view.y) / num(view.z)) * s + oy, vw * s, vh * s)
        mm._s = s; mm._ox = ox; mm._oy = oy
    }

    fun scopeAtTarget(t: dynamic): dynamic {
        if (t == null || t.classList == null) return null
        if (truthy(t.classList.contains("childgrid"))) return nodes().firstOrNull { it._childHost === t }
        if (truthy(t.classList.contains("ringworld"))) return nodes().firstOrNull { it._ringWorld === t }
        return null
    }

    companion object {
        fun parseFloat0(v: dynamic): Double { val f = num(js("parseFloat")(v)); return if (f.isNaN()) 0.0 else f }
    }
}
