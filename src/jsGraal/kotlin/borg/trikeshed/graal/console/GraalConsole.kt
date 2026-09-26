package borg.trikeshed.graal.console

import borg.trikeshed.web.AppNav
import borg.trikeshed.web.MuxIcons
import borg.trikeshed.web.graal.AllocationInspector
import borg.trikeshed.web.graal.TerrainPainter
import borg.trikeshed.web.graal.byId
import borg.trikeshed.web.graal.decUri
import borg.trikeshed.web.graal.encUri
import borg.trikeshed.web.graal.fetchJson
import borg.trikeshed.web.graal.jsRound
import borg.trikeshed.web.graal.jsonIndent
import borg.trikeshed.web.graal.jsonPost
import borg.trikeshed.web.graal.methodInit
import borg.trikeshed.web.graal.newObject
import borg.trikeshed.web.graal.num
import borg.trikeshed.web.graal.orr
import borg.trikeshed.web.graal.perfNow
import borg.trikeshed.web.graal.str
import borg.trikeshed.web.graal.terrainRows
import borg.trikeshed.web.graal.toFixed
import borg.trikeshed.web.graal.truthy
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A ping ring at a world point. */
class Ping(val x: Double, val y: Double, val color: String, val label: String?, val t0: Double)

/** A DAG edge of the selected document. */
class SelEdge(val to: TerrainNode?, val kind: String, val id: String)

/** The Graal console. Mounted once per page load on the `/graal` shell. */
object GraalConsole {
    val scope = MainScope()
    val reduced: Boolean = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    const val DB = "trikeshed"
    /** mounted project dbs (from /api/graal/map): rows `<db>/<docid>` resolve to `/<db>/<docid>` */
    var PROJECT_DBS: Set<String> = emptySet()

    lateinit var map: HTMLCanvasElement
    lateinit var ctx: CanvasRenderingContext2D
    lateinit var mini: HTMLCanvasElement
    lateinit var mctx: CanvasRenderingContext2D
    var cam = Camera()

    var root: TerrainNode? = null
    var byIdMap: Map<String, TerrainNode> = emptyMap()
    val classNodes = HashMap<String, MutableList<TerrainNode>>()
    var maxSeq = 1L
    /** the store terrain rows (from /api/graal/map) */
    var lastRows: Array<dynamic>? = null
    /** the heap continent rows (from /api/graal/heap) */
    var heapRows: Array<dynamic>? = null
    var ARRANGE: String = localStorage.getItem("arrange") ?: "positional"
    var HEAP_TERRAIN: Boolean = localStorage.getItem("heapTerrain") == "1"

    var skin: String = try { localStorage.getItem("graal.skin") ?: "ops" } catch (_: Throwable) { "ops" }
    var mode = "map"
    var fog = false
    /** territory path → last activity ms */
    val heat = HashMap<String, Double>()
    var layer = 1
    val painter = TerrainPainter(::graalContentUrl, false) {}

    val graph = TerrainGraph()
    var hubsCache: List<DagHub>? = null
    var gDrag: GraphBody? = null

    var hover: TerrainNode? = null
    var pings = ArrayList<Ping>()
    var shake = 0.0
    var sel: TerrainNode? = null
    var selEdges: List<SelEdge> = emptyList()

    fun dbUrl(id: String): String {
        val seg = id.split('/')[0]
        if (seg in PROJECT_DBS) return "/" + seg + "/" + id.split('/').drop(1).joinToString("/") { encUri(it) }
        return "/" + DB + "/" + id.split('/').joinToString("/") { encUri(it) }
    }
    fun graalDocUrl(id: String): String = "/api/graal/doc?id=" + encUri(id)
    fun graalContentUrl(id: String): String = "/api/graal/content?id=" + encUri(id)

    fun q(id: String): HTMLElement = byId(id)!!
    fun crumb(text: String) { q("crumb").textContent = text }
    fun first(id: String): Node = q(id).firstChild!!
    val innerWidth: Double get() = window.innerWidth.toDouble()
    val innerHeight: Double get() = window.innerHeight.toDouble()

