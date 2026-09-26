package borg.trikeshed.web.harness

import borg.trikeshed.landscape.Cubic
import borg.trikeshed.landscape.LandscapeCamera
import borg.trikeshed.landscape.LandscapeNavigation
import borg.trikeshed.landscape.Pt
import borg.trikeshed.web.PatchNavigation
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.w3c.dom.EventSource
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL
import org.w3c.dom.get
import org.w3c.dom.set
import org.w3c.dom.url.URLSearchParams
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val initialHash: String = js("typeof location === 'undefined' ? '' : location.hash") as String

/** View state only. Documents, vocabulary, run receipts and provenance come from the board (harness.js). */
@OptIn(DelicateCoroutinesApi::class)
object Harness {
    val surface: HarnessSurface = if (js("typeof location") == "undefined") HarnessSurface.Board else HarnessSurface.of(window.location.pathname)
    var epoch: dynamic = null
    var connectionGeneration = 0
    private class ViewEntry(val camera: dynamic, val focus: String, val node: dynamic)
    private val viewHistory = ArrayList<ViewEntry>()
    var focusKey = ""
    var viewNode: dynamic = null
    var terrainBookmark: dynamic = null
    var board: dynamic = js("Object.create(null)")
    var seq = 0.0
    var selected: String? = null
    var applying = false
    var dirty = false
    val events = ArrayList<dynamic>()
    val drafts = JsMap<String, dynamic>()
    var actors = JsMap<String, dynamic>()
    val positions = JsMap<String, dynamic>()
    val flashes = JsMap<String, Double>()
    val previews = JsMap<String, dynamic>()
    val mounts = JsMap<String, dynamic>()
    val baselines = JsMap<String, String>()
    val loadedCids = JsMap<String, dynamic>()
    var nextY = 0.0; var nextX = 0.0; var rowHeight = 0.0
    var ready = false; var live = false; var running = false
    var frame = 0
    var activeBounds: dynamic = js("({w:1600,h:900})")
    var connectionReport: dynamic = null
    var shaking = false
    var neighborKey: String? = null
    var neighborData: dynamic = null
    var neighborTicket = 0
    var neighborTimer: Int? = null
    var parentHandle: dynamic = null
    var parentRevision = 0
    var dragMoved = false
    var inspectionController: AbortController? = null
    var neighborController: AbortController? = null
    var sheetTicket = 0
    var sheetNode: dynamic = null
    private var narseseChains = ArrayList<Pair<HTMLElement, HTMLElement>>()
    private var viewSave = 0
    private var flashSweep: Int? = null
    var stream: EventSource? = null
    var reconnectTimer = 0

    private val viewport: HTMLElement get() = byId("viewport")
    private val world: HTMLElement get() = byId("world")
    private val view: dynamic get() = Patch.view
    private val landscape: dynamic get() = Patch.Landscape

    private fun entryOf(name: String?): dynamic =
        previews.get(name!!) ?: board[HarnessKeys.PROGRAM + name] ?: obj { it.document = drafts.get(name) }

    fun message(value: String) { q("#status").textContent = value }

    fun connectionStatus(value: String) {
        val e = q("#connection"); if (e.textContent != value) e.textContent = value; e.title = value
    }

    fun inspectionOnly(name: String? = selected): Boolean {
        val doc: dynamic = drafts.get(name.toString()) ?: previews.get(name.toString())?.document ?: board[HarnessKeys.PROGRAM + name]?.document
        return doc?.controls?.inspectionOnly == true
    }

    fun example(name: String): Boolean {
        val entry = board[HarnessKeys.PROGRAM + name]
        return previews.has(name) || (entry?.sourceKind == "preset" && entry.programCid == entry.sourceCid)
    }

    fun programs(): List<String> {
        val set = LinkedHashSet<String>()
        for (k in keysOf(board)) if (k.startsWith(HarnessKeys.PROGRAM) && truthy(board[k]?.document) && !example(HarnessKeys.programName(k))) set.add(k)
        for (n in drafts.keyList() + previews.keyList()) set.add(HarnessKeys.PROGRAM + n)
        return set.sorted()
    }

    fun openExample(name: String, doc: dynamic): Boolean {
        if (truthy(board[HarnessKeys.PROGRAM + name]?.document) && !example(name)) return select(name)
        val preview: dynamic = jsObject(); preview.name = name; preview.document = doc; preview.sourceKind = "preset"
        previews.set(name, preview)
        return select(name)
    }

    fun changed() {
        if (applying || !ready) return
        if (truthy(connectionReport)) { connectionReport = null; q("#connections").hidden = true; Patch.clearVerdicts() }
        dirty = JSON.stringify(document()) != baselines.get(selected!!)
        if (dirty) drafts.set(selected!!, document()) else drafts.delete(selected!!)
        q("#runBtn").unsafeCast<HTMLButtonElement>().disabled = dirty || running || inspectionOnly()
        if (dirty) message("Unpublished changes in $selected")
        schedule()
    }

    fun document(name: String? = selected): dynamic {
        val entry = entryOf(name)
        val origin: dynamic = mounts.get(name!!) ?: js("({x:0,y:0})")
        val owned = Patch.nodes().filter { it._program == name }
        val ids = LinkedHashMap<String, String>()
        for (n in owned) ids[n.id as String] = (if (truthy(n._localId)) n._localId else n.id) as String
        val original = HashMap<String, dynamic>()
        fun walk(nodes: dynamic) { if (!truthy(nodes)) return; for (n in nodes.unsafeCast<Array<dynamic>>()) { original[n.id.toString()] = n; walk(n.children) } }
        walk(entry?.document?.nodes)
        // A mount translation must not round-trip authored coordinates through
        // floating-point subtraction. Reuse its exact local values until edited.
        fun coordinate(n: dynamic, axis: String): dynamic =
            if (truthy(n._parentScope)) n[axis] else if (n._placement != null && n._placement[axis] == n[axis]) n._placement.local[axis] else n[axis] - origin[axis]
        fun node(n: dynamic): dynamic {
            val source: dynamic = ids[n.id as String]?.let { original[it] }
            val o: dynamic = js("Object.assign({}, source)")
            js("Object.assign")(o, Patch.nodeDoc(n))
            o.id = ids[n.id as String]; o.x = coordinate(n, "x"); o.y = coordinate(n, "y")
            o.children = (if (truthy(n.children)) n.children.unsafeCast<Array<dynamic>>() else emptyArray()).map { node(it) }.toTypedArray()
            return o
        }
        val out: dynamic = js("Object.assign({}, entry == null ? undefined : entry.document)")
        out.nodes = owned.filter { !truthy(it._parentScope) }.map { node(it) }.toTypedArray()
        out.wires = Patch.wires().filter { ids.containsKey(it.from[0]) && ids.containsKey(it.to[0]) }.map { w ->
            val o: dynamic = jsObject()
            o.from = arrayOf<dynamic>(ids[w.from[0]], w.from[1]); o.to = arrayOf<dynamic>(ids[w.to[0]], w.to[1]); o
        }.toTypedArray()
        val docSeq: dynamic = entry?.document?.seq
        out.seq = (listOf(if (truthy(docSeq)) jsNumber(docSeq) else 1.0) + ids.values.map { HarnessKeys.seqFloor(it) }).fold(Double.NEGATIVE_INFINITY) { a, b -> js("Math.max")(a, b) as Double }
        return out
    }

    fun select(name: String, focus: Boolean = true, preserveParent: Boolean = false): Boolean {
        if (example(name) && !previews.has(name) && !drafts.has(name)) { message("Choose this example from the palette to open it"); return false }
        val entry = entryOf(name)
        if (!truthy(entry?.document)) { message("Program is not on the board: $name"); return false }
        if (selected != null && dirty) drafts.set(selected!!, document())
        val changedMain = selected != name
        selected = name
        if (surface == HarnessSurface.Panels) {
            for (mounted in mounts.keyList()) if (mounted != name) unmount(mounted)
            nextX = 0.0; nextY = 0.0; rowHeight = 0.0
        }
        if (changedMain || !preserveParent) setParent(null)
        if (connectionReport?.program != name) { connectionReport = null; q("#connections").hidden = true; Patch.clearVerdicts() }
        if (!mounts.has(name)) mount(name)
        if (changedMain) { Patch.UNDO.length = 0; Patch.REDO.length = 0; Patch.lastDoc = JSON.stringify(document()); Patch.histButtons() }
        q("#panelName").unsafeCast<HTMLInputElement>().value = if (previews.has(name)) name.replaceFirst(Regex("^preset-"), "") else name
        q("#programSelect").unsafeCast<HTMLSelectElement>().value = name
        dirty = drafts.has(name)
        val run = q("#runBtn").unsafeCast<HTMLButtonElement>()
        run.disabled = dirty || running || inspectionOnly()
        run.title = if (inspectionOnly()) "Inspection-only wiring specimen" else "Run selected program"
        render()
        if (focus) fit(false)
        message(if (previews.has(name)) "Example: $name · not saved" else name + (if (dirty) " has unpublished changes" else " on the blackboard"))
        val url = URL(window.location.href); url.pathname = surface.pathname
        url.searchParams.delete("load"); url.searchParams.delete("example"); url.searchParams.set(if (previews.has(name)) "example" else "load", name)
        window.history.replaceState(null, "", url.href)
        return true
    }

    fun setParent(node: dynamic) {
        val next: dynamic = jsObject(); next.program = selected
        next.nodeId = if (truthy(node?._localId)) node._localId else if (truthy(node?.id)) node.id else null
        if (parentHandle?.program != next.program || parentHandle?.nodeId != next.nodeId) parentRevision++
        parentHandle = next
        refreshParent()
    }

    fun selectParent(node: dynamic) {
        if (truthy(node) && node._program != selected && !select(node._program as String, false)) return
        setParent(if (truthy(node?._childHost)) node else if (truthy(node?._parentScope)) node._parentScope else null)
    }

    class ParentTarget(val handle: dynamic, val node: dynamic, val nodes: Array<dynamic>, val origin: dynamic) {
        fun js(): dynamic { val o: dynamic = jsObject(); o.handle = handle; o.node = node; o.nodes = nodes; o.origin = origin; return o }
    }

