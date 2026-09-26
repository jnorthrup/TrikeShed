package borg.trikeshed.web.harness

import borg.trikeshed.web.PatchCamera
import borg.trikeshed.web.PatchLayout
import borg.trikeshed.web.PatchNavigation
import borg.trikeshed.web.PatchShake
import borg.trikeshed.web.PatchSurface
import borg.trikeshed.web.arr
import borg.trikeshed.web.isArray
import borg.trikeshed.web.jsArray
import borg.trikeshed.web.num
import borg.trikeshed.web.on
import borg.trikeshed.web.str
import borg.trikeshed.web.typeOf
import borg.trikeshed.web.patch.PatchKinds
import borg.trikeshed.web.patch.PatchRing
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * patch.js, harness variant: the construction surface the blackboard drives. Graph nodes are plain
 * JS objects ({id,type,x,y,params,children,collapsed,subprogram} plus the page-owned
 * el/_parentScope/_childHost/_ringWorld/_view/_frame/_program/_localId), so the documents the
 * Harness reads and writes are unchanged. Every top-level name patch.js declared is published on
 * `window` by [HarnessPatchGlobals].
 */
object HarnessPatch : PatchSurface {
    /** wires: {from:[nodeId,port], to:[nodeId,port]} */
    override var graph: dynamic = js("({nodes:[],wires:[],seq:1})")
    override var view: dynamic = js("({x:60,y:60,z:1})")
    override val world: HTMLElement = byId("world")
    override val wiresSvg: Element = document.getElementById("wires")!!
    override val viewport: HTMLElement = byId("viewport")
    val menu: HTMLElement = byId("menu")
    val mm: dynamic = byId("minimap")
    val mmx: dynamic = mm.getContext("2d")

    val camera = PatchCamera(this) { window.asDynamic().Harness }
    val shake = PatchShake(this, camera)
    val layout = PatchLayout(this)

    // ── contracts: the vocabulary is board-owned ──
    var contracts: dynamic = jsObject()
    var kindAcceptance: dynamic = jsObject()
    var acceptance: Map<String, List<String>> = emptyMap()
    var kindRefinements: dynamic = jsArray()
    var kindHierarchy: dynamic = jsArray()
    val runners: MutableMap<String, HarnessRunner> = harnessRunners()

    /** [{id,match,element,seed,emit}] hydrated from /api/lcnc/concentric */
    var lanes: dynamic = jsArray()
    /** [{name,data}] — data is this level's serialize() before diving deeper */
    var diveStack: dynamic = jsArray()
    /** BOARD={name,cables:Map,violations:Map} */
    var board: dynamic = newBoard(null)
    var dragWire: dynamic = null
    var autosave = true

    // ── undo / redo ──
    val undoStack: dynamic = jsArray()
    val redoStack: dynamic = jsArray()
    const val UNDO_MAX = 200
    var restoring = false
    var lastDoc: dynamic = null
    var lastPushMs = 0.0

    const val RING_EDGE = PatchRing.EDGE
    const val RING_GAP = PatchRing.GAP
    const val WIRE_PAD = 900.0

    /** Presentation sizes survive board remounts, but never enter executable documents. */
    val nodeFrames = JsMap<String, dynamic>()

    fun newBoard(name: String?): dynamic = obj { it.name = name; it.cables = JsMap<String, dynamic>(); it.violations = JsMap<String, dynamic>() }

    private val harness: dynamic get() = window.asDynamic().Harness
    private val ready: Boolean get() = harness.ready == true

    fun nodes(): Array<dynamic> = arr(graph.nodes)
    fun wires(): Array<dynamic> = arr(graph.wires)
    fun node(id: dynamic): dynamic = nodes().firstOrNull { it.id == id }
    fun p(n: dynamic, k: String): dynamic = n.params[k]
    fun contract(type: dynamic): dynamic = contracts[type]
    fun panelName(): dynamic = byId("panelName")
    fun svg(tag: String): dynamic = document.createElementNS("http://www.w3.org/2000/svg", tag).asDynamic()
    fun parseFloat0(v: dynamic): Double { val f = num(js("parseFloat")(v)); return if (f.isNaN()) 0.0 else f }

    // ── PatchSurface ──
    override fun status(text: String) { q("#status").textContent = text }
    override fun save() = saveDoc()
    override fun blipLeave() = HarnessBlip.leave(null)
    override fun fitToContent() = fitView()
    override fun showConnections(programName: String, result: dynamic, text: String) = Harness.showConnections(programName, result, text)

    // ── lanes ──
    fun laneOf(n: dynamic): dynamic {
        for (l in arr(lanes)) if (arr(l.match).any { pfx -> str(n.type).startsWith(str(pfx)) }) return l
        return arr(lanes).firstOrNull { it.id == "fanout" } ?: arr(lanes).lastOrNull()
    }

    /** ring depth: how many scope ancestors enclose this node (D0 = root canvas) */
    fun ringDepth(n: dynamic): Int { var d = 0; var s = n._parentScope; while (truthy(s)) { d++; s = s._parentScope }; return d }