    fun mount() {
        if (document.getElementById("map") == null) return
        MuxIcons.install()
        AppNav.mount()
        AllocationInspector.install()
        map = q("map") as HTMLCanvasElement
        ctx = map.getContext("2d") as CanvasRenderingContext2D
        mini = q("mini") as HTMLCanvasElement
        mctx = mini.getContext("2d") as CanvasRenderingContext2D
        sparkCtx = (q("cComp").querySelector("canvas") as HTMLCanvasElement).getContext("2d") as CanvasRenderingContext2D
        if (Skin.ALL[skin] == null) skin = "ops"
        applySkin()
        window.addEventListener("resize", { resize() })
        exportWindow()
        bindInput()
        bindDrop()
        bindChrome()
        window.setInterval({ recentCommits = 0 }, 1000)
        resize(); crumb("drop lane v2 armed — drag a folder anywhere")
        loadMap(); loadHeap(); pollVitals(); pollVms(); loadAot(); connectSse(); boardRefresh()
        window.requestAnimationFrame { draw() }
    }

    /** Every function the markup's inline handlers and the generated HTML strings call. */
    private fun exportWindow() {
        val w = window.asDynamic()
        val nSort: dynamic = newObject(); nSort.col = 2; nSort.dir = -1
        w.nSort = nSort
        w.selEdges = js("[]")
        w.sheetCurrentId = null
        w.hide = { id: String -> hide(id) }
        w.show = { id: String -> show(id) }
        w.toggleAot = { toggleAot() }
        w.hermesRunner = { hermesRunner() }
        w.toggleBoard = { toggleBoard() }
        w.toggleSheetView = { toggleSheetView() }
        w.renderSheetFamily = { renderSheetFamily() }
        w.vtKill = { vtKill() }
        w.openDoc = { id: String -> openDoc(id) }
        w.projectBytes = { id: String, ct: String?, ext: String? -> scope.launch { projectBytes(id, ct, ext) }; Unit }
        w.zoomGo = { zoomGo() }
        w.strengthGo = { strengthGo() }
        w.densityGo = { densityGo() }
        w.vmSpawn = { vmSpawn() }
        w.vmRevoke = { id: String -> vmRevoke(id) }
        w.vmEval = { id: String -> vmEval(id) }
        w.releaseRepo = { id: String -> releaseRepo(id) }
        w.occupyRepo = { occupyRepo() }
        w.pcAssert = { pcAssert() }
        w.jumpLine = { n: Int -> jumpLine(n) }
        w.flyTo = { n: dynamic -> flyTo(n.unsafeCast<TerrainNode>()) }
        w.captureAot = { captureAot() }
        w.boardRefresh = { boardRefresh() }
        w.buildNotion = { buildNotion() }
    }

    fun resize() {
        val dpr = window.devicePixelRatio
        map.width = (innerWidth * dpr).toInt(); map.height = (innerHeight * dpr).toInt()
        map.style.width = str(innerWidth) + "px"; map.style.height = str(innerHeight) + "px"
        fit()
    }

    fun fit() { killMomentum(); cam = Camera().also { it.fit(innerWidth, innerHeight) } }

    fun activeRows(): Array<dynamic> = if (HEAP_TERRAIN && heapRows != null) heapRows!! else (lastRows ?: emptyArray())

    fun buildAll() {
        val rows = activeRows()
        if (rows.isEmpty()) return
        buildTree(rows)
        crumb("terrain: " + (if (HEAP_TERRAIN) "JVM heap continent" else "store") +
            " — " + loc(rows.size) + " rows · " + (if (HEAP_TERRAIN) "live-set + allocation, per class" else "documents") +
            " · arrangement: " + ARRANGE + (if (HEAP_TERRAIN) " — x back to store" else " — x for the heap continent"))
    }

    fun toggleArrange() { ARRANGE = if (ARRANGE == "positional") "similarity" else "positional"; localStorage.setItem("arrange", ARRANGE); buildAll() }
    fun toggleHeapTerrain() {
        HEAP_TERRAIN = !HEAP_TERRAIN; localStorage.setItem("heapTerrain", if (HEAP_TERRAIN) "1" else "0")
        if (HEAP_TERRAIN && heapRows == null) loadHeap() else buildAll()
    }

