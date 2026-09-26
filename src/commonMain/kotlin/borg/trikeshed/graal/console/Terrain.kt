package borg.trikeshed.graal.console

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The Graal console terrain: store rows `[id, bytes, seq, gen, code]` as a nested territory tree,
 * laid out by a squarified treemap in a fixed 1600×1000 world. Layout insets are proportional,
 * so world rects never move with zoom; a node is laid lazily the first time it is drawn or hit.
 */
class TerrainNode(val name: String, var parent: TerrainNode?) {
    val children = LinkedHashMap<String, TerrainNode>()
    var bytes = 0.0
    var mass = 0.0
    var docs = 0
    var leaf = false
    var id: String? = null
    var seq = 0L
    var gen = 1L
    var maxSeq = 0L
    var hue = 0
    var laid = false
    /** Topology category bits of every leaf under this node (graal-terrain mask). */
    var categories = 0
    /** Runtime detail carried by a heap/runtime row, else null. */
    var detail: Any? = null
    var x = 0.0; var y = 0.0; var w = 0.0; var h = 0.0
    /** True once a rect has been assigned (root at build, children at their parent's layout). */
    var placed = false

    fun rect(x: Double, y: Double, w: Double, h: Double) { this.x = x; this.y = y; this.w = w; this.h = h; placed = true }
    fun path(): String {
        val parts = ArrayList<String>()
        var n: TerrainNode? = this
        while (n != null && n.parent != null) { parts.add(0, n.name); n = n.parent }
        return parts.joinToString("/")
    }
}

/** One terrain row. */
class TerrainRow(val id: String, val bytes: Double, val seq: Long, val gen: Long, val code: Int, val bit: Int = 0, val detail: Any? = null)

object Terrain {
    const val W = 1600.0
    const val H = 1000.0

    private val HUES = mapOf("forge" to 300, "projects" to 172, ".git" to 262, "pointcut" to 8, "_design" to 318,
        "memories" to 210, "lost-found" to 48, "jules" to 132, "vms" to 282)

    /** Content-addressed storage planes: real and divable, but they must not own the map. */
    private val VAULTS = setOf(".git", "lost-found", "subtree-cache", "objects", "worktrees")

    fun hueOf(name: String): Int {
        HUES[name]?.let { return it }
        var h = 0u
        for (c in name) h = h * 31u + c.code.toUInt()
        return (h % 360u).toInt()
    }

    fun massOf(bytes: Double, topName: String): Double {
        val m = ln(2 + bytes) / ln(2.0)
        return if (topName in VAULTS) m * 0.18 else m
    }

    /** The tree result: root, leaves by id, the largest seq. */
    class Built(val root: TerrainNode, val byId: Map<String, TerrainNode>, val maxSeq: Long)

    /** Build the territory tree. `similarity` splits by AngularCodec code nibbles instead of id path. */
    fun build(rows: List<TerrainRow>, similarity: Boolean): Built {
        val root = TerrainNode("trikeshed", null).apply { rect(0.0, 0.0, W, H) }
        val byId = HashMap<String, TerrainNode>()
        var maxSeq = 1L
        for (row in rows) {
            val parts = if (similarity)
                listOf("code-" + ((row.code shr 8) and 0xFF).toString(16).padStart(2, '0'), "code-" + (row.code and 0xFFFF).toString(4), row.id)
            else row.id.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            var n = root
            val mass = massOf(row.bytes, parts[0])
            for (p in parts) {
                var c = n.children[p]
                if (c == null) {
                    c = TerrainNode(p, n)
                    c.hue = if (n === root) (if (similarity) row.code % 360 else hueOf(p))
                    else if (similarity) hueOf(p + "#" + row.code) else n.hue
                    n.children[p] = c
                }
                n = c
                n.bytes += row.bytes; n.mass += mass; n.docs++
            }
            n.leaf = true; n.id = row.id; n.seq = row.seq; n.gen = row.gen; n.mass = mass; n.detail = row.detail
            if (row.bit != 0) { var c: TerrainNode? = n; while (c != null) { c.categories = c.categories or row.bit; c = c.parent } }
            byId[row.id] = n
            if (n.seq > maxSeq) maxSeq = n.seq
            var a = n.parent
            while (a != null) { if (a.maxSeq < n.seq) a.maxSeq = n.seq; a = a.parent }
            root.bytes += row.bytes; root.mass += mass; root.docs++
        }
        return Built(root, byId, maxSeq)
    }

