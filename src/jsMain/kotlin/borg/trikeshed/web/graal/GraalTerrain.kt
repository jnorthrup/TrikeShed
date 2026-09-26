package borg.trikeshed.web.graal

import borg.trikeshed.graal.console.Camera
import borg.trikeshed.graal.console.Skin
import borg.trikeshed.graal.console.Terrain
import borg.trikeshed.graal.console.TerrainNode
import borg.trikeshed.graal.console.TerrainRow
import borg.trikeshed.graal.console.Topology
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement

/** `[id, bytes, seq, gen, code, detail?]` JS rows → terrain rows carrying their topology bit. */
fun terrainRows(rows: Array<dynamic>): List<TerrainRow> = rows.map { r ->
    val detail: dynamic = r[5]
    val runtimeCategory: String? = if (detail != null && truthy(detail.runtime)) str(detail.category) else null
    TerrainRow(str(r[0]), num(r[1]), if (truthy(r[2])) num(r[2]).toLong() else 0L, if (truthy(r[3])) num(r[3]).toLong() else 1L,
        num(r[4]).toInt(), Topology.bit(Topology.category(str(r[0]), runtimeCategory)), detail)
}

/**
 * The object-viewer terrain (graal-terrain.js createGraalTerrain): its own geometry and camera,
 * category mask, no heat. [invalidate] asks the host to repaint after a lazy fetch lands.
 */
class GraalTerrain(canvas: HTMLCanvasElement, invalidate: () -> Unit) {
    private val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    private var projectDbs = setOf<String>()
    private var layer = 1
    private var mask = Topology.ALL
    private var root: TerrainNode? = null
    private var byId: Map<String, TerrainNode> = emptyMap()
    private var maxSeq = 1L
    private var cam = Camera()
    private val painter = TerrainPainter({ "/api/graal/content?id=" + encUri(it) }, true, invalidate)
    private val setMaskInvalidate = invalidate

    fun setRows(rows: Array<dynamic>, rect: dynamic, dbs: Array<String>?) {
        projectDbs = dbs?.toSet() ?: emptySet()
        val built = Terrain.build(terrainRows(rows), false)
        root = built.root; byId = built.byId; maxSeq = built.maxSeq
        built.root.rect(num(rect.x), num(rect.y), num(rect.w), num(rect.h))
        Terrain.invalidate(built.root)
    }

    fun draw(camera: dynamic, width: Double, height: Double) {
        cam = Camera(num(camera.s), num(camera.ox), num(camera.oy))
        val r = root ?: return
        painter.drawNode(TerrainPainter.Frame(ctx, cam, width, height, Skin.ALL.getValue("ops"), r, null, false, { 0.0 }, layer, maxSeq, mask), r, 0)
    }

    fun setLayer(value: Int) { layer = value }
    fun setMask(value: Int) { mask = value and Topology.ALL; setMaskInvalidate() }
    fun visible(n: TerrainNode?): Boolean = n != null && (n.categories and mask) != 0
    fun hit(x: Double, y: Double): TerrainNode? = Terrain.hitMasked(root, cam.s, x, y, mask)

    fun boxFor(id: String): TerrainNode? {
        val n = byId[id] ?: return null
        val path = ArrayList<TerrainNode>()
        var a: TerrainNode? = n
        while (a != null) { path.add(0, a); a = a.parent }
        for (i in 1 until path.size) { if (!path[i - 1].placed) return null; Terrain.layout(path[i - 1], root!!, true) }
        return if (n.placed) n else null
    }

    fun nodeFor(id: String): TerrainNode? = byId[id]

    fun url(id: String): String {
        val parts = id.split('/')
        return if (parts[0] in projectDbs) "/" + parts.joinToString("/") { encUri(it) } else "/trikeshed/" + parts.joinToString("/") { encUri(it) }
    }