    private val HEAP_ID = Regex("^heap-(live|alloc)/")

    fun buildTree(rows: Array<dynamic>) {
        classNodes.clear()
        val now = perfNow()
        heat.entries.removeAll { now - it.value >= 120000 }
        val touched = HashMap(heat)
        val built = Terrain.build(terrainRows(rows), ARRANGE == "similarity")
        root = built.root; byIdMap = built.byId; maxSeq = built.maxSeq
        for (r in rows) {
            val id = str(r[0])
            val n = built.byId[id] ?: continue
            val classPath = id.split("/build/live/classes/").getOrNull(1)
            val className = if (classPath != null && classPath.endsWith(".class")) classPath.dropLast(6).replace('/', '.')
            else if (HEAP_ID.containsMatchIn(id)) id.substring(id.indexOf('/') + 1) else null
            if (className != null) classNodes.getOrPut(className) { ArrayList() }.add(n)
            touched[id]?.let { heatUp(n, it) }
        }
        Terrain.invalidate(built.root)
        first("cDocs").textContent = loc(built.root.docs)
    }

    fun layout(n: TerrainNode) { root?.let { Terrain.layout(n, it) } }
    fun pathOf(n: TerrainNode?): String = n?.path() ?: ""
    fun fmtBytes(b: Double): String = Terrain.fmtBytes(b)
    fun fmtBytes(b: dynamic): String = Terrain.fmtBytes(num(b))
    fun loc(n: Number): String = borg.trikeshed.web.graal.loc(n)

    fun applySkin() {
        document.documentElement!!.setAttribute("data-skin", skin)
        try { localStorage.setItem("graal.skin", skin) } catch (_: Throwable) {}
    }

    fun cycleSkin() {
        skin = Skin.ORDER[(Skin.ORDER.indexOf(skin) + 1) % Skin.ORDER.size]; applySkin()
        crumb("skin: " + skin + (if (skin == "chalk") " — AI blackboard" else if (skin == "marker") " — developer whiteboard" else " — ops console"))
    }

    fun heatUp(n0: TerrainNode?, now: Double = perfNow()) {
        var n = n0
        while (n != null && n !== root) { val id = pathOf(n); heat[id] = max(heat[id] ?: 0.0, now); n = n.parent }
    }

    fun heatOf(n: TerrainNode): Double { val t = heat[pathOf(n)] ?: return 0.0; return if (t == 0.0) 0.0 else max(0.0, 1 - (perfNow() - t) / 120000) }

    fun targetNode(): TerrainNode? = hover ?: sel ?: root

    fun cycleMode() {
        mode = if (mode == "map") "graph" else if (mode == "graph") "tree" else if (mode == "tree") "notion" else "map"
        if (mode == "graph") buildGraph()
        if (mode == "notion") for (id in EXCLUSIVE_PANELS) q(id).style.display = "none"
        q("notion").style.display = if (mode == "notion") "flex" else "none"
        if (mode == "notion") buildNotion()
        val t = targetNode()
        crumb("mode: " + mode + (if (mode == "graph") " — force DAG (hubs + edges), drag nodes" else if (mode == "tree") " — radial drilldown of " + (if (t === root) "/" else pathOf(t))
            else if (mode == "notion") " — database flavor: sort by header, filter, click to inspect" else " — territory map"))
    }

    fun buildGraph() {
        scope.launch {
            if (hubsCache == null) hubsCache = try {
                val d: dynamic = fetchJson("/api/graal/dag")
                (orr(d.hubs, js("[]"))).unsafeCast<Array<dynamic>>().map { h -> DagHub(h.ids.unsafeCast<Array<String>>().toList(), num(h.degree)) }
            } catch (_: Throwable) { emptyList() }
            root?.let { graph.build(it, hubsCache!!) }
        }
    }