    fun parentTarget(): ParentTarget? {
        val handle: dynamic = if (parentHandle?.program == selected && parentHandle != null) parentHandle else obj { it.program = selected; it.nodeId = null }
        val node: dynamic = if (handle.nodeId == null) null else Patch.nodes().firstOrNull {
            it._program == handle.program && (if (truthy(it._localId)) it._localId else it.id) == handle.nodeId && truthy(it._childHost)
        }
        if (!truthy(handle.program) || (handle.nodeId != null && !truthy(node))) return null
        val nodes: Array<dynamic> = if (truthy(node)) (if (truthy(node.children)) node.children.unsafeCast<Array<dynamic>>() else emptyArray())
            else Patch.nodes().filter { it._program == handle.program && !truthy(it._parentScope) }.toTypedArray()
        val origin: dynamic = if (truthy(node)) js("({x:0,y:0})") else mounts.get(handle.program as String) ?: js("({x:0,y:0})")
        return ParentTarget(handle, node, nodes, origin)
    }

    fun refreshParent() {
        val target = parentTarget()
        if (target == null) {
            if (selected != null && parentHandle?.nodeId != null) { message("Selected scope removed; parent is now $selected"); setParent(null) }
            if (selected == null) {
                val grip = q("#parentHandle"); grip.textContent = "No program selected"; grip.title = "Select or create a program"; grip.setAttribute("aria-label", "No program selected")
            }
            return
        }
        for (n in Patch.nodes()) n.el?.classList?.toggle("selected-parent", n === target.node)
        val labels = ArrayList<String>()
        var n: dynamic = target.node
        while (truthy(n)) { labels.add(0, jsString(if (truthy(n._localId)) n._localId else n.id)); n = n._parentScope }
        val label = (listOf(jsString(target.handle.program)) + labels).joinToString(" / ")
        val grip = q("#parentHandle"); grip.textContent = "⠿ $label"
        grip.title = "Drag selected parent: $label (or Meta-drag anywhere in the landscape)"; grip.setAttribute("aria-label", "Selected parent: $label")
        for ((id, verb) in listOf("fitBtn" to "Fit", "fdBtn" to "Layout", "shakeBtn" to "Shake")) q("#$id").title = "$verb selected parent: $label"
    }