    companion object {
        /** The JS-facing node view: `{name, id, rect, detail, categories, leaf, bytes, docs}`. */
        fun view(n: TerrainNode?): dynamic {
            if (n == null) return null
            val o: dynamic = newObject()
            o.name = n.name; o.id = n.id; o.detail = n.detail; o.categories = n.categories; o.leaf = n.leaf
            o.bytes = n.bytes; o.docs = n.docs; o.seq = n.seq; o.gen = n.gen; o.hue = n.hue
            if (n.placed) { val r: dynamic = newObject(); r.x = n.x; r.y = n.y; r.w = n.w; r.h = n.h; o.rect = r }
            o.node = n
            return o
        }

        /** Publish `window.GraalTopology` and `window.createGraalTerrain`. */
        fun install() {
            val topo: dynamic = newObject()
            topo.categories = Topology.categories.map { c ->
                val o: dynamic = newObject(); o.id = c.id; o.label = c.label; o.color = c.color; o.hint = c.hint; o
            }.toTypedArray()
            topo.category = { row: dynamic ->
                val d: dynamic = row[5]
                Topology.category(str(row[0]), if (d != null && truthy(d.runtime)) str(d.category) else null)
            }
            topo.bit = { id: String -> Topology.bit(id) }
            topo.all = Topology.ALL
            topo.heapRows = { heap: dynamic, vitals: dynamic -> heapRows(heap ?: newObject(), vitals ?: newObject()) }
            window.asDynamic().GraalTopology = topo
            window.asDynamic().createGraalTerrain = { options: dynamic ->
                val t = GraalTerrain(options.canvas.unsafeCast<HTMLCanvasElement>()) { options.invalidate() }
                val api: dynamic = newObject()
                api.setRows = { rows: dynamic, rect: dynamic, dbs: dynamic -> t.setRows(rows.unsafeCast<Array<dynamic>>(), rect, dbs?.unsafeCast<Array<String>>()) }
                api.draw = { camera: dynamic, w: Double, h: Double -> t.draw(camera, w, h) }
                api.setLayer = { v: Int -> t.setLayer(v) }
                api.setMask = { v: Int -> t.setMask(v) }
                api.visible = { n: dynamic -> n != null && t.visible(n.node.unsafeCast<TerrainNode?>()) }
                api.hit = { x: Double, y: Double -> view(t.hit(x, y)) }
                api.boxFor = { id: String -> view(t.boxFor(id))?.rect }
                api.nodeFor = { id: String -> view(t.nodeFor(id)) }
                api.url = { id: String -> t.url(id) }
                api.hueOf = { name: String -> Terrain.hueOf(name) }
                api
            }
        }

        /** Runtime rows: live histogram, JFR allocation samples, GC pool blocks. */
        fun heapRows(heap: dynamic, vitals: dynamic): Array<dynamic> {
            val rows = ArrayList<dynamic>()
            val atMs: dynamic = orr(heap.atMs, null)
            fun add(category: String, name: dynamic, bytes: dynamic, detail: dynamic) {
                if (typeOf(name) != "string" || !isFiniteNum(bytes) || num(bytes) <= 0) return
                val d: dynamic = js("Object.assign({runtime:true},{category:category,name:name},detail)")
                rows.add(arrayOf<dynamic>("runtime:" + category + "/" + encUri(str(name)), bytes, 0, 1, 0, d))
            }
            val live = (if (isArray(heap.rows)) heap.rows else js("[]")).slice(0, 256).unsafeCast<Array<dynamic>>()
            for (r in live) if (truthy(r)) {
                val d: dynamic = newObject(); d.source = "GC.class_histogram"; d.atMs = atMs; d.count = r.count; d.bytes = r.bytes
                add("live", r["class"], r.bytes, d)
            }
            val alloc = (if (isArray(heap.allocation)) heap.allocation else js("[]")).slice(0, 256).unsafeCast<Array<dynamic>>()
            for (r in alloc) if (truthy(r)) {
                val d: dynamic = newObject(); d.source = "JFR ObjectAllocationSample"; d.atMs = atMs; d.sampledBytes = r.bytes
                add("allocation", r["class"], r.bytes, d)
            }
            val lane: dynamic = (if (vitals.gc != null) vitals.gc.lane else null) ?: newObject()
            val pools = (if (isArray(lane.pools)) lane.pools else js("[]")).slice(0, 64).unsafeCast<Array<dynamic>>()
            for (p in pools) if (truthy(p)) {
                val d: dynamic = newObject(); d.source = "JFR GCHeapMemoryPoolUsage"; d.atMs = orr(lane.atMs, null)
                d.usedBytes = p.lastUsedBytes; d.committedBytes = p.lastCommittedBytes; d.samples = p.samples
                add("blocks", p.pool, p.lastUsedBytes, d)
            }
            return rows.toTypedArray()
        }
    }
}