    // ── input ──
    var dragging = false; var lx = 0.0; var ly = 0.0; var moved = 0.0
    /** momentum: pan velocity in SCREEN px/ms; zoom velocity in log-scale per 16.7ms anchored at the last wheel point. */
    var momVx = 0.0; var momVy = 0.0; var momZv = 0.0; var momAx = 0.0; var momAy = 0.0
    var velX = 0.0; var velY = 0.0; var velT = 0.0
    var momT = perfNow()

    fun killMomentum() { momVx = 0.0; momVy = 0.0; momZv = 0.0 }

    fun hit(cx: Double, cy: Double): TerrainNode? = Terrain.hit(root, cam, cx, cy)

    private fun bindInput() {
        map.addEventListener("mousedown", { ev ->
            val e = ev as MouseEvent
            killMomentum()
            if (mode == "graph") {
                val wx = cam.ox + e.clientX / cam.s; val wy = cam.oy + e.clientY / cam.s
                gDrag = graph.at(wx, wy)
                if (gDrag != null) { moved = 0.0; return@addEventListener }
            }
            dragging = true; moved = 0.0; lx = e.clientX.toDouble(); ly = e.clientY.toDouble(); velX = 0.0; velY = 0.0; velT = perfNow(); map.classList.add("drag")
        })
        window.addEventListener("mouseup", {
            if (dragging && !reduced && perfNow() - velT < 80) { momVx = velX; momVy = velY } // recent flick → glide
            dragging = false; gDrag = null; map.classList.remove("drag")
        })
        map.addEventListener("mousemove", { ev ->
            val e = ev as MouseEvent
            val g = gDrag
            if (g != null) { g.x = cam.ox + e.clientX / cam.s; g.y = cam.oy + e.clientY / cam.s; return@addEventListener }
            if (dragging) {
                val dx = e.clientX - lx; val dy = e.clientY - ly; moved += abs(dx) + abs(dy)
                cam.ox -= dx / cam.s; cam.oy -= dy / cam.s; lx = e.clientX.toDouble(); ly = e.clientY.toDouble()
                val now = perfNow(); val dt = max(1.0, now - velT)
                velX = 0.75 * velX + 0.25 * (dx / dt); velY = 0.75 * velY + 0.25 * (dy / dt); velT = now // low-passed flick velocity
            }
            hover = hit(e.clientX.toDouble(), e.clientY.toDouble())
            val h = hover
            crumb(if (h != null) pathOf(h) + "  ·  " + (if (h.leaf) fmtBytes(h.bytes) else loc(h.docs) + " docs, " + fmtBytes(h.bytes)) else "")
        })
        val passive: dynamic = newObject(); passive.passive = false
        map.addEventListener("wheel", { ev ->
            val e = ev as WheelEvent
            e.preventDefault()
            val f = if (e.deltaY < 0) 1.18 else 1 / 1.18
            val wx = cam.ox + e.clientX / cam.s; val wy = cam.oy + e.clientY / cam.s
            cam.s = min(max(cam.s * f, 0.2), 4000.0)
            cam.ox = wx - e.clientX / cam.s; cam.oy = wy - e.clientY / cam.s
            if (!reduced) {
                momAx = e.clientX.toDouble(); momAy = e.clientY.toDouble() // glide anchor = wheel point
                momZv = max(-0.12, min(0.12, momZv + ln(f) * 0.28)); momVx = 0.0; momVy = 0.0
            }
        }, passive)
        map.addEventListener("dblclick", { ev ->
            val e = ev as MouseEvent
            if (mode == "graph") {
                val wx = cam.ox + e.clientX / cam.s; val wy = cam.oy + e.clientY / cam.s
                val g = graph.at(wx, wy)
                if (g != null) { sel = g.n; mode = "map"; flyTo(g.n) }
                return@addEventListener
            }
            val n = hit(e.clientX.toDouble(), e.clientY.toDouble())
            if (n != null) flyTo(if (n.leaf) (n.parent ?: n) else n)
        })
        map.addEventListener("click", { ev ->
            val e = ev as MouseEvent
            if (moved > 4) return@addEventListener
            val n = hit(e.clientX.toDouble(), e.clientY.toDouble())
            if (n != null && n.leaf) openDoc(n.id!!)
        })
        /* right-click a project-db territory: HIERARCHY KILL — unmount db + ledger + clone (CAS blobs stay) */
        map.addEventListener("contextmenu", { ev ->
            val e = ev as MouseEvent
            val n = hit(e.clientX.toDouble(), e.clientY.toDouble()) ?: return@addEventListener
            var top: TerrainNode = n
            while (top.parent != null && top.parent !== root) top = top.parent!!
            if (top.name !in PROJECT_DBS) return@addEventListener
            e.preventDefault()
            if (!window.confirm("Kill project db /" + top.name + "?\nUnmounts the db, ledger entry and forge-home clone. CAS blobs stay (content-addressed).")) return@addEventListener
            scope.launch {
                try {
                    val r: dynamic = fetchJson("/api/projects/" + encUri(top.name), methodInit("DELETE"))
                    if (r.verdict == "unmounted") { river("deopt", "project db /" + top.name + " killed"); sel = null; loadMap() }
                    else river("deopt", "kill refused: " + str(orr(r.error, "?")))
                } catch (err: Throwable) { river("deopt", "kill failed: " + errText(err)) }
            }
        })
        mini.addEventListener("click", { ev ->
            val e = ev as MouseEvent
            killMomentum(); val r = mini.getBoundingClientRect()
            cam.ox = (e.clientX - r.left) / (200 / Terrain.W) - innerWidth / (2 * cam.s)
            cam.oy = (e.clientY - r.top) / (124 / Terrain.H) - innerHeight / (2 * cam.s)
        })
        window.addEventListener("keydown", { ev -> keydown(ev as KeyboardEvent) })
        q("vtInput").addEventListener("keydown", { ev ->
            val e = ev as KeyboardEvent
            if (e.key == "Enter") vtSend() else if (e.key == "Escape") hide("vt")
            e.stopPropagation() // don't let the map's global keydown (1/2/3/M/F/B…) eat terminal keystrokes
        })
    }