    /**
     * Squarified treemap of [n]'s children inside its rect: rows of children sorted by mass.
     * [shrinkTotal] subtracts each laid row from the total (graal-terrain); the console keeps it fixed.
     */
    fun layout(n: TerrainNode, root: TerrainNode, shrinkTotal: Boolean = false) {
        if (n.laid) return
        n.laid = true
        val kids = n.children.values.sortedByDescending { it.mass }
        if (kids.isEmpty()) return
        val pad = min(n.w, n.h) * 0.015
        val title = if (n !== root) n.h * 0.07 else 0.0
        var x = n.x + pad; var y = n.y + pad + title; var w = n.w - 2 * pad; var h = n.h - 2 * pad - title
        if (w <= 0 || h <= 0) { for (k in kids) k.rect(n.x, n.y, 0.0, 0.0); return }
        var total = kids.sumOf { it.mass }.takeIf { it != 0.0 } ?: 1.0
        var i = 0
        while (i < kids.size) {
            val vert = w < h
            val side = if (vert) w else h
            val take = ArrayList<TerrainNode>()
            var mass = 0.0
            var best = Double.POSITIVE_INFINITY
            for (j in i until kids.size) {
                val m2 = mass + kids[j].mass
                val breadth = (if (vert) h else w) * (m2 / total)
                var worst = 0.0
                for (k in take + kids[j]) {
                    val s1 = (k.mass / m2) * side
                    val b = if (breadth == 0.0) 1e-9 else breadth
                    worst = max(worst, max(s1 / b, b / s1))
                }
                if (worst <= best || take.isEmpty()) { take.add(kids[j]); mass = m2; best = worst } else break
            }
            val breadth = (if (vert) h else w) * (mass / total)
            var off = 0.0
            for (k in take) {
                val kf = k.mass / mass
                if (vert) k.rect(x + off * w, y, w * kf, breadth) else k.rect(x, y + off * h, breadth, h * kf)
                off += kf
            }
            if (vert) { y += breadth; h -= breadth } else { x += breadth; w -= breadth }
            if (shrinkTotal) total -= mass
            i += take.size
            val rem = kids.drop(i).sumOf { it.mass }
            if (rem <= 0) { for (j in i until kids.size) kids[j].rect(x, y, 0.0, 0.0); break }
        }
    }

    fun invalidate(n: TerrainNode) { n.laid = false; for (c in n.children.values) invalidate(c) }

    /** The deepest node under screen point ([cx], [cy]) whose parent is drawn wide enough to show children. */
    fun hit(root: TerrainNode?, cam: Camera, cx: Double, cy: Double): TerrainNode? {
        if (root == null) return null
        val wx = cam.ox + cx / cam.s; val wy = cam.oy + cy / cam.s
        var n: TerrainNode? = root
        var found: TerrainNode = root
        while (n != null) {
            found = n
            var next: TerrainNode? = null
            if (n.children.isNotEmpty() && n.w * cam.s > 26) {
                layout(n, root)
                for (c in n.children.values) if (c.placed && wx >= c.x && wx <= c.x + c.w && wy >= c.y && wy <= c.y + c.h) { next = c; break }
            }
            n = next
        }
        return if (found === root) null else found
    }

    /**
     * graal-terrain hit over WORLD point ([wx], [wy]): every containing child is visited; a node
     * outside [mask] clears the find. Children open when drawn wider than 26 and taller than 18 px.
     */
    fun hitMasked(root: TerrainNode?, s: Double, wx: Double, wy: Double, mask: Int): TerrainNode? {
        if (root == null) return null
        var found: TerrainNode? = null
        fun visit(n: TerrainNode) {
            if (!n.placed || wx < n.x || wy < n.y || wx > n.x + n.w || wy > n.y + n.h) return
            if (n.categories and mask == 0) { found = null; return }
            found = n
            if (n.children.isNotEmpty() && n.w * s > 26 && n.h * s > 18) {
                layout(n, root, true)
                for (c in n.children.values) visit(c)
            }
        }
        visit(root)
        return if (found === root) null else found
    }

    fun fmtBytes(b: Double): String = when {
        b > 1048576 -> fixed(b / 1048576, 1) + "M"
        b > 1024 -> fixed(b / 1024, 1) + "K"
        else -> num(b) + "B"
    }