    fun dragParent(event: dynamic, leaf: dynamic = null) {
        if (event.button != 0) return
        val target = parentTarget() ?: return
        event.preventDefault(); event.stopPropagation(); dragMoved = false
        if (Patch.present("killMomentum")) Patch.killMomentum()
        val node: dynamic = if (truthy(leaf)) leaf else target.node
        val nodes: Array<dynamic> = if (truthy(node)) arrayOf(node) else target.nodes
        val scale = jsNumber(view.z) * (if (truthy(node)) Patch.ringScaleOf(node) else 1.0)
        val sx = jsNumber(event.clientX); val sy = jsNumber(event.clientY)
        val anchor: dynamic = if (truthy(node)) null else target.origin
        val origin: dynamic = if (truthy(anchor)) obj { it.x = anchor.x; it.y = anchor.y } else null
        val locals: HashMap<String, dynamic>? = if (truthy(anchor)) HashMap<String, dynamic>().also { m ->
            for (n in document(target.handle.program as String).nodes.unsafeCast<Array<dynamic>>()) m[jsString(n.id)] = obj { it.x = n.x; it.y = n.y }
        } else null
        class Start(val n: dynamic, val x: dynamic, val y: dynamic, val placement: dynamic, val local: dynamic)
        val starts = nodes.map { n -> Start(n, n.x, n.y, n._placement, locals?.get(jsString(if (truthy(n._localId)) n._localId else n.id))) }
        val revision = parentRevision
        val allNodes = { Patch.nodes() }
        fun stale(): Boolean = parentRevision != revision || starts.any { s -> !allNodes().contains(s.n) }
        lateinit var move: (Event) -> Unit
        lateinit var up: (Event) -> Unit
        lateinit var cancel: (Event) -> Unit
        fun finish(cancelledIn: Boolean) {
            val cancelled = cancelledIn || stale()
            window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up); window.removeEventListener("pointercancel", cancel)
            if (cancelled) {
                if (truthy(anchor)) js("Object.assign")(anchor, origin)
                for (s in starts) { s.n.x = s.x; s.n.y = s.y; s.n._placement = s.placement; s.n.el.style.left = "${s.x}px"; s.n.el.style.top = "${s.y}px" }
            } else if (dragMoved && truthy(node)) { Patch.resizeParentFrames(node._parentScope); Patch.save() }
            Patch.redraw(); schedule()
        }
        move = { ev ->
            val e: dynamic = ev
            if (stale()) finish(true)
            else if (dragMoved || hypot(jsNumber(e.clientX) - sx, jsNumber(e.clientY) - sy) >= 3) {
                dragMoved = true
                val dx = (jsNumber(e.clientX) - sx) / scale; val dy = (jsNumber(e.clientY) - sy) / scale
                if (truthy(anchor)) { anchor.x = origin.x + dx; anchor.y = origin.y + dy }
                for (s in starts) {
                    s.n.x = s.x + dx; s.n.y = s.y + dy
                    if (truthy(s.n._parentScope)) { s.n.x = max(0.0, jsNumber(s.n.x)); s.n.y = max(0.0, jsNumber(s.n.y)) }
                    if (truthy(anchor)) s.n._placement = obj { it.x = s.n.x; it.y = s.n.y; it.local = s.local }
                    s.n.el.style.left = "${s.n.x}px"; s.n.el.style.top = "${s.n.y}px"
                }
                Patch.redraw(); schedule()
            }
        }
        up = { finish(false) }
        cancel = { finish(true) }
        window.addEventListener("pointermove", move); window.addEventListener("pointerup", up); window.addEventListener("pointercancel", cancel)
    }

    fun mount(name: String, doc: dynamic = null) {
        if (surface == HarnessSurface.Panels && name != selected) return
        if (example(name) && !previews.has(name) && !drafts.has(name)) return
        val entry = entryOf(name)
        if (!truthy(entry?.document)) return
        if (connectionReport?.program == name) { connectionReport = null; q("#connections").hidden = true; Patch.clearVerdicts() }
        val previous = applying; applying = true
        try {
            var anchor: dynamic = mounts.get(name)
            val fresh = anchor == null
            if (anchor == null) { anchor = js("({})"); anchor.x = nextX; anchor.y = nextY; anchor.w = 1550; anchor.h = 720; mounts.set(name, anchor) }
            val oldIds = Patch.nodes().filter { it._program == name }.map { it.id }.toSet()
            for (n in Patch.nodes()) if (n.id in oldIds) { if (truthy(n._timer)) window.clearInterval(n._timer as Int); n._es?.close(); n.el?.remove() }
            Patch.G.nodes = Patch.nodes().filter { it.id !in oldIds }.toTypedArray()
            Patch.G.wires = Patch.wires().filter { it.from[0] !in oldIds && it.to[0] !in oldIds }.toTypedArray()
            dropCables(name, cablesOnly = false)
            fun id(local: dynamic) = "$name::" + jsString(local)
            fun clone(source: dynamic, parent: dynamic): dynamic {
                if (!truthy(Patch.CONTRACTS[source.type])) return null
                val n: dynamic = js("Object.assign({}, source)")
                n.id = id(source.id); n._localId = source.id; n._program = name; n._parentScope = parent
                n.params = js("Object.assign({}, source.params)"); n.children = arrayOf<dynamic>()
                n.x = (if (truthy(source.x)) source.x else 0) + (if (truthy(parent)) 0 else anchor.x)
                n.y = (if (truthy(source.y)) source.y else 0) + (if (truthy(parent)) 0 else anchor.y)
                Patch.G.nodes.push(n); Patch.buildNode(n)
                n.children = (if (truthy(source.children)) source.children.unsafeCast<Array<dynamic>>() else emptyArray()).map { clone(it, n) }.filter { truthy(it) }.toTypedArray()
                return n
            }
            val d: dynamic = if (truthy(doc)) doc else drafts.get(name) ?: entry.document
            for (n in (if (truthy(d.nodes)) d.nodes.unsafeCast<Array<dynamic>>() else emptyArray())) clone(n, null)
            for (n in Patch.nodes().filter { it._program == name && truthy(it._childHost) }) Patch.refreshRingChrome(n)
            for (n in Patch.nodes().filter { it._program == name }) ArchiveUI.decorate(n)
            for (n in Patch.nodes().filter { it._program == name && truthy(it._childHost) && !truthy(it._parentScope) }) Patch.layoutRing(n, truthy(doc) || drafts.has(name))
            Patch.resolveTopLevelOverlaps()
            val allIds = Patch.nodes().filter { it._program == name }.map { it.id }.toSet()
            for (wire in Patch.fromConfix(d).wires.unsafeCast<Array<dynamic>>())
                if (id(wire.from[0]) in allIds && id(wire.to[0]) in allIds)
                    Patch.G.wires.push(obj { it.from = arrayOf<dynamic>(id(wire.from[0]), wire.from[1]); it.to = arrayOf<dynamic>(id(wire.to[0]), wire.to[1]) })
            for (c in (if (truthy(entry.cables)) entry.cables.unsafeCast<Array<dynamic>>() else emptyArray()))
                Patch.BOARD.cables.set(cableKey(id(c.from[0]), c.from[1], id(c.to[0]), c.to[1]), c.type)
            for (v in (if (truthy(entry.violations)) entry.violations.unsafeCast<Array<dynamic>>() else emptyArray()))
                Patch.BOARD.violations.set(cableKey(id(v.fromNode), v.fromPort, id(v.toNode), v.toPort), if (truthy(v.detail)) v.detail else v.rule)
            js("Object.assign")(anchor, bounds(name))
            if (fresh) {
                // Normalize only the mount origin; document-local coordinates survive.
                for (n in Patch.nodes().filter { it._program == name && !truthy(it._parentScope) }) {
                    n.x -= anchor.left; n.y -= anchor.top; n.el.style.left = "${n.x}px"; n.el.style.top = "${n.y}px"
                }
                anchor.x -= anchor.left; anchor.y -= anchor.top
                if (mounts.size == 1) { nextY = jsNumber(anchor.y + anchor.top + anchor.h + 180); nextX = 0.0 }
                else {
                    nextX = jsNumber(anchor.x + anchor.left + anchor.w + 120); rowHeight = max(rowHeight, jsNumber(anchor.h))
                    if (nextX > 6500) { nextY += rowHeight + 180; nextX = 0.0; rowHeight = 0.0 }
                }
            }
            if (!truthy(doc) && !drafts.has(name)) baselines.set(name, JSON.stringify(document(name)))
            // The version this editor loaded: what publish() names as its base (Forge genesis, Cut C).
            if (!truthy(doc)) loadedCids.set(name, board[HarnessKeys.PROGRAM + name]?.programCid ?: null)
        } finally { applying = previous }
    }

    private fun cableKey(fromNode: dynamic, fromPort: dynamic, toNode: dynamic, toPort: dynamic): String =
        jsString(fromNode) + "." + jsString(fromPort).replaceFirst("?", "") + ">" + jsString(toNode) + "." + jsString(toPort).replaceFirst("?", "")

    private fun dropCables(name: String, cablesOnly: Boolean) {
        for (table in listOf(Patch.BOARD.cables, Patch.BOARD.violations)) {
            val keys = (js("Array").from(table.keys()) as Array<String>)
            for (key in keys) if (key.startsWith("$name::")) table.delete(key)
        }
    }

    fun replaceSelected(doc: dynamic) { mount(selected!!, doc); changed(); render() }

    fun unmount(name: String) {
        val ids = Patch.nodes().filter { it._program == name }.map { it.id }.toSet()
        for (n in Patch.nodes()) if (n.id in ids) { if (truthy(n._timer)) window.clearInterval(n._timer as Int); n._es?.close(); n.el?.remove() }
        Patch.G.nodes = Patch.nodes().filter { it.id !in ids }.toTypedArray()
        Patch.G.wires = Patch.wires().filter { it.from[0] !in ids && it.to[0] !in ids }.toTypedArray()
        mounts.delete(name); baselines.delete(name); loadedCids.delete(name)
        dropCables(name, cablesOnly = false)
    }

    fun schedule() {
        if (frame != 0) return
        frame = window.requestAnimationFrame { frame = 0; render() }
    }

    fun bounds(name: String? = selected): dynamic {
        val origin: dynamic = (if (name != null) mounts.get(name) else null) ?: js("({x:0,y:0})")
        val nodes = Patch.nodes().filter { !truthy(it._parentScope) && it._program == name }
        if (nodes.isEmpty()) return js("({left:0,top:0,w:320,h:180})")
        val left = nodes.minOf { jsNumber(it.x - origin.x) }; val top = nodes.minOf { jsNumber(it.y - origin.y) }
        val right = nodes.maxOf { jsNumber(it.x - origin.x) + jsNumber(if (truthy(it.el?.offsetWidth)) it.el.offsetWidth else 190) }
        val bottom = nodes.maxOf { jsNumber(it.y - origin.y) + jsNumber(if (truthy(it.el?.offsetHeight)) it.el.offsetHeight else 100) }
        val o: dynamic = jsObject(); o.left = left; o.top = top; o.w = right - left; o.h = bottom - top
        return o
    }

    fun programRight(): Double = mounts.valueList().fold(0.0) { m, a -> max(m, jsNumber(a.x + (if (truthy(a.left)) a.left else 0) + a.w)) }

    fun territory(key: String, title: String, count: String, x: Double, y: Double, w: Double, h: Double, tone: String): HTMLElement {
        val e = el("section", "territory")
        e.dataset["territory"] = key
        e.style.left = "${x}px"; e.style.top = "${y}px"; e.style.width = "${w}px"; e.style.height = "${h}px"
        e.style.setProperty("--tone", tone)
        if (flashes.has(key)) e.classList.add("flash")
        val head = el("header")
        head.appendAll(el("h2", "", title), el("span", "", count))
        e.append(head)
        q("#territories").append(e)
        positions.set(key, obj { it.x = x; it.y = y; it.w = w; it.h = h })
        return e
    }

    private val ARCHIVE_CID = Regex("^sha256:[0-9a-f]{64}$")

    fun render() {
        if (!ready) return
        for (id in listOf("runBtn", "argumentsBtn", "storeSaveBtn", "fdBtn", "shakeBtn", "fitBtn")) q("#$id").unsafeCast<HTMLButtonElement>().disabled = selected == null
        q("#runBtn").unsafeCast<HTMLButtonElement>().disabled = selected == null || dirty || running || inspectionOnly()
        refreshParent()
        q("#territories").replaceKids()
        positions.clear()
        activeBounds = bounds()
        for ((name, anchor) in mounts.entryList()) {
            val box = bounds(name); js("Object.assign")(anchor, box)
            val archiveDoc: dynamic = drafts.get(name) ?: board[HarnessKeys.PROGRAM + name]?.document
            val archiveNodes: dynamic = archiveDoc?.nodes
            val archiveCid: dynamic = (if (archiveNodes == null) null else archiveNodes[0])?.params?.archiveCid
            val archive = jsTypeOf(archiveCid) == "string" && ARCHIVE_CID.matches(archiveCid as String) && inspectionOnly(name)
            val t = territory(HarnessKeys.PROGRAM + name, if (archive) "Archive " + (archiveCid as String).substring(7, 19) else name,
                if (drafts.has(name)) "Unpublished" else if (archive) "Stored archive" else HarnessKeys.PROGRAM + name,
                jsNumber(anchor.x + box.left - 24), jsNumber(anchor.y + box.top - 64), jsNumber(box.w + 48), jsNumber(box.h + 88), HarnessTerritory.PROGRAM_TONE)
            t.classList.toggle("archive-territory", archive)
            t.classList.add("active-program"); if (name == selected) t.classList.add("selected")
            t.classList.toggle("selected-parent", name == selected && !truthy(parentTarget()?.node))
            val head = t.querySelector("header").unsafeCast<HTMLElement>()
            val neighbors = el("button", "source-ref", "↔"); neighbors.title = "Neighbors of $name"; neighbors.setAttribute("aria-label", "Neighbors of $name")
            neighbors.addEventListener("click", { e -> e.stopPropagation(); inspect(HarnessKeys.PROGRAM + name) }); head.append(neighbors)
            head.addEventListener("pointerdown", { ev ->
                val e: dynamic = ev
                if (truthy(e.target.closest("button")) || e.button != 0) return@addEventListener
                if (name != selected) select(name, false) else setParent(null)
                dragParent(e)
            })
            head.addEventListener("dblclick", { fit(false) })
            val cid: dynamic = board[HarnessKeys.PROGRAM + name]?.programCid
            if (truthy(cid)) {
                val source = el("button", "source-ref", "◇"); source.title = "Program version " + jsString(cid); source.setAttribute("aria-label", "Inspect version of $name")
                source.dataset["relation"] = "reference"
                source.addEventListener("click", { e -> e.stopPropagation(); landscape.inspectCid(cid, HarnessKeys.PROGRAM + name) }); head.append(source)
            }
            val run = el("button", "run", "▶ Run").unsafeCast<HTMLButtonElement>()
            run.disabled = running || drafts.has(name) || inspectionOnly(name); run.setAttribute("aria-label", "Run $name")
            run.addEventListener("click", { e -> e.stopPropagation(); select(name, false); GlobalScope.launch { run() } })
            if (!archive) head.append(run)
        }
        val groups = LinkedHashMap<String, ArrayList<Pair<String, dynamic>>>()
        for (key in keysOf(board)) {
            val prefix = HarnessKeys.prefix(key)
            if (!key.startsWith(HarnessKeys.PROGRAM) || !mounts.has(HarnessKeys.programName(key))) groups.getOrPut(prefix) { ArrayList() }.add(key to board[key])
        }
        val side = programRight() + 100
        landscape.positionObjects()
        val runs = keysOf(board).map { it to board[it] }.filter { (k, v) -> k.startsWith(HarnessKeys.RUN) && v.program == selected }
            .sortedWith { a, b -> (order(b.second) - order(a.second)).let { if (it > 0) 1 else if (it < 0) -1 else 0 } }
        q("#cancelRun").unsafeCast<HTMLButtonElement>().disabled = !runs.any { (_, v) -> HarnessKeys.isActiveRunStatus(v.status) }
        val receipt = territory("receipts", "Run receipt", "${runs.size} runs", side, -80.0, 600.0, 350.0, HarnessTerritory.RECEIPT_TONE)
        if (runs.isNotEmpty()) {
            val (key, value) = runs[0]
            val inspectButton = el("button", "fact-row", jsString(value.status) + " / " + jsString(value.runId))
            inspectButton.addEventListener("click", { inspect(key) })
            val shown: dynamic = jsObject(); shown.inputs = value.inputs; shown.returns = value.returns; shown.error = value.error; shown.violations = value.violations
            receipt.appendAll(inspectButton, el("pre", "run-result" + (if (value.ok == false) " error" else ""), jsonString(shown, true)))
        } else receipt.append(el("p", "", "No recorded run for this program"))
        var y = 300.0
        for ((prefix, items) in groups.entries.sortedWith { a, b -> b.value.size - a.value.size }) {
            val height = HarnessTerritory.factHeight(items.size)
            val tone = HarnessTerritory.tone(prefix)
            if (prefix == "narsese") { y += narseseTerritory(items, side, y, tone) + 24; continue }
            val t = territory(prefix, prefix, "${items.size} entries", side, y, 600.0, height, tone)
            sheetButton(t, prefix)
            if (items.size > HarnessTerritory.FACT_FIELD_THRESHOLD) {
                val field = el("div", "fact-field")
                for ((key, value) in items) {
                    val cell = el("button", "fact-cell")
                    cell.dataset["key"] = key
                    cell.title = key + "\n" + summary(value)
                    cell.setAttribute("aria-label", key)
                    if (flashes.has(key)) cell.classList.add("flash")
                    cell.addEventListener("click", { inspect(key) })
                    field.append(cell)
                }
                t.append(field)
            } else for ((key, value) in items) {
                val row = el("button", "fact-row")
                row.dataset["key"] = key
                row.appendAll(el("b", "", key.split("/").drop(1).joinToString("/")), el("span", "", summary(value)))
                row.title = key
                if (flashes.has(key)) row.classList.add("flash")
                row.addEventListener("click", { inspect(key) })
                t.append(row)
            }
            y += height + 24
        }
        val programKeys = programs()
        val actorValues = LinkedHashMap<String, Pair<IntArray, LinkedHashSet<String>>>()
        for (key in keysOf(board)) {
            val value = board[key]
            val actor: dynamic = actors.get(key) ?: (if (isObject(value)) value.actor else null)
            if (!truthy(actor)) continue
            val data = actorValues.getOrPut(jsString(actor)) { IntArray(1) to LinkedHashSet() }
            data.first[0]++; data.second.add(HarnessKeys.prefix(key))
        }
        val agents = territory("actors", "Actors", "${actorValues.size} observed", side + 630, -80.0, 420.0, max(210.0, 70.0 + actorValues.size * 68), HarnessTerritory.ACTOR_TONE)
        for ((name, data) in actorValues) {
            val row = el("div", "actor-row", name)
            row.append(el("small", "", "${data.first[0]} entries / " + data.second.joinToString(", ")))
            agents.append(row)
        }
        val vocab: Array<dynamic> = board[HarnessKeys.VOCABULARY]?.contracts ?: emptyArray()
        val vocabulary = territory("vocabulary", "Vocabulary", "${vocab.size} contracts", side + 630, max(180.0, actorValues.size * 68.0), 420.0, 620.0, HarnessTerritory.VOCABULARY_TONE)
        val families = LinkedHashMap<String, Int>()
        for (c in vocab) { val f = (c.type as String).split(".")[0]; families[f] = (families[f] ?: 0) + 1 }
        val buttons = el("div", "vocab-groups")
        for ((family, count) in families.entries.map { it.key to it.value }.sortedBy { "${it.first},${it.second}" }) {
            val button = el("button", "", "$family $count")
            button.addEventListener("click", {
                GlobalScope.launch {
                    (Patch.buildPalette() as Promise<dynamic>).await()
                    q("#palette").classList.add("open")
                    val input = q("#palette input").unsafeCast<HTMLInputElement>(); input.value = family; input.dispatchEvent(Event("input"))
                }
            })
            buttons.append(button)
        }
        vocabulary.append(buttons)
        if (jsNumber(landscape.rows.length) > 0) {
            val box = landscape.objectBox
            territory("objects", "Graal objects", jsString(landscape.rows.length) + " objects", jsNumber(box.x), jsNumber(box.y) - 60, jsNumber(box.w), jsNumber(box.h) + 60, HarnessTerritory.OBJECT_TONE).classList.add("objects")
        }
        val nav = q("#boardnav"); nav.replaceKids()
        for ((key, box) in positions.entryList()) {
            if (key.startsWith(HarnessKeys.PROGRAM) && key != HarnessKeys.PROGRAM + selected) continue
            val button = el("button", "", if (key.startsWith(HarnessKeys.PROGRAM)) selected else key)
            button.addEventListener("click", { focus(box) }); nav.append(button)
        }
        val select = q("#programSelect").unsafeCast<HTMLSelectElement>()
        val options = select.options
        fun optionAt(i: Int): dynamic = options.asDynamic()[i]
        val label = { k: String -> (if (previews.has(HarnessKeys.programName(k))) "Example: " else "") + HarnessKeys.programName(k) }
        if (optionAt(0)?.value != "" || options.length != programKeys.size + 1 ||
            programKeys.withIndex().any { (i, k) -> optionAt(i + 1)?.value != HarnessKeys.programName(k) || optionAt(i + 1)?.textContent != label(k) }) {
            val empty = el("option", "", if (programKeys.isNotEmpty()) "Select a program" else "No saved programs"); empty.asDynamic().value = ""
            select.replaceKids(empty, *programKeys.map { key ->
                val name = HarnessKeys.programName(key)
                val option = el("option", "", if (previews.has(name)) "Example: $name" else name); option.asDynamic().value = name; option
            }.toTypedArray())
        }
        select.value = selected ?: ""
        landscape.refreshActivity(); renderEvents(); channels(if (runs.isNotEmpty()) runs[0].second else null)
        q("#boardCount").textContent = "${keysOf(board).size} entries"
        Patch.applyView(); Patch.redraw(); landscape.schedule()
    }

    private fun order(v: dynamic): Double = jsNumber(if (truthy(v.sequence)) v.sequence else if (truthy(v.startedAtMs)) v.startedAtMs else 0)

    /* Narsese on the board is shown as EXPRESSIONS and linked by their terms. A curation receipt
       carries the expression the minter knew (plus subject / object / relation when the bag knows
       them); a rete firing carries its antecedent and consequent. A row whose object (or consequent)
       is another row's subject (or antecedent) is a derivation step, and channels() draws that chain.
       A receipt with no expression is shown as exactly that, so the gap is visible instead of silent. */
    private fun field(value: dynamic, name: String): String? = if (isObject(value) && truthy(value[name])) jsString(value[name]) else null

    fun narseseExpression(value: dynamic): String? {
        if (!isObject(value)) return null
        return NarseseRows.expression(field(value, "expression"), field(value, "antecedent"), field(value, "consequent"), field(value, "subject"), field(value, "object"))
    }

    fun narseseTerms(value: dynamic): NarseseRows.Terms? {
        if (!isObject(value)) return null
        return NarseseRows.terms(field(value, "expression"), field(value, "antecedent"), field(value, "consequent"), field(value, "subject"), field(value, "object"))
    }

    private class NalRow(val key: String, val value: dynamic, val expression: String?, val terms: NarseseRows.Terms?) { var el: HTMLElement? = null }

    fun narseseTerritory(items: List<Pair<String, dynamic>>, x: Double, y: Double, tone: String): Double {
        val rows = items.map { (key, value) -> NalRow(key, value, narseseExpression(value), narseseTerms(value)) }
            .sortedWith { a, b ->
                val e = (if (a.expression != null) 0 else 1) - (if (b.expression != null) 0 else 1)
                if (e != 0) e else {
                    val r = NarseseRows.rank(a.value?.event) - NarseseRows.rank(b.value?.event)
                    if (r != 0) r else (js("(function(x,y){return x.localeCompare(y)})")(a.key, b.key) as Number).toInt()
                }
            }
        val shown = rows.count { it.expression != null }; val blind = rows.size - shown
        val visible = rows.take(HarnessTerritory.NARSESE_LIMIT); val rest = rows.drop(HarnessTerritory.NARSESE_LIMIT)
        val height = HarnessTerritory.narseseHeight(visible.size, rest.size)
        val t = territory("narsese", "narsese", "$shown expressions" + (if (blind > 0) " · $blind uncaptioned" else ""), x, y, 600.0, height, tone)
        sheetButton(t, "narsese")
        val byHead = HashMap<String, ArrayList<NalRow>>()
        narseseChains = ArrayList()
        for (r in visible) {
            val row = el("button", "fact-row nal-row" + (if (r.expression != null) "" else " blind"))
            row.dataset["key"] = r.key
            val v = r.value
            val tag = (if (truthy(v?.event)) jsString(v.event) else "") +
                (if (truthy(v?.relation)) " · " + jsString(v.relation.toLowerCase()) else "") +
                (if (jsTypeOf(v?.expectation) == "number") " · e=" + jsString(v.expectation.toFixed(2)) else "")
            row.appendAll(el("b", "", r.expression ?: ("∠ " + (if (truthy(v?.angular)) jsString(v.angular) else r.key) + " — no expression on this daemon")), el("span", "", tag))
            row.title = r.key
            if (flashes.has(r.key)) row.classList.add("flash")
            row.addEventListener("click", { inspect(r.key) })
            t.append(row)
            r.el = row
            if (r.terms != null) byHead.getOrPut(r.terms.head) { ArrayList() }.add(r)
        }
        for (r in visible) {
            if (r.terms == null) continue
            for (next in byHead[r.terms.tail] ?: emptyList()) if (next !== r) narseseChains.add(r.el!! to next.el!!)
        }
        if (rest.isNotEmpty()) {
            t.append(el("p", "nal-more", "${rest.size} more receipts"))
            val fieldEl = el("div", "fact-field")
            for (r in rest) {
                val cell = el("button", "fact-cell" + (if (r.expression != null) "" else " blind"))
                cell.dataset["key"] = r.key
                cell.title = (r.expression ?: "no expression") + "\n" + r.key; cell.setAttribute("aria-label", r.expression ?: r.key)
                if (flashes.has(r.key)) cell.classList.add("flash")
                cell.addEventListener("click", { inspect(r.key) })
                fieldEl.append(cell)
            }
            t.append(fieldEl)
        }
        return height
    }

    private fun pt(x: Double, y: Double) = Pt(x, y)

    private fun wire(path: dynamic, a: Pt, b: Pt, controls: List<Pt>? = null) {
        val c = controls ?: run { val dx = max(40.0, kotlin.math.abs(b.x - a.x) / 2); listOf(Pt(a.x + dx, a.y), Pt(b.x - dx, b.y)) }
        path._wireCurve = Cubic(a, c[0], c[1], b)
        PatchNavigation.projectCurve(path, jsNumber(viewport.clientWidth), jsNumber(viewport.clientHeight), view)
    }

    private fun svgChild(svg: dynamic, tag: String): dynamic = document.createElementNS(svg.namespaceURI as String?, tag)

    fun channels(receipt: dynamic) {
        val svg: dynamic = q("#channels"); svg.replaceChildren()
        // Term correspondence is an association, not evidence of a derivation.
        val w = world.getBoundingClientRect()
        val z = jsNumber(view.z)
        for (link in (landscape.activityLinks as Array<dynamic>)) {
            val from = positions.get(HarnessKeys.prefix(link.from as String)); val to = positions.get(HarnessKeys.prefix(link.to as String))
            if (from == null || to == null || from === to) continue
            val path = svgChild(svg, "path"); path.setAttribute("class", "activity-ref"); path.dataset.relation = "reference"
            val title = svgChild(svg, "title"); title.textContent = jsString(link.kind) + ": " + link.from + " → " + link.to + " (not causal support)"; path.append(title)
            wire(path, pt(jsNumber(from.x + from.w), jsNumber(from.y + 70)), pt(jsNumber(to.x + to.w), jsNumber(to.y + 70))); svg.append(path)
        }
        for ((from, to) in narseseChains) {
            val a = from.getBoundingClientRect(); val b = to.getBoundingClientRect()
            val ax = (a.right - w.left) / z; val ay = (a.top + a.height / 2 - w.top) / z; val bx = (b.right - w.left) / z; val by = (b.top + b.height / 2 - w.top) / z
            val path = svgChild(svg, "path"); path.setAttribute("class", "chain")
            path.dataset.relation = "association"; val title = svgChild(svg, "title"); title.textContent = "Term association, not causal support"; path.append(title)
            wire(path, pt(ax, ay), pt(bx, by), listOf(pt(ax + 70, ay), pt(bx + 70, by))); svg.append(path)
        }
        neighborChannels(svg)
        if (!truthy(receipt)) return
        val a = positions.get(jsString(receipt.programKey)); val b = positions.get("receipts"); if (a == null || b == null) return
        val path = svgChild(svg, "path"); path.dataset.relation = "execution"
        wire(path, pt(jsNumber(a.x + a.w), jsNumber(a.y + 160)), pt(jsNumber(b.x), jsNumber(b.y + 160))); svg.append(path)
        val label = svgChild(svg, "text"); label.setAttribute("x", jsString(a.x + a.w + 8)); label.setAttribute("y", jsString(a.y + 147)); label.textContent = receipt.status; svg.append(label)
    }

    fun summary(value: dynamic): String {
        if (jsTypeOf(value) == "string") return value as String
        if (!truthy(value) || jsTypeOf(value) != "object") return jsString(value)
        return keysOf(value).take(4).joinToString(" / ") { k -> val v = value[k]; k + ": " + (if (jsTypeOf(v) == "object") JSON.stringify(v) else jsString(v)) }.take(250)
    }

    fun beginInspection(key: String, actor: String, raw: String = ""): AbortSignal {
        inspectionController?.abort()
        val controller = AbortController(); inspectionController = controller
        sheetTicket++
        sheetNode = null
        neighborKey = null; neighborData = null; neighborTicket++
        neighborController?.abort()
        neighborTimer?.let { window.clearTimeout(it) }; neighborTimer = null
        q("#factNeighbors").hidden = true; q("#neighborResults").replaceKids()
        val refs = q("#factInspector").querySelectorAll(".terrain-ref")
        for (i in 0 until refs.length) refs.item(i).asDynamic().remove()
        q("#factKey").textContent = key; q("#factActor").textContent = actor
        q("#factValue").textContent = raw; q("#factSheet").replaceKids(); q("#sheetRoots").replaceKids()
        rawSheets(false)
        val dialog: dynamic = q("#factInspector")
        if (dialog.open != true) dialog.showModal()
        return controller.signal
    }

    private fun factInspectorAppend(e: HTMLElement) { q("#factInspector").append(e) }

    fun inspect(key: String) {
        val actor: dynamic = actors.get(key) ?: board[key]?.actor ?: ""
        beginInspection(key, jsString(actor), jsString(jsonString(board[key], true)))
        GlobalScope.launch { loadSheets(listOf(SheetSource("/blackboard/sheet?key=" + encodeURIComponent(key))), key) }
        neighborKey = key; q("#factNeighbors").hidden = false; q("#neighborKey").unsafeCast<HTMLInputElement>().value = key
        GlobalScope.launch { loadNeighbors() }
        val receipt: dynamic = board[key]
        for (link in (landscape.activityLinks as Array<dynamic>).filter { it.from == key || it.to == key }) {
            val target = (if (link.from == key) link.to else link.from) as String
            val button = el("button", "terrain-ref", jsString(link.kind) + " → " + target); button.dataset["relation"] = "reference"
            button.title = "Recorded identifier reference, not causal support"; button.addEventListener("click", { inspect(target) }); factInspectorAppend(button)
        }
        if (truthy(receipt?.programCid)) {
            val button = el("button", "terrain-ref", "Program version " + (receipt.programCid as String).take(16))
            button.addEventListener("click", { landscape.inspectCid(receipt.programCid) }); factInspectorAppend(button)
        }
        // A refused publish offers the two honest exits: take the board's version, or overwrite it knowingly.
        if (key.startsWith(HarnessKeys.PUBLISH) && receipt?.verdict == "refused") {
            val name = key.substring(13)
            val reload = el("button", "terrain-ref", "Reload board version (discard draft)")
            reload.addEventListener("click", {
                drafts.delete(name); dirty = false; unmount(name); mount(name); select(name, false); q("#factInspector").asDynamic().close()
            })
            factInspectorAppend(reload)
            val overwrite = el("button", "terrain-ref", "Overwrite " + jsString(receipt.currentCid).take(16))
            overwrite.addEventListener("click", { q("#factInspector").asDynamic().close(); GlobalScope.launch { publish(receipt.currentCid) } })
            factInspectorAppend(overwrite)
        }
        if (key.startsWith(HarnessKeys.SNAPSHOT) && truthy(receipt?.cid)) {
            val button = el("button", "terrain-ref", "Snapshot " + jsString(receipt.cid).take(16))
            button.addEventListener("click", { landscape.inspectCid(receipt.cid) }); factInspectorAppend(button)
        }
        if (key.startsWith(HarnessKeys.STALE) && truthy(receipt?.runId)) {
            val button = el("button", "terrain-ref", "Rebuild")
            button.addEventListener("click", { GlobalScope.launch { try { rebuild(receipt) } catch (e: Throwable) { message(errorMessage(e)) } } }); factInspectorAppend(button)
        }
    }

    fun openNeighbors() {
        val key = neighborKey ?: programs().firstOrNull { it == HarnessKeys.PROGRAM + selected } ?: keysOf(board).sorted().firstOrNull()
        if (key != null) inspect(key) else message("The blackboard is empty")
    }

    fun queueNeighbors() {
        if (neighborKey == null || neighborTimer != null) return
        neighborTimer = window.setTimeout({ neighborTimer = null; GlobalScope.launch { loadNeighbors() } }, 150)
    }

    suspend fun loadNeighbors() {
        val key = neighborKey ?: return
        val epochAt = epoch
        neighborController?.abort()
        val controller = AbortController(); neighborController = controller
        val signal = controller.signal
        val ticket = ++neighborTicket
        val host = q("#neighborResults"); val status = q("#neighborStatus")
        fun current() = ticket == neighborTicket && key == neighborKey && epochAt == epoch && !signal.aborted
        neighborData = null; host.replaceKids(); status.textContent = "Finding neighbors across the blackboard…"; schedule()
        val options = q("#neighborKeys"); options.replaceKids()
        for (node in keysOf(board).sorted()) { val option = el("option"); option.asDynamic().value = node; options.append(option) }
        if (!hasOwn(board, key)) { status.textContent = "This node was deleted."; schedule(); return }
        try {
            val response = fetchResponse("/blackboard/neighbors?key=" + encodeURIComponent(key), requestInit(signal = signal))
            if (!response.ok) throw Error(if (response.status.toInt() == 404) "This node was deleted." else "Neighbors unavailable (" + response.status + ")")
            val data = response.jsonValue(); if (!current()) return
            if (data.epoch != epochAt || jsNumber(data.revision) < seq) { queueNeighbors(); return }
            neighborData = data
            status.textContent = jsString(data.indexedKeys) + " nodes indexed · " + jsString(data.classifiedKeys) + " with SUMO concepts · " + jsString(data.corpus) + " corpus · revision " + jsString(data.revision) +
                (if (jsNumber(data.opaqueKeys.length) > 0) " · " + jsString(data.opaqueKeys.length) + " nodes have opaque values" else "")
            val neighbors = data.neighbors.unsafeCast<Array<dynamic>>()
            if (neighbors.isEmpty()) host.append(el("p", "sheet-note", "No supported neighbors in this snapshot."))
            for (neighbor in neighbors) {
                val row = el("button", "neighbor-row"); row.dataset["key"] = neighbor.key as String
                row.append(el("b", "", neighbor.key))
                val evidence = ArrayList<String>()
                if (jsNumber(neighbor.concepts.length) > 0) evidence.add("SUMO: " + neighbor.concepts.join(", "))
                if (jsNumber(neighbor.terms.length) > 0) evidence.add("Text: " + neighbor.terms.join(", "))
                if (jsNumber(neighbor.references.length) > 0) for (r in neighbor.references.unsafeCast<Array<dynamic>>()) evidence.add("Reference: " + jsString(r.from) + " → " + jsString(r.to))
                row.append(el("span", "", evidence.joinToString(" · ")))
                row.append(el("span", "", jsString(if (truthy(neighbor.provenance?.actor)) neighbor.provenance.actor else "unattributed") + " · similarity " + jsString(neighbor.score.toFixed(3))))
                row.title = "Shared concepts and text are associations; references retain their recorded direction."
                row.addEventListener("click", {
                    val nk = neighbor.key as String
                    val position = positions.get(nk) ?: positions.get(HarnessKeys.prefix(nk))
                    if (position != null) focus(position, nk)
                    inspect(nk)
                })
                host.append(row)
            }
            schedule()
        } catch (error: Throwable) { if (current()) { status.textContent = errorMessage(error); schedule() } }
    }

    fun neighborChannels(svg: dynamic) {
        val data = neighborData
        val elements = HashMap<String, HTMLElement>()
        val keyed = q("#territories").querySelectorAll("[data-key]")
        for (i in 0 until keyed.length) { val e = keyed.item(i).unsafeCast<HTMLElement>(); e.classList.remove("neighbor"); elements[e.dataset["key"]!!] = e }
        if (!truthy(data) || data.epoch != epoch || jsNumber(data.revision) < seq || !hasOwn(board, data.key as String)) return
        val box = world.getBoundingClientRect()
        val z = jsNumber(view.z)
        fun point(key: String): Pt? {
            val element = elements[key]
            if (element != null) { val r = element.getBoundingClientRect(); return Pt((r.right - box.left) / z, (r.top + r.height / 2 - box.top) / z) }
            val p = positions.get(key) ?: positions.get(HarnessKeys.prefix(key)); return if (p != null) Pt(jsNumber(p.x + p.w), jsNumber(p.y + 70)) else null
        }
        val from = point(data.key as String) ?: return
        for (neighbor in data.neighbors.unsafeCast<Array<dynamic>>()) {
            val nk = neighbor.key as String
            if (!hasOwn(board, nk)) continue
            val to = point(nk) ?: continue
            elements[nk]?.classList?.add("neighbor")
            val path = svgChild(svg, "path"); path.setAttribute("class", "neighbor-ref"); path.dataset.relation = "association"
            val title = svgChild(svg, "title"); title.textContent = jsString(data.key) + " ↔ " + nk + ": " + neighbor.concepts.concat(neighbor.terms).join(", "); path.append(title)
            wire(path, from, to); svg.append(path)
        }
    }

    /* Sheets. A fact, a territory, or the whole board opens as the grid-in-cell family
       /blackboard/sheet projects: rows are (key, value), a container value is a ▤ ref cell that
       drills in, the crumb climbs out. The kanban territory also carries kanban.activeSheets from
       /api/lcnc/kanban. The renderer is patch.js's renderConcentric on a synthetic node whose el is
       the dialog host; nothing here reshapes a sheet. */
    fun sheetButton(territory: HTMLElement, prefix: String) {
        val button = el("button", "sheets", "▤")
        button.title = "$prefix as sheets"; button.setAttribute("aria-label", "$prefix as sheets")
        button.addEventListener("click", { e -> e.stopPropagation(); openSheets(listOf(prefix)) })
        territory.querySelector("header")!!.append(button)
    }

    class SheetSource(val url: String, val kanban: Boolean = false)

    fun openSheets(prefixes: List<String>) {
        val list = if (prefixes.isNotEmpty()) prefixes else keysOf(board).map { HarnessKeys.prefix(it) }.toSet().sorted()
        val sources = ArrayList(list.map { p -> SheetSource("/blackboard/sheet?prefix=" + encodeURIComponent(p) + "&max=1024") })
        if ("kanban" in list) sources.add(SheetSource("/api/lcnc/kanban", true))
        beginInspection((if (prefixes.isNotEmpty()) list.joinToString(" · ") else "blackboard") + " as sheets", "")
        GlobalScope.launch { loadSheets(sources, if ("kanban" in list) "board" else list.firstOrNull()) }
    }

    suspend fun loadSheets(sources: List<SheetSource>, cur: String?) {
        val host = q("#factSheet")
        val ticket = ++sheetTicket
        host.replaceKids(el("p", "sheet-note", "Projecting sheets"))
        q("#sheetRoots").replaceKids(); rawSheets(false)
        val idx: dynamic = jsObject(); val notes = ArrayList<String>(); val continuations = ArrayList<String>(); var orch: dynamic = null
        coroutineScope {
            sources.map { src ->
                async {
                    try {
                        val r = fetchResponse(src.url, requestInit(signal = inspectionController?.signal))
                        if (!r.ok) throw Error(jsString(r.status) + " on " + src.url)
                        val j: dynamic = JSON.parse((landscape.readText(r) as Promise<String>).await())
                        val sheets: dynamic = if (src.kanban) {
                            val list = arrayListOf<dynamic>(j.board)
                            if (truthy(j.byStatus)) list.addAll(j.byStatus.unsafeCast<Array<dynamic>>())
                            if (truthy(j.byPriority)) list.addAll(j.byPriority.unsafeCast<Array<dynamic>>())
                            list.toTypedArray()
                        } else j
                        if (!src.kanban && truthy(j[0]?.nextKey)) {
                            val url = URL(src.url, window.location.href); url.searchParams.set("after", j[0].nextKey as String); url.searchParams.set("revision", jsString(j[0].boardRevision))
                            continuations.add(url.pathname + url.search)
                        }
                        if (src.kanban) orch = if (truthy(j.orchestration)) j.orchestration else null
                        if (truthy(sheets)) for (s in sheets.unsafeCast<Array<dynamic>>()) if (truthy(s) && truthy(s.id)) idx[s.id] = s
                    } catch (e: Throwable) { notes.add(errorMessage(e)) }
                }
            }.awaitAll()
        }
        if (ticket != sheetTicket) return
        if (keysOf(idx).isEmpty()) {
            host.replaceKids(el("p", "sheet-note error", "No sheets: " + notes.joinToString("; ").ifEmpty { "empty projection" }))
            if (!q("#factValue").textContent.isNullOrEmpty()) rawSheets(true)
            return
        }
        host.replaceKids()
        val node: dynamic = jsObject()
        node.el = host; node._sheets = idx; node._cur = if (cur != null && truthy(idx[cur])) cur else keysOf(idx)[0]; node._orch = orch; node._notes = notes.toTypedArray()
        sheetNode = node
        renderSheets()
        if (notes.isNotEmpty()) host.append(el("p", "sheet-note error", notes.joinToString("; ")))
        for (url in continuations) {
            val button = el("button", "sheet-more", "Next page")
            button.addEventListener("click", { GlobalScope.launch { loadSheets(listOf(SheetSource(url)), cur) } }); host.append(button)
        }
    }

    fun renderSheets() {
        val n = sheetNode; if (!truthy(n)) return
        val sheets = n._sheets
        val roots = keysOf(sheets).map { sheets[it] }.filter { !truthy(it.parent) || !truthy(sheets[it.parent]) }
        var top: dynamic = sheets[n._cur]; var guard = 0
        while (truthy(top) && truthy(top.parent) && truthy(sheets[top.parent]) && guard++ < 24) top = sheets[top.parent]
        val strip = q("#sheetRoots"); strip.replaceKids()
        if (roots.size > 1) for (r in roots) {
            val b = el("button", if (truthy(top) && top.id == r.id) "here" else "", "▤ " + jsString(if (truthy(r.title)) r.title else r.id))
            b.addEventListener("click", { n._cur = r.id; renderSheets() })
            strip.append(b)
        }
        Patch.renderConcentric(n)
        // a drill-in or crumb click re-renders inside patch.js; keep the roots strip honest
        val cells = n.el.querySelectorAll("[data-sheet]")
        for (i in 0 until jsNumber(cells.length).toInt()) cells.item(i).addEventListener("click", { renderSheets() })
    }

    fun rawSheets(on: Boolean) {
        q("#factInspector").classList.toggle("raw", on)
        q("#sheetRawBtn").setAttribute("aria-pressed", on.toString())
    }

    fun renderEvents() {
        val host = q("#events"); host.replaceKids()
        for (event in events.take(50)) {
            val button = el("button", "event-row")
            button.appendAll(el("b", "", event.key), el("small", "", jsString(if (truthy(event.actor)) event.actor else "unknown actor") + " / #" + jsString(event.seq) + " / " + Date(jsNumber(event.atMs)).toLocaleTimeString()))
            button.addEventListener("click", { inspect(event.key as String) }); host.append(button)
        }
    }

    fun zoomCeiling(px: Double, py: Double, camera: dynamic = view): Double = Patch.scopeZoomCeiling(px, py, camera)

    fun restoreCamera(camera: dynamic) {
        if (Patch.present("killMomentum")) Patch.killMomentum()
        val r = viewport.getBoundingClientRect(); val ax = r.width / 2; val ay = r.height / 2
        val c = LandscapeNavigation.zoomAt(LandscapeCamera(jsNumber(camera.x), jsNumber(camera.y), jsNumber(camera.z)), jsNumber(camera.z), Pt(ax, ay), zoomCeiling(ax, ay, camera))
        view.x = c.x; view.y = c.y; view.z = c.z
    }

    fun objectFocusBox(id: String?): dynamic {
        if (!id.isNullOrEmpty()) {
            val terrain = landscape.terrain
            val box = if (jsTypeOf(terrain?.boxFor) == "function") terrain.boxFor(id) else null
            return if (truthy(box)) box else terrain?.nodeFor(id)?.rect ?: null
        }
        return landscape.objectBox
    }

    fun applyTerrainBookmark(): Boolean {
        val target = terrainBookmark; if (!truthy(target)) return false
        val box = objectFocusBox(target.id as String?); if (!truthy(box)) return false
        focus(box, LandscapeNavigation.`object`(jsString(target.id)), false)
        if (truthy(landscape.heapLoaded)) terrainBookmark = null
        return true
    }

    fun restoreBookmark(bookmark: dynamic): Boolean {
        if (!truthy(bookmark)) return false
        val focus = if (truthy(bookmark.focus)) bookmark.focus as String else ""
        if (focus.startsWith("object:")) {
            terrainBookmark = obj { it.id = focus.substring(7) }
            if (applyTerrainBookmark()) return true
        }
        restoreCamera(bookmark.camera); focusKey = focus; Patch.applyView(); Patch.redraw()
        return true
    }

    fun focus(box: dynamic, identity: String = "", remember: Boolean = true, scale: Double = 1.0) {
        if (remember) {
            viewHistory.add(ViewEntry(js("Object.assign({}, view)"), focusKey, viewNode))
            if (viewHistory.size > 64) viewHistory.removeAt(0)
        }
        focusKey = identity; viewNode = null; q("#viewBack").unsafeCast<HTMLButtonElement>().disabled = viewHistory.isEmpty()
        if (Patch.present("killMomentum")) Patch.killMomentum() // a focus is a hard cut, never a glide target
        val r = viewport.getBoundingClientRect()
        val c = focusCamera(WorldBox(jsNumber(box.x), jsNumber(box.y), jsNumber(box.w), jsNumber(box.h)), r.width, r.height, scale)
        view.z = c.z; view.x = c.x; view.y = c.y; Patch.applyView(); Patch.redraw()
        rememberView()
    }

    fun rememberView() {
        if (!ready) return
        window.clearTimeout(viewSave)
        viewSave = window.setTimeout({
            val url = URL(window.location.href)
            url.hash = LandscapeNavigation.encode(LandscapeCamera(jsNumber(view.x), jsNumber(view.y), jsNumber(view.z)), focusKey)
            window.history.replaceState(null, "", url.href)
        }, 200)
    }

    fun previousView() {
        val previous = viewHistory.removeLastOrNull() ?: return
        restoreCamera(previous.camera); focusKey = previous.focus; viewNode = previous.node
        q("#viewBack").unsafeCast<HTMLButtonElement>().disabled = viewHistory.isEmpty(); Patch.applyView(); Patch.redraw(); rememberView()
    }

    fun enclosingView() {
        val node = parentTarget()?.node
        if (truthy(node?._parentScope)) focusNode(node._parentScope)
        else if (truthy(node)) select(node._program as String)
        else fit(true)
    }

    fun focusNode(node: dynamic) {
        selectParent(node)
        focusElement(node.el, LandscapeNavigation.node(jsString(node._program), jsString(if (truthy(node._localId)) node._localId else node.id)))
        viewNode = node
    }

    // Editing ownership is sticky. Panning, fitting the board and zooming out cannot retarget layout or mutation commands.
    fun prominent(): String? = selected

    fun observeZoom(px: Double, py: Double) {
        val r = viewport.getBoundingClientRect()
        // Snap to the territory being LOOKED AT: the innermost one under the pointer that has grown
        // to fill the view, measured per axis (see ZoomSnap).
        val territories = positions.entryList().map { (k, a) -> ZoomSnap.Territory(k, jsNumber(a.x), jsNumber(a.y), jsNumber(a.w), jsNumber(a.h)) }
        val box = ZoomSnap.innermost(territories, LandscapeCamera(jsNumber(view.x), jsNumber(view.y), jsNumber(view.z)), px, py) ?: return
        if (!ZoomSnap.arrived(box, r.width, r.height)) return
        val name = HarnessKeys.programName(box.key)
        if (name != selected) select(name, false)
    }

    fun fit(all: Boolean) {
        if (!all) {
            val target = parentTarget() ?: return
            if (truthy(target.node)) {
                focusElement(if (truthy(target.node._childHost)) target.node._childHost else target.node.el, LandscapeNavigation.node(selected!!, jsString(target.handle.nodeId)))
                viewNode = target.node
            } else { val box = positions.get(HarnessKeys.PROGRAM + selected); if (box != null) focus(box, LandscapeNavigation.program(selected!!)) }
            return
        }
        val boxes = positions.valueList()
        val x = boxes.fold(Double.POSITIVE_INFINITY) { m, b -> min(m, jsNumber(b.x)) }
        val y = boxes.fold(Double.POSITIVE_INFINITY) { m, b -> min(m, jsNumber(b.y)) }
        val right = boxes.fold(Double.NEGATIVE_INFINITY) { m, b -> max(m, jsNumber(b.x + b.w)) }
        val bottom = boxes.fold(Double.NEGATIVE_INFINITY) { m, b -> max(m, jsNumber(b.y + b.h)) }
        focus(obj { it.x = x; it.y = y; it.w = right - x; it.h = bottom - y })
    }

    fun focusElement(element: dynamic, identity: String = "") {
        val a = element.getBoundingClientRect(); val b = world.getBoundingClientRect()
        val z = jsNumber(view.z)
        val box: dynamic = jsObject()
        box.x = (jsNumber(a.left) - b.left) / z; box.y = (jsNumber(a.top) - b.top) / z; box.w = jsNumber(a.width) / z; box.h = jsNumber(a.height) / z
        focus(box, identity, true, if (jsNumber(element.offsetWidth) > 0) jsNumber(a.width) / jsNumber(element.offsetWidth) / z else 1.0)
    }

    fun output(receipt: dynamic) {
        HarnessArguments.record(receipt)
        if (!truthy(receipt?.programKey)) return
        val entry = board[receipt.programKey]
        if (truthy(receipt.programCid) && truthy(entry?.programCid) && receipt.programCid != entry.programCid) return
        if (drafts.has(receipt.program as String)) return
        for (n in Patch.nodes().filter { it._program == receipt.program }) {
            val outputs: dynamic = receipt.outputs
            val out: dynamic = if (outputs == null) undefined else outputs[if (truthy(n._localId)) n._localId else n.id]
            n.el.classList.remove("running", "ok", "err")
            if (out !== undefined) { n._lastOut = out; n.el.classList.add("ok"); Patch.setResult(n, out) }
        }
        for (v in (if (truthy(receipt.violations)) receipt.violations.unsafeCast<Array<dynamic>>() else emptyArray()))
            Patch.BOARD.violations.set(cableKey(jsString(receipt.program) + "::" + jsString(v.fromNode), v.fromPort, jsString(receipt.program) + "::" + jsString(v.toNode), v.toPort), if (truthy(v.detail)) v.detail else v.rule)
        applying = true
        try { Patch.resolveTopLevelOverlaps() } finally { applying = false }
        baselines.set(receipt.program as String, JSON.stringify(document(receipt.program as String)))
        schedule()
    }

    suspend fun run() {
        if (inspectionOnly()) { message("Inspection-only wiring specimen; execution is disabled"); return }
        if (running || dirty || selected == null) return
        val inputs: dynamic
        try { inputs = HarnessArguments.inputs(selected!!) } catch (e: Throwable) { message(errorMessage(e)); return }
        running = true; q("#runBtn").unsafeCast<HTMLButtonElement>().disabled = true
        val name = selected!!; message("Running $name")
        try {
            val body: dynamic = jsObject(); body.program = name; body.inputs = inputs
            val response = fetchResponse("/api/lcnc/run", requestInit("POST", JSON.stringify(body)))
            val result = response.jsonValue(); output(result)
            message(name + ": " + (if (truthy(result.ok)) "completed" else if (truthy(result.error)) jsString(result.error) else "failed"))
        } catch (e: Throwable) { message("Run failed: " + errorMessage(e)) }
        finally {
            running = false; q("#runBtn").unsafeCast<HTMLButtonElement>().disabled = dirty || inspectionOnly()
            if (q("#argumentInspector").asDynamic().open == true) HarnessArguments.validate()
        }
    }

    suspend fun cancelRun() {
        val run = keysOf(board).map { board[it] }.firstOrNull { it?.programKey == HarnessKeys.PROGRAM + selected && HarnessKeys.isActiveRunStatus(it.status) } ?: return
        val body: dynamic = jsObject(); body.runId = run.runId
        val response = fetchResponse("/api/lcnc/run/cancel", requestInit("POST", JSON.stringify(body)))
        message(if (response.ok) "Cancellation requested" else "Run is no longer active")
    }

    /** Rebuild (Forge genesis, Cut S): the same program version, the same inputs, over the documents as they are now. */
    suspend fun rebuild(receipt: dynamic) {
        val body: dynamic = jsObject(); body.runId = receipt.runId
        val response = fetchResponse("/api/lcnc/run/rebuild", requestInit("POST", JSON.stringify(body)))
        val result: dynamic = try { response.jsonValue() } catch (_: Throwable) { jsObject() }
        if (!response.ok) throw Error(if (truthy(result.error)) jsString(result.error) else jsString(response.status))
        output(result)
        message("Rebuilt " + jsString(if (truthy(receipt.program)) receipt.program else "") + ": " + (if (truthy(result.ok)) "completed" else if (truthy(result.error)) jsString(result.error) else "failed") +
            (if (truthy(result.rebuildOf)) " (rebuild of " + jsString(result.rebuildOf).take(19) + ")" else ""))
    }

    suspend fun publish(overrideBaseCid: dynamic = null) {
        val name = q("#panelName").unsafeCast<HTMLInputElement>().value.trim()
        if (!HarnessKeys.validProgramName(name)) { message("Use a lowercase program name"); return }
        // Stale-base refusal (Forge genesis, Cut C): publishing over the program this editor
        // loaded names that version; a board that moved on refuses, and the draft stays here.
        val baseCid: dynamic = if (truthy(overrideBaseCid)) overrideBaseCid else if (name == selected && truthy(loadedCids.get(name))) loadedCids.get(name) else null
        val url = "/api/panels/" + encodeURIComponent(name) + (if (truthy(baseCid)) "?baseCid=" + encodeURIComponent(baseCid as String) else "")
        try {
            val response = fetchResponse(url, requestInit("POST", JSON.stringify(document())))
            val result = response.jsonValue()
            if (response.status.toInt() == 409 && result.error == "stale_base") {
                val refused: dynamic = js("Object.assign({}, result)"); refused.actor = "panels-editor"; refused.atMs = Date.now()
                board[HarnessKeys.PUBLISH + name] = refused
                message("Publish refused: $name moved on the board (now " + jsString(result.currentCid).take(19) + ")")
                inspect(HarnessKeys.PUBLISH + name)
                return
            }
            if (!response.ok || result.verdict != "ok") throw Error(jsString(if (truthy(result.error)) result.error else if (truthy(result.detail)) result.detail else response.status))
            val entryResponse = fetchResponse("/api/panels/" + encodeURIComponent(name) + "?entry=1")
            if (!entryResponse.ok) throw Error("Published entry unavailable")
            board[HarnessKeys.PROGRAM + name] = entryResponse.jsonValue()
            if (previews.has(selected!!)) { previews.delete(selected!!); unmount(selected!!) }
            drafts.delete(selected!!); dirty = false; mount(name); select(name, false)
            message("Published " + name + (if (jsNumber(result.violations?.length ?: 0) > 0) " with refused cables" else ""))
        } catch (e: Throwable) { message("Publish failed: " + errorMessage(e)) }
    }

    /** A workspace snapshot from the toolbar (Forge genesis, Cut C): the head lands on the board and opens in the inspector. */
    suspend fun snapshot() {
        try {
            val response = fetchResponse("/api/snapshots", requestInit("POST", JSON.stringify(js("({note:'from the harness'})"))))
            val result = response.jsonValue()
            if (!response.ok || result.verdict != "ok") throw Error(jsString(if (truthy(result.error)) result.error else if (truthy(result.detail)) result.detail else response.status))
            val head: dynamic = jsObject()
            head.cid = result.cid; head.previousCid = result.previousCid; head.atMs = result.atMs; head.counts = result.counts; head.note = "from the harness"; head.actor = "snapshots-route"
            board[HarnessKeys.SNAPSHOT_HEAD] = head
            message("Snapshot " + jsString(result.cid).take(19) + (if (truthy(result.previousCid)) " after " + jsString(result.previousCid).take(19) else ""))
            inspect(HarnessKeys.SNAPSHOT_HEAD)
        } catch (e: Throwable) { message("Snapshot failed: " + errorMessage(e)) }
    }

    fun layoutHints(doc: dynamic, parentId: dynamic): dynamic = Patch.PatchLayout.hints(doc, parentId)

    fun shake(options: dynamic): dynamic = Patch.requestTreeShake(facade, options, selected)

    fun showConnections(program: dynamic, result: dynamic, summary: String) {
        connectionReport = obj { it.program = program; it.result = result }
        val host = q("#connections"); host.hidden = false; host.replaceKids(el("h3", "", "Connections"), el("p", "", summary))
        val verdicts = (if (truthy(result.verdicts)) result.verdicts.unsafeCast<Array<dynamic>>() else emptyArray()).toList()
            .sortedBy { if (ConnectionVerdicts.settled(it.status)) 1 else 0 }
        for (verdict in verdicts.take(ConnectionVerdicts.SHOWN)) {
            val node = Patch.nodes().firstOrNull { it.id == verdict.nodeId } ?: continue
            val row = el("div", "connection-row " + jsString(verdict.status))
            val nodeLabel = jsString(if (truthy(node._localId)) node._localId else node.id)
            val focusButton = el("button", "connection-focus", nodeLabel + " / " + jsString(verdict.port))
            focusButton.title = "Focus " + jsString(node.type) + " " + jsString(verdict.port)
            focusButton.addEventListener("click", { focusNode(node) })
            row.appendAll(focusButton, el("span", "", verdict.label))
            if (ConnectionVerdicts.offersMate(verdict.dir, verdict.status)) {
                val mate = el("button", "connection-mate", "+"); mate.title = "Choose a source for " + jsString(verdict.port)
                mate.setAttribute("aria-label", "Choose source for $nodeLabel " + jsString(verdict.port))
                mate.addEventListener("click", {
                    focusNode(node)
                    val box = viewport.getBoundingClientRect()
                    Patch.showMateMenu(box.left + 20, box.top + 20, node, verdict.port, node.x - 240, node.y, node._parentScope, "in")
                })
                row.append(mate)
            }
            host.append(row)
        }
        if (verdicts.size > ConnectionVerdicts.SHOWN) host.append(el("p", "", "${verdicts.size - ConnectionVerdicts.SHOWN} additional port results"))
    }

    suspend fun accept(event: dynamic) {
        if (!truthy(event.key) || jsNumber(event.seq) <= seq) return
        if (event.epoch != epoch || jsNumber(event.seq) != seq + 1) { reconnect("Revision gap"); return }
        seq = jsNumber(event.seq)
        val key = event.key as String
        if (truthy(event.deleted)) jsDelete(board, key) else board[key] = event.value
        if (truthy(event.actor)) actors.set(key, event.actor)
        events.add(0, event); while (events.size > 100) events.removeAt(events.size - 1)
        val prefix = HarnessKeys.prefix(key); flash(prefix); flash(key)
        if (key == HarnessKeys.VOCABULARY) { (Patch.syncContracts(event.value) as Promise<dynamic>).await(); Patch.buildPalette() }
        if (key.startsWith(HarnessKeys.PROGRAM)) {
            val name = HarnessKeys.programName(key)
            if (drafts.has(name)) message("Board updated while $name has unpublished changes")
            else if (truthy(event.deleted)) unmount(name)
            else mount(name)
        }
        if (key.startsWith(HarnessKeys.RUN) && !truthy(event.deleted)) { output(event.value); flash(event.value.programKey as String?); flash("receipts") }
        queueNeighbors()
        schedule()
    }

    // Each key keeps its own expiry (now + 1000 ms) so a burst of N kanban commits lights N rows
    // that fade on their own clocks.
    fun flash(key: String?) { if (!key.isNullOrEmpty()) flashes.set(key, Date.now() + FlashClock.HOLD_MS); armFlashSweep() }

    private fun armFlashSweep() {
        if (flashSweep != null) return
        flashSweep = window.setInterval({
            val now = Date.now(); var expired = false
            for ((key, until) in flashes.entryList()) if (until <= now) { flashes.delete(key); expired = true }
            if (expired) schedule()
            if (flashes.size == 0) { flashSweep?.let { window.clearInterval(it) }; flashSweep = null }
        }, FlashClock.SWEEP_MS)
    }

    fun reconnect(reason: String = "Reconnecting") {
        live = false; landscape.refreshActivity()
        stream?.close(); connectionGeneration++
        connectionStatus(reason); q("#connection").classList.remove("live")
        window.clearTimeout(reconnectTimer); reconnectTimer = window.setTimeout({ GlobalScope.launch { connect() } }, 500)
    }

    suspend fun connect() {
        live = false
        stream?.close(); window.clearTimeout(reconnectTimer)
        val generation = ++connectionGeneration; val initial = !ready
        val decoded = if (initial) LandscapeNavigation.decode(initialHash.ifEmpty { window.location.hash }) else null
        val bookmark: dynamic = if (decoded == null) null else obj {
            it.camera = obj { c -> c.x = decoded.camera.x; c.y = decoded.camera.y; c.z = decoded.camera.z }; it.focus = decoded.focus
        }
        var buffer = ArrayList<dynamic>(); var hydrating = true
        var chain: Promise<Any?> = Promise.resolve(null)
        connectionStatus("Syncing")
        try {
            val response = fetchResponse("/blackboard/board"); if (!response.ok) throw Error("Snapshot " + response.status)
            val snapshot = response.jsonValue(); if (generation != connectionGeneration) return
            board = if (truthy(snapshot.board)) snapshot.board else js("Object.create(null)")
            seq = jsNumber(snapshot.revision); epoch = snapshot.epoch
            if (!(js("Number").isSafeInteger(seq) as Boolean) || !truthy(epoch)) throw Error("Revision-linked snapshot required")
            actors = JsMap()
            val provenance: dynamic = if (truthy(snapshot.provenance)) snapshot.provenance else jsObject()
            for (k in keysOf(provenance)) actors.set(k, provenance[k].actor)
            val s = EventSource("/blackboard/facts?since=" + jsString(seq) + "&epoch=" + encodeURIComponent(jsString(epoch))); stream = s
            s.addEventListener("reset", { if (generation == connectionGeneration) reconnect("Refreshing snapshot") })
            s.onmessage = { e: MessageEvent ->
                if (generation == connectionGeneration) {
                    try {
                        val event: dynamic = JSON.parse(e.data as String)
                        if (hydrating) { buffer.add(event); if (buffer.size > 2048) reconnect("Snapshot backlog") }
                        else chain = chain.then { if (generation == connectionGeneration) GlobalScope.promise { accept(event) } else null }
                            .unsafeCast<Promise<Any?>>().catch { reconnect() }.unsafeCast<Promise<Any?>>()
                    } catch (_: Throwable) { reconnect("Invalid event") }
                }
            }
            s.onerror = { if (generation == connectionGeneration) reconnect() }
            (Patch.syncContracts(board[HarnessKeys.VOCABULARY]) as Promise<dynamic>).await(); (Patch.syncLanes() as Promise<dynamic>).await()
            if (generation != connectionGeneration) return
            ready = true
            for (name in mounts.keyList()) if (!truthy(board[HarnessKeys.PROGRAM + name]) && !drafts.has(name) && !previews.has(name)) unmount(name)
            val query = URLSearchParams(window.location.search); val requested = query.get("load"); val requestedExample = query.get("example")
            val names = programs().map { HarnessKeys.programName(it) }
            val name = listOf(selected, requested).firstOrNull { !it.isNullOrEmpty() && it in names }
            for (n in names) mount(n)
            if (name != null) select(name, initial && bookmark == null, !initial)
            else {
                selected = null; dirty = false; setParent(null); q("#panelName").unsafeCast<HTMLInputElement>().value = ""
                message(if (!requested.isNullOrEmpty()) "Program is not available: $requested" else "No program selected" + (if (names.isNotEmpty()) "" else " · no saved programs"))
                if (!requested.isNullOrEmpty()) { val url = URL(window.location.href); url.searchParams.delete("load"); url.hash = ""; window.history.replaceState(null, "", url.href) }
            }
            if (initial && !requestedExample.isNullOrEmpty()) {
                val presets = fetchResponse("/api/panels/presets"); if (!presets.ok) throw Error("Example catalog " + presets.status)
                val catalog = presets.jsonValue(); if (generation != connectionGeneration) return
                val example = (if (truthy(catalog.presets)) catalog.presets.unsafeCast<Array<dynamic>>() else emptyArray()).firstOrNull { it.name == requestedExample }
                if (example != null) openExample(example.name as String, example.document) else message("Example is not available: $requestedExample")
            }
            for (event in buffer) {
                if (generation != connectionGeneration) return
                accept(event)
            }
            buffer = ArrayList(); hydrating = false; render(); Patch.buildPalette()
            (landscape.refresh() as Promise<dynamic>).await()
            if (generation != connectionGeneration) return
            if (bookmark != null && (requested.isNullOrEmpty() || name != null || !requestedExample.isNullOrEmpty())) restoreBookmark(bookmark)
            else if (initial && surface == HarnessSurface.Graal) focus(landscape.objectBox, LandscapeNavigation.`object`(""))
            else if (initial && name == null && requestedExample.isNullOrEmpty()) fit(surface != HarnessSurface.Panels)
            connectionStatus("Live"); q("#connection").classList.add("live")
            live = true; landscape.refreshActivity()
            queueNeighbors()
        } catch (e: Throwable) {
            if (generation == connectionGeneration) { message("Blackboard unavailable: " + errorMessage(e)); reconnect() }
        }
    }

    /** `window.Harness`: the object the patch surface, camera, shake and landscape reach for. */
    val facade: dynamic by lazy { HarnessFacade.build() }
}

fun jsDelete(target: dynamic, key: String) { js("delete target[key]") }