    private fun keydown(e: KeyboardEvent) {
        when (e.key) {
            "0" -> fit()
            "p", "P" -> togglePoints()
            "v", "V" -> toggleVms()
            "h", "H" -> hermesRunner()
            "o", "O" -> toggleBoard()
            "a", "A" -> toggleAot()
            "t", "T" -> treeSheet()
            "l", "L" -> ledger()
            "b", "B" -> cycleSkin()
            "m", "M" -> cycleMode()
            "y", "Y" -> { toggleArrange(); crumb("arrangement: " + ARRANGE + (if (ARRANGE == "similarity") " — split by AngularCodec rings (satellite)" else " — split by id path (positional)")) }
            "x", "X" -> toggleHeapTerrain()
            "g", "G" -> toggleGc()
            "z", "Z" -> toggleZoom()
            "f", "F" -> { fog = !fog; crumb("fog of war: " + (if (fog) "ON — only active territories lit" else "off")) }
            "1" -> { layer = 1; crumb("layer: plain terrain") }
            "2" -> { layer = 2; crumb("layer: kolmogorov — green structured, red incompressible (measured lazily per tile)") }
            "3" -> { layer = 3; crumb("layer: causal — cool old → warm recent, ⟳n = revision generation") }
        }
    }

    /** per-frame momentum integration — called from draw(); frame-rate independent decay */
    fun tickMomentum() {
        val now = perfNow(); val dt = min(50.0, now - momT); momT = now
        if (reduced) return
        val k = dt / 16.7
        if (!dragging && (abs(momVx) > 0.002 || abs(momVy) > 0.002)) {
            cam.ox -= momVx * dt / cam.s; cam.oy -= momVy * dt / cam.s
            val fr = 0.93.pow(k); momVx *= fr; momVy *= fr
            if (abs(momVx) <= 0.002 && abs(momVy) <= 0.002) { momVx = 0.0; momVy = 0.0 }
        }
        if (abs(momZv) > 0.0008) {
            val f = exp(momZv * k); val wx = cam.ox + momAx / cam.s; val wy = cam.oy + momAy / cam.s
            cam.s = min(max(cam.s * f, 0.2), 4000.0)
            cam.ox = wx - momAx / cam.s; cam.oy = wy - momAy / cam.s
            momZv *= 0.88.pow(k)
            if (abs(momZv) <= 0.0008) momZv = 0.0
        }
    }