    /** A number as JS prints it: integral values without a fraction. */
    fun num(b: Double): String = if (b == kotlin.math.floor(b) && kotlin.math.abs(b) < 1e15) b.toLong().toString() else b.toString()

    fun fixed(v: Double, digits: Int): String {
        var p = 1.0; repeat(digits) { p *= 10 }
        val r = kotlin.math.floor(v * p + 0.5) / p
        val s = r.toString()
        val dot = s.indexOf('.')
        return if (digits == 0) s.substringBefore('.') else if (dot < 0) s + "." + "0".repeat(digits) else s.padEnd(dot + 1 + digits, '0').substring(0, dot + 1 + digits)
    }
}

/** screen = (world - o) * s */
class Camera(var s: Double = 1.0, var ox: Double = 0.0, var oy: Double = 0.0) {
    fun fit(width: Double, height: Double) {
        s = min(width / Terrain.W, height / Terrain.H) * 0.96
        ox = Terrain.W / 2 - width / (2 * s)
        oy = Terrain.H / 2 - height / (2 * s) + 30 / s
    }
}

/** The three canvas skins: ops console, AI blackboard (chalk), developer whiteboard (marker). */
class Skin(
    val bg: String,
    val fill: (Int, Int) -> String,
    val stroke: (Int, Int) -> String,
    val hot: (Int) -> String,
    val label: (Int) -> String,
    val text: String, val sub: String, val faint: String, val mini: String,
    val miniTile: (Int) -> String,
    val lw: Double,
) {
    companion object {
        val ORDER = listOf("ops", "chalk", "marker")
        val ALL = mapOf(
            "ops" to Skin("#0b0e14", { h, l -> "hsl($h 42% $l%)" }, { h, l -> "hsl($h 45% ${min(l + 14, 40)}%)" },
                { h -> "hsl($h 90% 60%)" }, { h -> "hsl($h 70% 72%)" }, "rgba(216,220,230,.82)", "rgba(123,132,150,.9)",
                "rgba(216,220,230,.55)", "#11151e", { h -> "hsl($h 40% 22%)" }, 0.75),
            "chalk" to Skin("#1b2420", { h, l -> "hsl($h 18% ${max(l - 2, 9)}%)" }, { h, _ -> "hsla($h,35%,82%,.55)" },
                { h -> "hsla($h,80%,88%,.95)" }, { h -> "hsla($h,45%,86%,.95)" }, "rgba(233,239,232,.85)", "rgba(147,163,152,.9)",
                "rgba(233,239,232,.5)", "#212b26", { h -> "hsl($h 22% 26%)" }, 1.4),
            "marker" to Skin("#f6f5f0", { h, l -> "hsl($h 60% ${96 - min(l, 14)}%)" }, { h, _ -> "hsl($h 55% 42%)" },
                { h -> "hsl($h 80% 34%)" }, { h -> "hsl($h 65% 30%)" }, "rgba(34,38,43,.88)", "rgba(118,125,136,.95)",
                "rgba(34,38,43,.5)", "#ffffff", { h -> "hsl($h 55% 80%)" }, 1.6),
        )
    }
}

/** Ids whose content the console never fetches or shows. */
object Sensitive {
    private val ID = Regex("(^|[/:._-])(credential|credentials|secret|secrets|token|tokens|apikey|api[-_]?key|keymux)([/:._-]|$)", RegexOption.IGNORE_CASE)
    val TEXT = Regex("\\b(Bearer\\s+[A-Za-z0-9._+/=-]{16,}|(?:sk|rk|pk|ghp|github_pat|xox[baprs]|ya29|AIza)[A-Za-z0-9._-]{12,})\\b", RegexOption.IGNORE_CASE)
    fun docId(id: String): Boolean {
        val s = id.replace('\\', '/')
        val f = s.substringAfterLast('/').lowercase()
        return f == ".env" || f.startsWith(".env.") || f.endsWith(".env") || ID.containsMatchIn(s)
    }
    fun fieldName(name: String): Boolean {
        val k = name.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
        return Regex("apikey|token|password|secret|credential|authorization").containsMatchIn(k) ||
            Regex("^(accesskey|accesskeyid|secretkey|clientsecret|privatekey|providerkey|bearertoken|key)$").matches(k)
    }
    fun display(k: String, v: Any?): String {
        val s = v?.toString() ?: ""
        return if (fieldName(k) || TEXT.containsMatchIn(s)) "[redacted]" else s.replace(TEXT, "[redacted]")
    }
}
