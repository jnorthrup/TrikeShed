package borg.trikeshed.graal.console

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A graph-mode body: one territory, placed by the force step. */
class GraphBody(val k: String, val n: TerrainNode, var x: Double, var y: Double, val r: Double) {
    var vx = 0.0
    var vy = 0.0
}

/** A graph-mode spring between two bodies, weighted by shared-blob hub degree. */
class GraphEdge(val a: Int, val b: Int, val weight: Double)

/** One shared-blob hub from `/api/graal/dag`: the ids sharing it and its degree. */
class DagHub(val ids: List<String>, val degree: Double)

/** Graph mode: territories as bodies (top level, or depth 2 under big top levels), shared-blob hubs as springs. */
class TerrainGraph {
    val nodes = ArrayList<GraphBody>()
    val edges = ArrayList<GraphEdge>()

    fun build(root: TerrainNode, hubs: List<DagHub>) {
        val depth2 = LinkedHashMap<String, TerrainNode>()
        for (c in root.children.values) {
            if (c.children.isNotEmpty() && c.docs > 40) for (g in c.children.values) depth2[c.name + "/" + g.name] = g
            else depth2[c.name] = c
        }
        val idx = HashMap<String, Int>()
        nodes.clear(); edges.clear()
        var i = 0
        for ((k, n) in depth2) {
            nodes.add(GraphBody(k, n,
                Terrain.W / 2 + cos(i * 2.4) * (160 + (8 * i) % 300),
                Terrain.H / 2 + sin(i * 2.4) * (120 + (7 * i) % 220),
                6 + log2(1 + n.bytes / 1024) * 1.6))
            idx[k] = nodes.size - 1
            i++
        }
        fun key(id: String): String {
            val p = id.split('/').filter { it.isNotEmpty() }
            val two = p.getOrElse(0) { "undefined" } + "/" + p.getOrElse(1) { "undefined" }
            return if (idx.containsKey(two)) two else p.getOrElse(0) { "undefined" }
        }
        val w = LinkedHashMap<String, Double>()
        for (h in hubs) {
            val ks = h.ids.map(::key).distinct().filter { idx.containsKey(it) }
            for (a in ks.indices) for (b in a + 1 until ks.size) {
                val e = if (ks[a] < ks[b]) ks[a] + "|" + ks[b] else ks[b] + "|" + ks[a]
                w[e] = (w[e] ?: 0.0) + h.degree
            }
        }
        for ((e, weight) in w) {
            val (a, b) = e.split('|')
            edges.add(GraphEdge(idx[a]!!, idx[b]!!, weight))
        }
    }

    /** One force step: pairwise repulsion, weighted springs toward 140, centering, damping. */
    fun step(drag: GraphBody?) {
        val n = nodes.size
        if (n == 0) return
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = nodes[i]; val b = nodes[j]
            var dx = b.x - a.x; var dy = b.y - a.y
            val d2 = dx * dx + dy * dy + 40
            val f = 2600 / d2
            val d = sqrt(d2)
            dx /= d; dy /= d
            a.vx -= dx * f; a.vy -= dy * f; b.vx += dx * f; b.vy += dy * f
        }
        for (e in edges) {
            val a = nodes[e.a]; val b = nodes[e.b]
            val dx = b.x - a.x; val dy = b.y - a.y
            val d = sqrt(dx * dx + dy * dy) + 1e-3
            val f = (d - 140) * 0.004 * min(1.0, log2(1 + e.weight) / 8)
            a.vx += dx / d * f * d * 0.01; a.vy += dy / d * f * d * 0.01
            b.vx -= dx / d * f * d * 0.01; b.vy -= dy / d * f * d * 0.01
        }
        val cx = Terrain.W / 2; val cy = Terrain.H / 2
        for (g in nodes) {
            g.vx += (cx - g.x) * 0.0004; g.vy += (cy - g.y) * 0.0004
            g.vx *= 0.86; g.vy *= 0.86
            if (g !== drag) { g.x += g.vx; g.y += g.vy }
        }
    }

    /** The body under world point ([wx], [wy]) within its radius + 6. */
    fun at(wx: Double, wy: Double): GraphBody? = nodes.firstOrNull { g ->
        val dx = g.x - wx; val dy = g.y - wy
        dx * dx + dy * dy < (g.r + 6) * (g.r + 6)
    }
}

/** Tree mode: radial drilldown angles over 3 levels of [center]. */
class Radial(center: TerrainNode) {
    val leaves = ArrayList<TerrainNode>()
    val angleOf = LinkedHashMap<TerrainNode, Double>()

    init {
        fun count(n: TerrainNode, depth: Int) {
            if (depth == 3 || n.children.isEmpty()) { leaves.add(n); return }
            for (c in n.children.values) count(c, depth + 1)
        }
        count(center, 0)
        leaves.forEachIndexed { i, l -> angleOf[l] = (i.toDouble() / leaves.size) * PI * 2 - PI / 2 }
        fun place(n: TerrainNode): Double {
            angleOf[n]?.let { return it }
            val a = n.children.values.map { place(it) }.average()
            angleOf[n] = a
            return a
        }
        place(center)
    }
}

internal fun log2(x: Double): Double = ln(x) / ln(2.0)