    fun flyTo(n: TerrainNode) {
        killMomentum() // the fly animation owns the camera; a live glide would fight it
        val margin = 0.94
        val s = min(innerWidth / n.w, innerHeight / n.h) * margin
        val tx = n.x + n.w / 2 - innerWidth / (2 * s); val ty = n.y + n.h / 2 - innerHeight / (2 * s)
        if (reduced) { cam = Camera(s, tx, ty); return }
        val from = Camera(cam.s, cam.ox, cam.oy); val t0 = perfNow()
        fun step() {
            val t = min(1.0, (perfNow() - t0) / 320); val e = 1 - (1 - t).pow(3)
            cam.s = from.s + (s - from.s) * e; cam.ox = from.ox + (tx - from.ox) * e; cam.oy = from.oy + (ty - from.oy) * e
            if (t < 1) window.requestAnimationFrame { step() }
        }
        step()
    }

    fun nodeFor(id: String): TerrainNode? {
        byIdMap[id]?.let { return it }
        val parts = id.split('/').filter { it.isNotEmpty() }
        var n: TerrainNode? = root; var best = root
        for (p in parts) { if (n == null) break; n = n.children[p]; if (n != null) best = n }
        return best
    }

    fun errText(e: Throwable): String { val d: dynamic = e; return str(d) }

    // ── drop a directory hierarchy → it becomes a NEW PROJECT DB and a top-level territory ──
    private fun bindDrop() {
        window.addEventListener("dragover", { e -> e.preventDefault() })
        window.addEventListener("drop", { ev ->
            ev.preventDefault()
            val dt: dynamic = ev.asDynamic().dataTransfer ?: return@addEventListener
            // Lane 1: sources that expose real host paths (uri-list) → server-side mount.
            val raw: String = str(orr(orr(dt.getData("text/uri-list"), dt.getData("text/plain")), ""))
            val paths = Ingest.hostPaths(raw, { u -> try { str(js("new URL(u)").pathname) } catch (_: Throwable) { null } }, ::decUri)
            // Lane 2 reads the entries synchronously: the DataTransfer is emptied after the event.
            val items: Array<dynamic> = js("Array.from(dt.items||[])")
            val entries = items.mapNotNull { it -> if (truthy(it.webkitGetAsEntry)) it.webkitGetAsEntry() else null }.filter { truthy(it) }
            scope.launch {
                if (paths.isNotEmpty()) {
                    for (p in paths) {
                        river("commit", "mounting $p …")
                        try {
                            val body: dynamic = newObject(); body.path = p
                            val r: dynamic = fetchJson("/api/projects", jsonPost(JSON.stringify(body)))
                            if (r.verdict == "ok") { river("commit", "project db /" + str(r.name) + " — " + str(r.docs) + " docs (" + str(r.kind) + ")"); landTerritory(str(r.name)) }
                            else if (r.verdict == "mounting") {
                                // detached mount: the daemon absorbs off-request (multi-GB safe); poll for the territory
                                val guess = Ingest.mountName(p)
                                river("commit", "mounting in the daemon — big folders take a while ($guess)")
                                pollTerritory(guess, 0)
                            } else river("deopt", "mount refused: " + str(orr(orr(r.detail, r.error), "?")))
                        } catch (err: Throwable) { river("deopt", "mount failed: " + errText(err)) }
                    }
                    return@launch
                }
                // Lane 2: Chrome hides host paths — WALK the dropped hierarchy via the entry API and UPLOAD the bytes.
                if (entries.isEmpty()) { crumb("nothing mountable in that drop"); return@launch }
                for (entry in entries) {
                    if (!truthy(entry.isDirectory)) { river("deopt", "drop a FOLDER — single files go to the ingest lane"); continue }
                    river("commit", "uploading " + str(entry.name) + "/ …")
                    try { uploadFolder(entry) } catch (err: Throwable) { river("deopt", "upload failed: " + errText(err)) }
                }
            }
        })
    }