    fun applyNodeFrame(n: dynamic) {
        if (!truthy(n._frame)) return
        if (truthy(n._childHost)) {
            val w = parseFloat0(n._ringWorld.style.width).let { if (it == 0.0) 1.0 else it }
            val h = parseFloat0(n._ringWorld.style.height).let { if (it == 0.0) 1.0 else it }
            n._view = obj { it.x = 0; it.y = 0; it.z = minOf(num(n._frame.w) / w, num(n._frame.h) / h) }
            n.el.style.width = ""
            applyRingView(n)
        } else {
            n.el.style.width = "${n._frame.w}px"
            n.el.style.minHeight = if (truthy(n.collapsed)) "" else "${n._frame.h}px"
        }
    }

    fun resizeNode(event: dynamic, n: dynamic) {
        if (event.button != 0 || truthy(n.collapsed)) return
        event.preventDefault(); event.stopPropagation()
        harness.selectParent(n)
        camera.killMomentum()
        val revision = harness.parentRevision
        val handle = event.currentTarget; val scale = num(view.z) * ringScaleOf(n)
        if (!(scale > 0)) return
        val box = if (truthy(n._childHost)) n._childHost else n.el
        val old = n._frame; val oldView = js("Object.assign({}, n._view)")
        val styles = listOf(n.el, n._childHost, n._ringWorld).filter { truthy(it) }.map { Pair<dynamic, String>(it, str(it.style.cssText)) }
        val startW = num(box.offsetWidth); val startH = num(box.offsetHeight); val sx = num(event.clientX); val sy = num(event.clientY)
        var moved = false; var finished = false
        fun stale(): Boolean = !nodes().contains(n) || harness.parentRevision != revision
        lateinit var move: (dynamic) -> Unit
        lateinit var up: (dynamic) -> Unit
        lateinit var cancel: (dynamic) -> Unit
        lateinit var key: (dynamic) -> Unit
        fun finish(cancelled: Boolean) {
            if (finished) return; finished = true
            window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up)
            window.removeEventListener("pointercancel", cancel); window.removeEventListener("keydown", key)
            handle.removeEventListener("lostpointercapture", cancel)
            if (truthy(handle.hasPointerCapture(event.pointerId))) handle.releasePointerCapture(event.pointerId)
            if (cancelled || stale()) {
                n._frame = old; n._view = oldView
                for (pair in styles) { val el: dynamic = pair.first; el.style.cssText = pair.second }
            } else if (moved) {
                nodeFrames.delete(str(n.id))
                nodeFrames.set(str(n.id), obj { it.type = n.type; it.w = n._frame.w; it.h = n._frame.h })
                if (nodeFrames.size > 1024) nodeFrames.delete(nodeFrames.keyList().first())
                resizeParentFrames(n._parentScope)
            }
            redraw(); harness.schedule()
        }
        move = { e ->
            if (e.pointerId == event.pointerId) {
                if (stale()) finish(true)
                else if (moved || hypot(num(e.clientX) - sx, num(e.clientY) - sy) >= 3) {
                    moved = true
                    n._frame = obj {
                        it.w = maxOf(160.0, minOf(1600.0, startW + (num(e.clientX) - sx) / scale))
                        it.h = maxOf(80.0, minOf(1200.0, startH + (num(e.clientY) - sy) / scale))
                    }
                    applyNodeFrame(n); redraw()
                }
            }
        }
        up = { e -> if (e.pointerId == event.pointerId) finish(false) }
        cancel = { finish(true) }
        key = { e -> if (e.key == "Escape") { e.preventDefault(); finish(true) } }
        handle.setPointerCapture(event.pointerId); handle.addEventListener("lostpointercapture", cancel)
        window.addEventListener("pointermove", move); window.addEventListener("pointerup", up)
        window.addEventListener("pointercancel", cancel); window.addEventListener("keydown", key)
    }

    fun toggleNodeCollapsed(n: dynamic, button: dynamic) {
        harness.selectParent(n)
        n.collapsed = !truthy(n.collapsed); n.el.classList.toggle("collapsed", n.collapsed)
        button.textContent = if (truthy(n.collapsed)) "\u25b8" else "\u25be"
        button.setAttribute("aria-expanded", str(!truthy(n.collapsed)))
        button.setAttribute("aria-label", if (truthy(n.collapsed)) "Expand node" else "Collapse node")
        button.title = if (truthy(n.collapsed)) "Expand node" else "Collapse node (keep contents)"
        applyNodeFrame(n); resizeParentFrames(n._parentScope); redraw(); save()
    }

    fun layoutRing(n: dynamic, preserve: Boolean = false): dynamic =
        layout.ringLayout(n, preserve, { p, ancestors -> resizeParentFrames(p, ancestors) }, { applyNodeFrame(it) }, { applyRingView(it) })

    /** per-ring camera: outer scales compound, so pointer math divides by the product */
    override fun ringScaleOf(node: dynamic): Double {
        var s = 1.0; var q = node._parentScope
        while (truthy(q)) { s *= if (truthy(q._view) && truthy(q._view.z)) num(q._view.z) else 1.0; q = q._parentScope }
        return s
    }

    override fun resizeParentFrames(parent: dynamic) = resizeParentFrames(parent, true)

    /** Resize only the affected ancestry; never rearrange a sibling's interior. */
    fun resizeParentFrames(parent: dynamic, ancestors: Boolean) {
        var n = parent
        while (truthy(n)) {
            if (truthy(n._ringWorld)) {
                val children = arr(n.children)
                val w = children.fold(1.0) { m, c -> maxOf(m, num(c.x) + (if (truthy(c.el?.offsetWidth)) num(c.el.offsetWidth) else 200.0) + RING_EDGE) }
                val h = children.fold(1.0) { m, c -> maxOf(m, num(c.y) + (if (truthy(c.el?.offsetHeight)) num(c.el.offsetHeight) else 100.0) + RING_EDGE) }
                n._ringWorld.style.width = "${w}px"; n._ringWorld.style.height = "${h}px"
                val v = PatchRing.view(w, h); n._view = obj { it.x = v.x; it.y = v.y; it.z = v.z }
                applyRingView(n)
                applyNodeFrame(n)
            }
            n = if (ancestors) n._parentScope else null
        }
    }

    fun applyRingView(n: dynamic) {
        val v = if (truthy(n._view)) n._view else obj { it.x = 0; it.y = 0; it.z = 1 }
        if (!truthy(n._ringWorld)) return
        // An elastic frame cannot hold anything ABOVE or LEFT of itself: hold the edge; the frame grows instead.
        if (num(v.x) < 0) v.x = 0
        if (num(v.y) < 0) v.y = 0
        n._ringWorld.style.transform = "translate(${v.x}px,${v.y}px) scale(${v.z})"
        syncRingFrame(n)
    }

    /** A child dragged inside a ring grows the world to hold what is moving and places nothing; walks outward. */
    fun growRingWorldFor(child: dynamic) {
        var m = child
        while (truthy(m) && truthy(m._parentScope)) {
            val parent = m._parentScope; val rw = parent._ringWorld
            if (!truthy(rw)) break
            val w = if (truthy(m.el) && truthy(m.el.offsetWidth)) num(m.el.offsetWidth) else 210.0
            val h = if (truthy(m.el) && truthy(m.el.offsetHeight)) num(m.el.offsetHeight) else 96.0
            val needW = (if (truthy(m.x)) num(m.x) else 0.0) + w + RING_EDGE
            val needH = (if (truthy(m.y)) num(m.y) else 0.0) + h + RING_EDGE
            val curW = parseFloat0(rw.style.width); val curH = parseFloat0(rw.style.height)
            var grew = false
            if (needW > curW) { rw.style.width = "${ceil(needW).toInt()}px"; grew = true }
            if (needH > curH) { rw.style.height = "${ceil(needH).toInt()}px"; grew = true }
            if (grew) syncRingFrame(parent)
            m = parent
        }
    }

    /** The blackboard frame is width/height: its contents at the current zoom, or the hand-set frame. */
    fun syncRingFrame(n: dynamic) {
        val host = n._childHost; val rw = n._ringWorld
        if (!truthy(host) || !truthy(rw)) return
        val v = if (truthy(n._view)) n._view else obj { it.x = 0; it.y = 0; it.z = 1 }
        val z = if (truthy(v.z)) num(v.z) else 1.0
        val w = parseFloat0(rw.style.width); val h = parseFloat0(rw.style.height)
        if (w == 0.0 || h == 0.0) return
        host.style.minWidth = "0"; host.style.minHeight = "0"
        host.style.width = "${ceil(if (truthy(n._frame?.w)) num(n._frame.w) else w * z).toInt()}px"
        host.style.height = "${ceil(if (truthy(n._frame?.h)) num(n._frame.h) else h * z).toInt()}px"
    }

    /** ring chrome rides the tree: depth tab + edge captions recompute once parents are linked */
    fun refreshRingChrome(n: dynamic) {
        val d = ringDepth(n)
        val lbl = n.el?.querySelector(":scope > .ringlbl")
        if (lbl != null) {
            lbl.textContent = "D$d " + str(if (truthy(n._localId)) n._localId else n.id)
            lbl.title = "Scope depth $d; arguments enter bindings, child yields form returns"
        }
        val t = contract(n.type) ?: obj { it.ins = jsArray(); it.outs = jsArray() }
        val kids = arr(n.children)
        fun nameOf(c: dynamic) = if (truthy(c.params) && truthy(c.params.name)) str(c.params.name) else "?"
        val inNames = kids.filter { it.type == "scope.in" }.map { nameOf(it) }
        val outNames = kids.filter { it.type == "scope.out" }.map { nameOf(it) }
        val seedEl = n.el?.querySelector(":scope > .seed")
        if (seedEl != null) seedEl.textContent = (arr(t.ins).map { str(it) } + inNames.map { "in:$it" }).joinToString(" · ")
        val emitEl = n.el?.querySelector(":scope > .emit")
        if (emitEl != null) emitEl.textContent = (arr(t.outs).map { str(it) } + outNames.map { "out:$it" }).joinToString(" · ")
        renderNodePorts(n)
    }

    fun mountChildren() {
        // parent links first (children arrays ship inside the program doc)
        for (n in nodes()) for (c in arr(n.children)) c._parentScope = n
        for (n in nodes()) if (truthy(n._childHost)) refreshRingChrome(n)
        for (n in nodes()) if (truthy(n._childHost) && !truthy(n._parentScope)) layoutRing(n)
        resolveTopLevelOverlaps()
    }

    /** rings grow; push a colliding top-level sibling of the same program right of the grown square */
    fun resolveTopLevelOverlaps() {
        val tops = nodes().filter { !truthy(it._parentScope) && truthy(it.el) }
        class R(val x: Double, val y: Double, val w: Double, val h: Double)
        fun rect(n: dynamic) = R(num(n.x), num(n.y), if (truthy(n.el.offsetWidth)) num(n.el.offsetWidth) else 200.0, if (truthy(n.el.offsetHeight)) num(n.el.offsetHeight) else 100.0)
        for (pass in 0 until 3) {
            var moved = false
            val sorted = tops.sortedBy { num(it.x) }
            for (i in sorted.indices) for (j in i + 1 until sorted.size) {
                val a = rect(sorted[i]); val b = rect(sorted[j])
                if (sorted[i]._program == sorted[j]._program && a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) {
                    sorted[j].x = a.x + a.w + 40; sorted[j].el.style.left = "${sorted[j].x}px"; moved = true
                }
            }
            if (!moved) break
        }
    }

    /** the spine: lane boxes over their top-level members, measured from what is drawn */
    fun drawSpine() {
        val sp: dynamic = document.getElementById("spine") ?: document.createElement("div").also { it.id = "spine"; world.appendChild(it) }
        sp.innerHTML = ""
        val byLane = LinkedHashMap<String, ArrayList<dynamic>>()
        for (n in nodes()) {
            if (truthy(n._parentScope)) continue
            val l = laneOf(n) ?: continue
            byLane.getOrPut(str(l.id)) { ArrayList() }.add(n)
        }
        val pad = 20.0
        for (l in arr(lanes)) {
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
        if (contract(type) == null) return null
        if (ready && harness.selected == null) {
            val requested = str(panelName().value).trim()
            val names = arr(harness.programs()).map { str(it).substring(13) }.toSet()
            var name = requested.ifEmpty { "untitled" }; var suffix = 1
            while (name in names) name = requested.ifEmpty { "untitled" } + "-" + (suffix++)
            harness.drafts.set(name, js("({nodes:[],wires:[],seq:1})")); harness.select(name, false)
        }
        val n = jsObject(); n.id = mintId(); n.type = type; n.x = x; n.y = y; n.params = nodeParams(type, params)
        n._program = harness.selected
        graph.nodes.push(n); buildNode(n); redraw(); save(); return n
    }

    fun topRingOf(s: dynamic): dynamic { var q = s; while (truthy(q._parentScope)) q = q._parentScope; return q }

    /** double-click inside a ring's square adds a node INTO that ring */
    fun addChildNode(scope: dynamic, type: String): dynamic {
        if (contract(type) == null) return null
        val n = jsObject(); n.id = mintId(); n.type = type; n.x = 0; n.y = 0; n.params = nodeParams(type); n._parentScope = scope
        n._program = scope._program
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
        val m = (if (dir == "out") c.outputKinds else c.inputKinds) ?: jsObject()
        val k = m[PatchKinds.bare(port)]
        return if (truthy(k)) str(k) else null
    }

    fun portClass(type: dynamic, dir: String, port: String): String = PatchKinds.portClass(kindOf(type, dir, port), acceptance)

    fun kindsCompatible(a: String?, b: String?): Boolean = PatchKinds.compatible(a, b, acceptance)

    fun refinedLiteralKind(nd: dynamic, port: String, declared: String): String {
        for (r in arr(kindRefinements)) {
            if (r.nodeType != nd.type || r.outputPort != PatchKinds.bare(port)) continue
            val raw = (if (truthy(nd.params)) nd.params else jsObject())[port] ?: ""
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

    /** a cable is type-safe when Kotlin's served relation accepts it, within one program */
    fun wireKindOk(w: dynamic): Boolean {
        val s = node(w.from[0]); val d = node(w.to[0])
        if (s == null || d == null) return false
        if (s._program != d._program) return false
        return kindsCompatible(nodeKindOf(s, "out", str(w.from[1])), nodeKindOf(d, "in", str(w.to[1])))
    }

    fun nodeParams(type: dynamic, authored: dynamic = null): dynamic {
        val params: dynamic = js("Object.assign({}, authored)")
        val specs = contract(type)?.params ?: jsObject()
        for (key in keysOf(specs)) {
            // Absence of a scope default is meaningful: it is not an empty binding.
            if (params[key] === undefined && !(type == "scope.in" && key == "default")) params[key] = specs[key].v ?: ""
        }
        return params
    }

    fun nodePorts(n: dynamic, dir: String): List<String> {
        val c = contract(n.type)
        val declared = if (c == null) emptyList() else arr(c[if (dir == "in") "ins" else "outs"]).map { str(it) }
        if (n.type != "scope" && !(truthy(n.children) && num(n.children.length) > 0)) return declared
        val named = arr(n.children).filter { it.type == (if (dir == "in") "scope.in" else "scope.out") }
            .map { it.params?.name }.filter { truthy(it) }.map { str(it) }
        return LinkedHashSet(declared + named).toList()
    }

    class BindingLink(val scope: dynamic, val child: dynamic, val from: Array<String>, val to: Array<String>, val kind: String, val label: String)

    /** Structural frame projections, not authored cables or evidence of execution. */
    fun scopeBindingLinks(nodes: List<dynamic>, wires: Array<dynamic>, limit: Int = 512): List<BindingLink> {
        val links = ArrayList<BindingLink>(); val incoming = HashMap<String, HashSet<String>>()
        for (w in wires) incoming.getOrPut(str(w.to[0])) { HashSet() }.add(str(w.to[1]).replace(Regex("\\?$"), ""))
        fun add(scope: dynamic, child: dynamic, from: Array<String>, to: Array<String>, kind: String, label: String) {
            if (links.size < limit) links.add(BindingLink(scope, child, from, to, kind, label))
        }
        for (scope in nodes) {
            if (links.size >= limit) break
            if (scope.type != "scope" || !(truthy(scope.children) && num(scope.children.length) > 0)) continue
            for (child in arr(scope.children)) {
                if (links.size >= limit) break
                val raw = child.params?.name
                if (!truthy(raw)) continue
                val name = str(raw).replace(Regex("\\?$"), ""); if (name.isEmpty()) continue
                if (child.type == "scope.in") {
                    val port = nodePorts(scope, "in").firstOrNull { it.replace(Regex("\\?$"), "") == name }
                    if (port != null) add(scope, child, arrayOf(str(scope.id), "in", port), arrayOf(str(child.id), "out", "value"), "binding",
                        "$name binding declaration; resolved at runtime from named input, args map or enclosing frame, then default")
                    if (incoming[str(scope.id)]?.contains("args") == true) add(scope, child, arrayOf(str(scope.id), "in", "args?"), arrayOf(str(child.id), "out", "value"), "argument",
                        "args.$name candidate; named input takes precedence; map contents are checked at runtime")
                } else if (child.type == "scope.out") {
                    val port = nodePorts(scope, "out").firstOrNull { it.replace(Regex("\\?$"), "") == name }
                    if (port != null) add(scope, child, arrayOf(str(child.id), "in", "value"), arrayOf(str(scope.id), "out", port), "yield", "yield $name only when the child receives a value")
                    add(scope, child, arrayOf(str(child.id), "in", "value"), arrayOf(str(scope.id), "out", "returns"), "yield", "collect returns.$name; this is a field name, not a constant value")
                }
            }
        }
        return links
    }

    fun scopePortLabel(n: dynamic, dir: String, name: String): String {
        if (n.type != "scope") return name
        if (dir == "in" && name.replace(Regex("\\?$"), "") == "args") return "args? {bindings}"
        if (dir == "out" && name == "returns") return "returns {" + arr(n.children).filter { it.type == "scope.out" }
            .map { it.params?.name }.filter { truthy(it) }.joinToString(", ") { str(it) } + "}"
        return name
    }

    fun refreshBindingCaption(n: dynamic) {
        if (n.type != "scope.in" && n.type != "scope.out") return
        val seed = n.el?.querySelector(":scope > .seed"); val emit = n.el?.querySelector(":scope > .emit")
        val name = if (truthy(n.params?.name)) str(n.params.name) else "?"
        if (seed != null) seed.textContent = if (n.type == "scope.in") "binding $name" else "yield $name"
        if (emit != null) emit.textContent = if (n.type == "scope.in")
            (if (js("Object.prototype.hasOwnProperty.call(n.params||{},'default')").unsafeCast<Boolean>()) "default: " + str(n.params["default"]) else "from enclosing bindings")
        else (if (truthy(n._parentScope)) str(if (truthy(n._parentScope._localId)) n._parentScope._localId else n._parentScope.id) + ".returns." + name else "program returns.$name")
    }

    fun renderNodePorts(n: dynamic) {
        val body = n.el?.querySelector(":scope > .body") ?: return
        body.replaceChildren()
        for (dir in listOf("in", "out")) for (name in nodePorts(n, dir)) {
            val kind = nodeKindOf(n, dir, name)
            val row = document.createElement("div").asDynamic(); row.className = "row $dir"
            val port = document.createElement("span").asDynamic(); port.className = "port " + portClass(n.type, dir, name)
            port.dataset.dir = dir; port.dataset.port = name; port.dataset.kind = kind ?: ""
            port.title = name + ": " + (if (kind == "*") "generic binding" else kind ?: "untyped")
            if (truthy(contract(n.type)?.effect)) port.classList.add("fx")
            on(port, "pointerdown", { e -> e.stopPropagation(); e.preventDefault(); startWireDrag(n, dir, name, e) })
            val label = scopePortLabel(n, dir, name)
            if (dir == "in") row.append(port, label) else row.append(label, port)
            body.append(row)
        }
        refreshBindingCaption(n)
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

    // ── wires ──
    override fun portCenter(nodeId: String, dir: String, name: String): dynamic {
        val n = node(nodeId) ?: return null
        val closure = HarnessLandscape.visibleClosure(n)
        if (closure !== n) {
            val r = closure.el.getBoundingClientRect(); val w = world.getBoundingClientRect()
            return obj { it.x = ((if (dir == "out") num(r.right) else num(r.left)) - w.left) / num(view.z); it.y = (num(r.top) + num(r.height) / 2 - w.top) / num(view.z) }
        }
        // a ring's DOM contains its children's ports — resolve the node's OWN port
        var el: dynamic = null
        for (cand in arr(n.el.querySelectorAll(".port[data-dir=\"$dir\"][data-port=\"$name\"]"))) {
            if (cand.closest(".node") === n.el) { el = cand; break }
        }
        if (el == null) return null
        val r = el.getBoundingClientRect(); val w = world.getBoundingClientRect()
        return obj { it.x = (num(r.left) + num(r.width) / 2 - w.left) / num(view.z); it.y = (num(r.top) + num(r.height) / 2 - w.top) / num(view.z) }
    }

    fun portCenterOf(end: Array<String>): dynamic = portCenter(end[0], end[1], end[2])

    private class WireBox(val x: Int, val y: Int, val w: Int, val h: Int)
    private var wireBox: WireBox? = null

    /** The wire backdrop's box in world units: grown, never shrunk, from coordinates already in hand. */
    fun growWireBox(pts: List<dynamic>): Boolean {
        var x0 = Double.POSITIVE_INFINITY; var y0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        for (n in nodes()) {
            if (truthy(n._parentScope)) continue // a child lives inside its ring's box
            val x = if (truthy(n.x)) num(n.x) else 0.0; val y = if (truthy(n.y)) num(n.y) else 0.0
            x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x + 320); y1 = maxOf(y1, y + 220)
        }
        for (pt in pts) { x0 = minOf(x0, num(pt.x)); y0 = minOf(y0, num(pt.y)); x1 = maxOf(x1, num(pt.x)); y1 = maxOf(y1, num(pt.y)) }
        if (!x0.isFinite()) { x0 = 0.0; y0 = 0.0; x1 = 1200.0; y1 = 800.0 }
        x0 -= WIRE_PAD; y0 -= WIRE_PAD; x1 += WIRE_PAD; y1 += WIRE_PAD
        val b = wireBox
        if (b != null && x0 >= b.x && y0 >= b.y && x1 <= b.x + b.w && y1 <= b.y + b.h) return false
        val nx = if (b != null) minOf(b.x, floor(x0).toInt()) else floor(x0).toInt()
        val ny = if (b != null) minOf(b.y, floor(y0).toInt()) else floor(y0).toInt()
        val nx1 = if (b != null) maxOf(b.x + b.w, ceil(x1).toInt()) else ceil(x1).toInt()
        val ny1 = if (b != null) maxOf(b.y + b.h, ceil(y1).toInt()) else ceil(y1).toInt()
        wireBox = WireBox(nx, ny, maxOf(1200, nx1 - nx), maxOf(800, ny1 - ny))
        return true
    }

    /** In the blackboard, ropes live in a viewport-sized SVG projected through its viewBox. */
    override fun applyWireBox() {
        if (wiresSvg.parentElement === viewport) {
            for (s in listOf(wiresSvg, document.getElementById("channels"))) {
                if (s == null) continue
                PatchNavigation.projectWires(s, viewport, view)
            }
            return
        }
        val b = wireBox ?: return
        wiresSvg.setAttribute("width", b.w.toString()); wiresSvg.setAttribute("height", b.h.toString())
        wiresSvg.setAttribute("viewBox", "${b.x} ${b.y} ${b.w} ${b.h}")
        wiresSvg.asDynamic().style.left = "${b.x}px"; wiresSvg.asDynamic().style.top = "${b.y}px"
    }

    fun bez(a: dynamic, b: dynamic): String {
        val dx = maxOf(40.0, kotlin.math.abs(num(b.x) - num(a.x)) / 2)
        return "M ${a.x} ${a.y} C ${num(a.x) + dx} ${a.y}, ${num(b.x) - dx} ${b.y}, ${b.x} ${b.y}"
    }

    // ── WHAT THE DAEMON OBEYS, SHOWN: the program's board entry types every cable ──
    fun cableKey(fromNode: dynamic, fromPort: dynamic, toNode: dynamic, toPort: dynamic): String =
        PatchKinds.cableKey(str(fromNode), str(fromPort), str(toNode), str(toPort))

    fun currentProgramName(): String = str(panelName().value ?: "").trim()

    fun applyBoardEntry(name: String?, e: dynamic, loadDocument: Boolean = false): Boolean {
        board = newBoard(if (name.isNullOrEmpty()) null else name)
        if (!truthy(e) || typeOf(e) != "object") { redraw(); return false }
        for (c in arr(e.cables)) board.cables.set(cableKey(c.from[0], c.from[1], c.to[0], c.to[1]), c.type)
        for (v in arr(e.violations)) board.violations.set(cableKey(v.fromNode, v.fromPort, v.toNode, v.toPort), if (truthy(v.detail)) v.detail else v.rule)
        if (loadDocument && truthy(e.document)) {
            val was = autosave; autosave = false
            try { load(fromConfix(e.document)) } finally { autosave = was }
        }
        val typed = arr(js("Array.from")(board.cables.values())).count { it != null }
        val violations = num(board.violations.size).toInt()
        val line = "from the board: $typed cable" + (if (typed == 1) "" else "s") + " typed" + (if (violations > 0) " · $violations refused by the daemon" else "")
        val st = q("#status"); st.textContent = (if (!st.textContent.isNullOrEmpty()) st.textContent + " · " else "") + line
        redraw()
        return true
    }

    fun applyProgramDelta(key: String, value: dynamic) {
        val name = key.substring("lcnc/program/".length)
        val cur = currentProgramName()
        if (cur.isEmpty() || !(name == cur || name.replace(Regex("^preset-"), "") == cur || "preset-$cur" == name)) return
        applyBoardEntry(name, value, true)
    }

    suspend fun loadBoardProgram(name: String): Boolean {
        val resp = borg.trikeshed.web.fetchJs("/api/panels/" + encodeURIComponent(name) + "?entry=1")
        if (!truthy(resp.ok)) return false
        val entry = borg.trikeshed.web.awaitJs(resp.json())
        panelName().value = name.replace(Regex("^preset-"), "")
        applyBoardEntry(name, entry, true)
        return true
    }

    suspend fun syncBoard(name: String?) {
        board = newBoard(if (name.isNullOrEmpty()) null else name)
        if (name.isNullOrEmpty()) { redraw(); return }
        try {
            val resp = borg.trikeshed.web.fetchJs("/api/panels/" + encodeURIComponent(name) + "?entry=1")
            if (!truthy(resp.ok)) { redraw(); return }
            applyBoardEntry(name, borg.trikeshed.web.awaitJs(resp.json()))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: dynamic) {}
        redraw()
    }

    override fun redraw() {
        // The backdrop is grown to fit, from coordinates already in hand rather than a reflow per node.
        val ends = ArrayList<dynamic>()
        if (growWireBox(emptyList())) applyWireBox()
        wiresSvg.innerHTML = ""
        redrawConcentric() // fractal spine + minimap ride every redraw
        val selected = harness.selected
        val wires = arr(graph.wires)
        wires.forEachIndexed { idx, wr ->
            val source = node(wr.from[0]); val target = node(wr.to[0])
            if (source != null && target != null && HarnessLandscape.visibleClosure(source) === HarnessLandscape.visibleClosure(target)) return@forEachIndexed
            val a = portCenter(str(wr.from[0]), "out", str(wr.from[1])); val b = portCenter(str(wr.to[0]), "in", str(wr.to[1]))
            if (a == null || b == null) return@forEachIndexed
            ends.add(a); ends.add(b)
            val path = svg("path")
            PatchNavigation.wireCurve(path, a, b, viewport, view); path.classList.add("live")
            val tc = PatchKinds.kindColor[if (source != null) portClass(source.type, "out", str(wr.from[1])) else ""]
            if (tc != null) path.style.stroke = tc
            if (str(wr.from[0]) in shake.starved) path.classList.add("starved")
            if (!wireKindOk(wr)) {
                path.classList.add("mismatch")
                val t = svg("title")
                t.textContent = "kind mismatch — this cable feeds nothing: " + arr(wr.from).joinToString(".") { str(it) } + " → " + arr(wr.to).joinToString(".") { str(it) }
                path.appendChild(t)
            }
            val bk = cableKey(wr.from[0], wr.from[1], wr.to[0], wr.to[1])
            val refused = board.violations.get(bk)
            if (truthy(refused)) {
                path.classList.add("refused")
                val t = svg("title"); t.textContent = "refused by the daemon: " + str(refused); path.appendChild(t)
            }
            val mutable = { source?._program == harness.selected && target?._program == harness.selected }
            if (!mutable()) path.style.pointerEvents = "none"
            on(path, "pointerdown", { e -> if (mutable()) { e.stopPropagation(); graph.wires.splice(idx, 1); redraw(); save() } })
            wiresSvg.appendChild(path)
            if (truthy(board.cables.has(bk)) && hypot(num(a.x) - num(b.x), num(a.y) - num(b.y)) * num(view.z) > 140 && kotlin.math.abs(num(a.x) - num(b.x)) > 80) {
                val ty = board.cables.get(bk)
                val label = svg("text")
                label.classList.add("wiretype"); if (truthy(refused)) label.classList.add("refused")
                if (tc != null && !truthy(refused)) label.style.fill = tc
                label.setAttribute("x", (num(a.x) + num(b.x)) / 2); label.setAttribute("y", (num(a.y) + num(b.y)) / 2 - 4); label.setAttribute("text-anchor", "middle")
                label.textContent = if (ty == null) "unresolved" else ty
                val tt = svg("title")
                tt.textContent = (if (ty == null) "unresolved on the board" else "typed on the board: " + str(ty)) + (if (truthy(refused)) " — refused: " + str(refused) else ""); label.appendChild(tt)
                wiresSvg.appendChild(label)
            }
        }
        val projected = nodes().filter { it._program == selected }
        for (link in scopeBindingLinks(projected, wires)) {
            if (truthy(link.scope.collapsed) || !truthy(link.child.el?.offsetWidth) ||
                HarnessLandscape.visibleClosure(link.child) !== link.child || HarnessLandscape.visibleClosure(link.scope) !== link.scope) continue
            val a = portCenterOf(link.from); val b = portCenterOf(link.to); if (a == null || b == null) continue
            ends.add(a); ends.add(b)
            val path = svg("path")
            PatchNavigation.wireCurve(path, a, b, viewport, view); path.classList.add("scope-binding", link.kind)
            path.dataset.scope = link.scope.id; path.dataset.child = link.child.id
            val title = svg("title"); title.textContent = link.label; path.appendChild(title); wiresSvg.appendChild(path)
        }
        // endpoints are the truth: a cable into a deeply nested, zoomed ring can sit outside the prediction
        if (ends.isNotEmpty() && growWireBox(ends)) applyWireBox()
        shake.positionVerdicts()
        if (dragWire?.repaint != null) dragWire.repaint()
    }

    // ── minimap: world overview + click-to-jump ──
    fun drawMinimap() {
        mmx.clearRect(0, 0, 168, 112)
        // fractal containment: one inset frame per dive level — the crumb, drawn
        val depth = num(diveStack.length).toInt()
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

    /** Frame every top-level node; on the blackboard the Harness owns the fit. */
    fun fitView() {
        camera.killMomentum() // a fit is a hard cut, never a glide target
        if (ready) { harness.fit(false); return }
        val tops = nodes().filter { !truthy(it._parentScope) && truthy(it.el) }
        if (tops.isEmpty()) return
        var x0 = Double.POSITIVE_INFINITY; var y0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        for (n in tops) {
            x0 = minOf(x0, num(n.x)); y0 = minOf(y0, num(n.y))
            x1 = maxOf(x1, num(n.x) + (if (truthy(n.el.offsetWidth)) num(n.el.offsetWidth) else 190.0))
            y1 = maxOf(y1, num(n.y) + (if (truthy(n.el.offsetHeight)) num(n.el.offsetHeight) else 90.0))
        }
        val r = viewport.getBoundingClientRect(); val pad = 48.0
        val z = minOf(2.5, maxOf(.08, minOf((r.width - pad * 2) / maxOf(1.0, x1 - x0), (r.height - pad * 2) / maxOf(1.0, y1 - y0))))
        view.z = z
        view.x = (r.width - (x1 - x0) * z) / 2 - x0 * z
        view.y = (r.height - (y1 - y0) * z) / 2 - y0 * z
        camera.applyView(); camera.saveCameraSoon()
    }

    /** dblclick/right-click on a ring's EMPTY interior adds into that ring */
    fun scopeAtTarget(t: dynamic): dynamic {
        if (t == null || t.classList == null) return null
        if (truthy(t.classList.contains("childgrid"))) return nodes().firstOrNull { it._childHost === t }
        if (truthy(t.classList.contains("ringworld"))) return nodes().firstOrNull { it._ringWorld === t }
        return null
    }

    fun menuTargetScope(e: dynamic): dynamic = scopeAtTarget(e.target)

    fun jsonArray(list: List<dynamic>): dynamic = list.toTypedArray()
}

/** the minimap's own scale (the JS kept it on the canvas element) */
internal fun minimapJump(e: dynamic) {
    val p = HarnessPatch; val mm = p.mm
    if (!truthy(mm._s)) return
    val r = mm.getBoundingClientRect()
    val wx = ((num(e.clientX) - num(r.left)) - num(mm._ox)) / num(mm._s); val wy = ((num(e.clientY) - num(r.top)) - num(mm._oy)) / num(mm._s)
    p.view.x = -(wx * num(p.view.z)) + num(p.viewport.clientWidth) / 2; p.view.y = -(wy * num(p.view.z)) + num(p.viewport.clientHeight) / 2
    p.camera.applyView(); p.save(); p.drawMinimap()
}