    private class Walked(val rel: String, val file: dynamic)

    private suspend fun walkEntries(dirEntry: dynamic, prefix: String): List<Walked> {
        val out = ArrayList<Walked>()
        val reader: dynamic = dirEntry.createReader()
        while (true) {
            val batch: Array<dynamic> = Promise<Array<dynamic>> { res, rej -> reader.readEntries({ b: dynamic -> res(b) }, { e: dynamic -> rej(e) }) }.await()
            if (batch.isEmpty()) break
            for (en in batch) {
                if (truthy(en.isFile)) {
                    val file: dynamic = Promise<dynamic> { res, rej -> en.file({ f: dynamic -> res(f) }, { e: dynamic -> rej(e) }) }.await()
                    out.add(Walked(prefix + str(en.name), file))
                } else if (truthy(en.isDirectory)) {
                    if (str(en.name) in Ingest.SKIP_DIRS) continue
                    out.addAll(walkEntries(en, prefix + str(en.name) + "/"))
                }
            }
        }
        return out
    }

    private suspend fun uploadFolder(entry: dynamic) {
        val files = walkEntries(entry, "")
        // Chrome hides .git from the walker — classify by build-system markers instead
        val kind = Ingest.kind(files.map { it.rel })
        val b: dynamic = fetchJson("/_project/" + encUri(str(entry.name).lowercase()) + "/begin?kind=" + kind, methodInit("POST"))
        if (b.verdict != "ok") { river("deopt", "upload refused: " + str(orr(b.detail, "?"))); return }
        val db = str(b.name)
        river("commit", db + " classified " + (if (kind == "project") "PROJECT (build markers)" else "assets pile"))
        var sent = 0; var skipped = 0; var failed = 0
        // pack small files into ~3MB framed batches: one round trip per batch, not per file
        val frames = ArrayList<dynamic>(); var packed = 0.0
        suspend fun flushBatch() {
            if (frames.isEmpty()) return
            val parts = frames.toTypedArray(); frames.clear(); packed = 0.0
            val blob: dynamic = js("new Blob(parts)")
            val init: dynamic = newObject(); init.method = "POST"
            val headers: dynamic = newObject(); headers["Content-Type"] = "application/octet-stream"; init.headers = headers; init.body = blob
            val r = window.fetch("/_project/" + encUri(db) + "/putBatch", init).await()
            if (r.ok) {
                val j: dynamic = r.json().await()
                sent += num(orr(j.stored, 0)).toInt(); failed += num(orr(j.failed, 0)).toInt()
                river("commit", db + ": " + sent + "/" + files.size)
            } else failed++
        }
        fun frame(rel: String, buf: ArrayBuffer) {
            val pb = rel.encodeToByteArray()
            frames.add(Ingest.int32(pb.size)); frames.add(pb); frames.add(Ingest.int64(buf.byteLength.toLong())); frames.add(buf)
            packed += 12 + pb.size + buf.byteLength
        }
        for (f in files) {
            frame(f.rel, f.file.arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await())
            if (packed > Ingest.BATCH_BYTES || frames.size >= Ingest.BATCH_PARTS) flushBatch()
        }
        flushBatch()
        river("commit", "project db /" + db + " — " + sent + " docs uploaded" + (if (skipped > 0) " ($skipped >3.5MB skipped)" else "") + (if (failed > 0) " · $failed FAILED" else ""))
        landTerritory(db)
    }

    suspend fun landTerritory(name: String) {
        loadMapNow()
        window.requestAnimationFrame {
            val r = root
            if (r != null) { layout(r); r.children[name]?.let { flyTo(it) } }
        }
    }

    fun pollTerritory(name: String, tries: Int) {
        if (tries > 120) { river("deopt", "mount of $name never landed — check the daemon log"); return }
        window.setTimeout({
            scope.launch {
                loadMapNow()
                if (name in PROJECT_DBS) { river("commit", "project db /$name landed"); landTerritory(name) }
                else pollTerritory(name, tries + 1)
            }
        }, 5000)
    }

    // ── ⚑ feedback: opens a GitHub issue PREFILLED with this view's coordinates ──
    fun forgeIssue(surface: String, coords: dynamic) {
        val body = "**Surface:** " + surface + "\n**URL:** " + window.location.href + "\n**Coordinates:**\n```json\n" +
            jsonIndent(coords, 1) + "\n```\n\n**What I did:**\n\n**What happened:**\n\n**What I expected:**\n"
        window.open("https://github.com/jnorthrup/TrikeShed/issues/new?labels=quickstart-feedback" +
            "&title=" + encUri("[$surface] ") + "&body=" + encUri(body), "_blank")
    }

    fun qsInstall() {
        val d = document.createElement("div") as HTMLElement; d.asDynamic().dataset.qs = "1"
        d.style.cssText = "position:fixed;inset:0;background:rgba(0,0,0,.65);z-index:99;display:flex;align-items:center;justify-content:center"
        d.innerHTML = "<div style=\"background:#11151e;border:1px solid #f29111;border-radius:8px;padding:18px 22px;max-width:580px;color:#d8dce6;font:13px monospace\">" +
            "<b style=\"color:#f29111\">Run TrikeShed in anger — five minutes, one port</b>" +
            "<pre id=\"qsCmds\" style=\"background:#0b0e14;padding:10px;border-radius:4px;margin:10px 0;user-select:text;white-space:pre-wrap\">git clone git@github.com:jnorthrup/TrikeShed.git && cd TrikeShed\n./gradlew hotswapFeed\nbin/oroboros-daemon --watch</pre>" +
            "<div style=\"color:#7b8496;margin-bottom:10px\">then open http://localhost:8888 — drop a REAL folder on this terrain, run a stored program via POST /api/lcnc/run, abuse the board.</div>" +
            "<button onclick=\"navigator.clipboard.writeText(document.getElementById('qsCmds').textContent)\" style=\"background:#161b26;border:1px solid #3ddc84;color:#3ddc84;border-radius:4px;padding:4px 12px;cursor:pointer;font:inherit\">copy commands</button>" +
            "<a href=\"https://github.com/jnorthrup/TrikeShed#run-it-in-anger--please\" target=\"_blank\" style=\"color:#3fd0ff;margin-left:10px\">README ↗</a>" +
            "<button onclick=\"document.querySelector('div[data-qs]').remove()\" style=\"background:none;border:none;padding:0;font:inherit;color:#7b8496;margin-left:14px;cursor:pointer\">close</button></div>"
        d.onclick = { e: Event -> if (e.target === d) d.remove() }
        document.body!!.appendChild(d)
    }

    private fun bindChrome() {
        q("qsLink").addEventListener("click", { qsInstall() })
        q("fbLink").addEventListener("click", {
            val coords: dynamic = newObject()
            coords.mode = mode; coords.layer = layer; coords.skin = skin; coords.fog = fog
            val c: dynamic = newObject(); c.s = num(toFixed(cam.s, 3)); c.ox = jsRound(cam.ox); c.oy = jsRound(cam.oy)
            coords.cam = c
            coords.focus = if (sel != null) pathOf(sel) else if (hover != null) pathOf(hover) else null
            coords.crumb = (q("crumb").textContent ?: "").take(160)
            coords.projectDbs = PROJECT_DBS.toTypedArray(); coords.docs = root?.docs ?: 0
            forgeIssue("graal", coords)
        })
        val onResize: () -> Unit = { q("river").style.top = str(ceil(q("hud").getBoundingClientRect().bottom + 4)) + "px" }
        val observer: dynamic = js("new ResizeObserver(onResize)")
        observer.observe(q("hud"))
    }

    fun inputValue(id: String): String = (q(id).unsafeCast<HTMLInputElement>()).value
}
