package narchy.spacegraph

import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*
import kotlin.math.*

/**
 * The curator scope: a live tree of LCNC statements grown from the activity ring. Each drain adds
 * the nodes the walk touched (a program is a trunk, a ring a bough, a statement a leaf) and the
 * events that lit them; each frame eases the camera onto the production being followed, unless the
 * operator has taken the camera, in which case it stays theirs for [hold] ms after their last touch
 * and then glides back to follow. The path a run takes is left behind as a trail.
 *
 * Platform adapters feed [drain] with `/api/lcnc/trail` values and a local clock, route input to
 * [pan]/[zoom]/[pick], and draw [frame]'s plan; everything they show is decided here.
 */
class CuratorScope(var viewport: Viewport = Viewport(1, 1)) {
    /** How long the camera stays where the operator left it before returning to follow. */
    var hold = 6000.0
    /** True while the operator has pinned the camera; follow resumes only on [follow]. */
    var pinned = false
    /** The run being followed; -1 follows whichever run moved last. */
    var followRun = -1
    var selected = -1
    var hovered = -1

    // ── node table (ordinals match the daemon's ring) ──
    private val keys = ArrayList<String>()
    private val types = ArrayList<String>()
    private val parents = IntAccumulatorList()
    private val born = DoubleList(); private val lastBegin = DoubleList(); private val lastEnd = DoubleList()
    private val activeSince = DoubleList(); private val spent = DoubleList(); private val runs = IntAccumulatorList()
    private val lastKind = IntAccumulatorList(); private val lastNote = ArrayList<String?>()
    private val x = DoubleList(); private val y = DoubleList()
    private var laidOut = 0
    private var runNames: List<String> = emptyList()

    // ── recent events (local time) ──
    private val cap = 4096
    private val evT = DoubleArray(cap); private val evKind = IntArray(cap); private val evRun = IntArray(cap)
    private val evNode = IntArray(cap); private val evNote = arrayOfNulls<String>(cap)
    private var evHead = 0L

    var cursor = 0L; private set
    var dropped = 0L; private set
    var lastDrained = 0; private set
    private var lastRun = -1

    // ── camera ──
    private var cx = 0.0; private var cy = 0.0; private var zoom = 1.0
    private var touched = -1e18
    private var now = 0.0

    val nodes: Int get() = keys.size

    /** Folds one `/api/lcnc/trail` drain in, stamping server times onto the local clock [localNow]. */
    fun drain(value: Map<*, *>, localNow: Double) {
        now = localNow
        if (value["reset"] == true) clear()
        val offset = localNow - num(value["now"])
        val from = num(value["nodesFrom"]).toInt()
        val nk = value["nodeKeys"] as? List<*> ?: emptyList<Any>()
        val nt = value["nodeTypes"] as? List<*> ?: emptyList<Any>()
        val np = value["nodeParents"] as? List<*> ?: emptyList<Any>()
        for (i in nk.indices) if (from + i == keys.size) {
            keys.add(nk[i].toString()); types.add(nt[i].toString()); parents.add(num(np[i]).toInt())
            born.add(localNow); lastBegin.add(-1e18); lastEnd.add(-1e18); activeSince.add(-1.0); spent.add(0.0)
            runs.add(0); lastKind.add(-1); lastNote.add(null); x.add(0.0); y.add(0.0)
        }
        (value["runs"] as? List<*>)?.let { r -> runNames = r.map { it.toString() } }
        val t = value["t"] as? List<*> ?: emptyList<Any>()
        val kind = value["kind"] as? List<*> ?: emptyList<Any>()
        val run = value["run"] as? List<*> ?: emptyList<Any>()
        val node = value["node"] as? List<*> ?: emptyList<Any>()
        val note = value["note"] as? List<*> ?: emptyList<Any>()
        for (i in t.indices) {
            val at = num(t[i]) + offset; val k = num(kind[i]).toInt(); val r = num(run[i]).toInt(); val n = num(node[i]).toInt()
            val s = (evHead % cap).toInt()
            evT[s] = at; evKind[s] = k; evRun[s] = r; evNode[s] = n; evNote[s] = note[i]?.toString(); evHead++
            lastRun = r
            if (n !in 0 until keys.size) continue
            lastKind[n] = k; note[i]?.let { lastNote[n] = it.toString() }
            when (k) {
                BEGIN -> { lastBegin[n] = at; activeSince[n] = at }
                END -> { lastEnd[n] = at; if (activeSince[n] >= 0) spent[n] = spent[n] + (at - activeSince[n]); activeSince[n] = -1.0; runs[n] = runs[n] + 1 }
                FAIL -> { lastEnd[n] = at; activeSince[n] = -1.0 }
                else -> lastBegin[n] = at
            }
            // A ring lights the boughs above it, so a deep production shows its whole branch.
            var p = parents[n]; var guard = 0
            while (p >= 0 && guard++ < 64) { if (lastBegin[p] < at) lastBegin[p] = at; p = parents[p] }
        }
        cursor = (value["head"] as? Number)?.toLong() ?: cursor
        dropped += (value["dropped"] as? Number)?.toLong() ?: 0L
        lastDrained = t.size
        if (keys.size != laidOut) layout()
    }

    /** Forgets every node and event: the daemon's ring restarted, so ordinals mean new nodes. */
    private fun clear() {
        keys.clear(); types.clear(); lastNote.clear()
        for (c in listOf(born, lastBegin, lastEnd, activeSince, spent, x, y)) c.n = 0
        for (c in listOf(parents, runs, lastKind)) c.n = 0
        evHead = 0; cursor = 0; laidOut = 0; lastRun = -1; selected = -1; hovered = -1; runNames = emptyList()
    }

    // ── layout: each program a tree growing upward; a bough's statements climb it in rows of [ROW],
    //    so a ring of forty statements grows tall instead of wide ──
    private fun layout() {
        val kids = HashMap<Int, MutableList<Int>>()
        val roots = ArrayList<Int>()
        for (i in keys.indices) { val p = parents[i]; if (p < 0) roots.add(i) else kids.getOrPut(p) { ArrayList() }.add(i) }
        val w = DoubleArray(keys.size); val h = DoubleArray(keys.size)
        fun measure(n: Int) {
            val c = kids[n]
            if (c.isNullOrEmpty()) { w[n] = SLOT; h[n] = LEVEL; return }
            c.forEach(::measure)
            var width = 0.0; var height = 0.0
            for (row in c.chunked(ROW)) { width = max(width, row.sumOf { w[it] }); height += row.maxOf { h[it] } }
            w[n] = max(SLOT, width); h[n] = height + LEVEL
        }
        // World y is up: the trunk sits at the base and boughs climb above it.
        fun place(n: Int, cx: Double, base: Double) {
            x[n] = cx; y[n] = base
            var rowBase = base + LEVEL
            for ((r, row) in (kids[n] ?: return).chunked(ROW).withIndex()) {
                val width = row.sumOf { w[it] }
                // Alternate rows lean left and right of the bough, like leaves up a stem.
                var left = cx - width / 2 + (if (r % 2 == 0) -SLOT / 4 else SLOT / 4)
                for (k in row) { place(k, left + w[k] / 2, rowBase); left += w[k] }
                rowBase += row.maxOf { h[it] }
            }
        }
        var left = 0.0
        for (r in roots) { measure(r); place(r, left + w[r] / 2, 0.0); left += w[r] + SLOT * 1.5 }
        if (laidOut == 0) fitAll()
        laidOut = keys.size
    }

    private fun fitAll() {
        if (keys.isEmpty()) return
        val b = bounds(keys.indices.toList()); cx = b[0]; cy = b[1]; zoom = b[2]
    }

    /** Center and zoom that frame [ids]: [cx, cy, zoom]. */
    private fun bounds(ids: List<Int>): DoubleArray {
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in ids) { x0 = min(x0, x[i]); x1 = max(x1, x[i]); y0 = min(y0, y[i]); y1 = max(y1, y[i]) }
        val w = max(x1 - x0, SLOT * 3) + SLOT * 2; val h = max(y1 - y0, LEVEL * 2) + LEVEL * 1.5
        val z = min(viewport.width / w, (viewport.height - HUD) / h).coerceIn(.05, 2.4)
        return doubleArrayOf((x0 + x1) / 2, (y0 + y1) / 2, z)
    }

    // ── camera: operator input takes it for [hold] ms; after that it eases back onto the production ──
    fun pan(dx: Double, dy: Double) { cx -= dx / zoom; cy += dy / zoom; touched = now }
    fun zoom(factor: Double, sx: Double, sy: Double) {
        val c = camera().zoomAt(factor, Vec3(sx, sy), viewport); cx = c.center.x; cy = c.center.y; zoom = c.zoom; touched = now
    }
    fun follow() { pinned = false; touched = -1e18 }
    fun pin() { pinned = true; touched = now }
    /** Remaining ms before the camera returns to following; 0 while following, +∞ while pinned. */
    fun holding(): Double = if (pinned) Double.POSITIVE_INFINITY else max(0.0, touched + hold - now)

    private fun camera() = GraphCamera(center = Vec3(cx, cy), zoom = zoom, position = Vec3(cx, cy, 500.0))

    /** The node under screen point ([sx], [sy]), or -1. */
    fun pick(sx: Double, sy: Double): Int {
        val cam = camera(); var best = -1; var bestD = 22.0
        for (i in keys.indices) {
            val p = cam.project(Vec3(x[i], y[i]), viewport) ?: continue
            val d = hypot(p.x - sx, p.y - sy); if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** The run the camera follows: the pinned [followRun], else the last run that moved. */
    private fun followed() = if (followRun >= 0) followRun else lastRun

    /** The production's recent path: the last [n] nodes the followed run began or ended, oldest first. */
    private fun trail(n: Int): List<Int> {
        val run = followed(); val out = ArrayList<Int>(); var seq = evHead - 1
        while (seq >= 0 && seq >= evHead - cap && out.size < n) {
            val s = (seq % cap).toInt()
            if (evRun[s] == run && (evKind[s] == END || evKind[s] == BEGIN || evKind[s] == RING) && evNode[s] >= 0 &&
                (out.isEmpty() || out.last() != evNode[s])) out.add(evNode[s])
            seq--
        }
        return out.asReversed()
    }

    /** Advances the camera to [localNow] and plans the frame. */
    fun frame(localNow: Double): FramePlan {
        now = localNow
        if (!pinned && now - touched > hold && keys.isNotEmpty()) {
            val path = trail(FOLLOW)
            val focus = if (path.isEmpty()) keys.indices.toList() else path + path.mapNotNull { parents[it].takeIf { p -> p >= 0 } }
            val b = bounds(focus)
            val k = 1 - exp(-(EASE))
            cx += (b[0] - cx) * k; cy += (b[1] - cy) * k; zoom *= (b[2] / zoom).pow(k)
        }
        val cam = camera()
        val items = ArrayList<DrawItem>()
        val labels = ArrayList<DrawItem>()
        fun screen(i: Int) = cam.project(Vec3(x[i], y[i]), viewport)!!

        // Stems: parent to child, thick at the trunk, thinning toward the leaves; lit while sap runs.
        for (i in keys.indices) {
            val p = parents[i]; if (p < 0) continue
            val grow = growth(i); if (grow <= 0) continue
            val a = screen(p); val b = screen(i)
            val tip = Vec3(a.x + (b.x - a.x) * grow, a.y + (b.y - a.y) * grow)
            val mid = Vec3((a.x + tip.x) / 2, a.y)
            val heat = heat(i)
            val depth = depthOf(i)
            val width = max(1.2, (7.0 - depth * 1.3) * zoom.coerceIn(.35, 1.6))
            items.add(DrawItem.Path("stem-$i", curve(a, mid, tip), stroke = mix(STEM, SAP, heat), width = width))
        }
        // The trail: the followed run's path, older segments fading, a comet at the head.
        val path = trail(TRAIL)
        for (k in 1 until path.size) {
            val a = screen(path[k - 1]); val b = screen(path[k]); val age = (path.size - k).toDouble() / path.size
            items.add(DrawItem.Path("trail-$k", s_[PathPart.Move(a), PathPart.Line(b)],
                stroke = Rgba(255, 214, 120, (0.85 * (1 - age)).coerceIn(.08, .85)), width = 2.4 * (1 - age) + .8))
        }
        // Blooms: each node a shape of its metaphor, sized by growth, haloed by recent work.
        for (i in keys.indices) {
            val grow = growth(i); if (grow <= 0) continue
            val m = CuratorMetaphors.of(types[i]); val c = screen(i)
            val r = radius(i) * grow * zoom.coerceIn(.3, 2.0)
            val heat = heat(i)
            if (heat > .02) items.add(DrawItem.Path("halo-$i", disc(c, r * (1.6 + heat * 1.4), 18), fill = Rgba(m.color.red, m.color.green, m.color.blue, .28 * heat)))
            val failed = lastKind[i] == FAIL
            items.add(DrawItem.Path("n-$i", shape(m.shape, c, r), fill = if (failed) Rgba(220, 70, 70) else mix(dim(m.color), m.color, max(heat, .35)),
                stroke = if (i == selected || i == hovered) Rgba(255, 255, 255) else null, width = 1.4))
            if (activeSince[i] >= 0) items.add(DrawItem.Path("pulse-$i",
                disc(c, r * (1.3 + .25 * sin((now - activeSince[i]) / 90.0)), 20), stroke = Rgba(255, 240, 200, .9), width = 1.6))
            val bough = CuratorMetaphors.of(types[i]).shape == CuratorMetaphors.Shape.BOUGH
            if (i == hovered || i == selected || heat > .35 || (bough && zoom > .25) || zoom > 1.1) {
                val id = keys[i].substringAfterLast('/')
                labels.add(DrawItem.Text("l-$i", if (parents[i] < 0) keys[i] else "${m.name} · $id", Vec3(c.x + r + 4, c.y + 4),
                    Rgba(226, 230, 236, (.55 + heat * .45).coerceAtMost(1.0)), 11.0 + min(3.0, r / 8), maxWidth = 220.0, weight = if (heat > .5) 600 else 400))
                if (heat > .3) lastNote[i]?.let { note ->
                    labels.add(DrawItem.Text("v-$i", "${verb(i)} — $note", Vec3(c.x + r + 4, c.y + 18),
                        Rgba(250, 214, 150, heat.coerceIn(.3, 1.0)), 10.5, font = "monospace", maxWidth = 360.0))
                }
            }
        }
        spikes(items, labels)
        return FramePlan(viewport, (items + labels).toSeries(), Rgba(11, 14, 20))
    }

    // ── the fill/drain sparkline: events per 250 ms over 30 s, a spike is a bin over 3× the mean ──
    private fun spikes(items: MutableList<DrawItem>, labels: MutableList<DrawItem>) {
        val bins = IntArray(BINS); var seq = evHead - 1
        while (seq >= 0 && seq >= evHead - cap) {
            val s = (seq % cap).toInt(); val b = ((now - evT[s]) / BIN_MS).toInt()
            if (b >= BINS) break; if (b >= 0) bins[BINS - 1 - b]++; seq--
        }
        val mean = bins.average().coerceAtLeast(.5); val peak = max(1, bins.max())
        val x0 = 14.0; val y0 = viewport.height - 16.0; val w = 3.0
        for (i in 0 until BINS) {
            val h = 40.0 * bins[i] / peak; if (h <= 0) continue
            val x = x0 + i * w
            items.add(DrawItem.Path("spike-$i", s_[PathPart.Move(Vec3(x, y0)), PathPart.Line(Vec3(x + w - 1, y0)),
                PathPart.Line(Vec3(x + w - 1, y0 - h)), PathPart.Line(Vec3(x, y0 - h)), PathPart.Close],
                fill = if (bins[i] > 3 * mean) Rgba(236, 96, 88) else Rgba(96, 170, 150)))
        }
        val rate = bins.takeLast(4).sum()
        val held = holding()
        labels.add(DrawItem.Text("hud", "ring ${cursor}  ·  ${rate}/s  ·  drained $lastDrained  ·  lost $dropped  ·  " +
            (if (held.isInfinite()) "pinned — F follows" else if (held > 0) "yours for ${(held / 1000).roundToInt()}s" else "following ${runNames.getOrNull(followed()) ?: "—"}"),
            Vec3(x0 + BINS * w + 12, y0 - 4), Rgba(170, 182, 196), 12.0, font = "monospace"))
    }

    /** What the illustrative panel shows for the node in view: hovered, selected, or the production's head. */
    fun panel(): Map<String, Any?>? {
        val i = when { hovered >= 0 -> hovered; selected >= 0 -> selected; else -> trail(1).firstOrNull() ?: -1 }
        if (i !in keys.indices) return null
        val m = CuratorMetaphors.of(types[i])
        val story = ArrayList<String>(); var seq = evHead - 1
        while (seq >= 0 && seq >= evHead - cap && story.size < 8) {
            val s = (seq % cap).toInt()
            if (evNode[s] == i) story.add("${runNames.getOrNull(evRun[s]) ?: "run ${evRun[s]}"}: ${verbOf(m, evKind[s])}" + (evNote[s]?.let { " — $it" } ?: ""))
            seq--
        }
        return linkedMapOf("key" to keys[i], "type" to types[i], "metaphor" to m.name, "shape" to m.shape.name,
            "color" to m.color.css, "gloss" to m.gloss, "verb" to verb(i), "runs" to runs[i],
            "meanMs" to if (runs[i] > 0) spent[i] / runs[i] else null, "active" to (activeSince[i] >= 0), "story" to story)
    }

    private fun verb(i: Int) = verbOf(CuratorMetaphors.of(types[i]), lastKind[i])
    private fun verbOf(m: CuratorMetaphors.Metaphor, kind: Int) = when (kind) {
        BEGIN -> m.begin; END -> m.end; RING -> "opens a bough"; YIELD -> "bears fruit"; TURN -> "turns again"
        SKIP -> "sleeps (unfed)"; FAIL -> "withers"; else -> m.end
    }

    private fun growth(i: Int) = ((now - born[i]) / GROW_MS).coerceIn(0.0, 1.0).let { 1 - (1 - it).pow(3) }
    /** 1 at the moment of work, fading to 0 over [GLOW_MS]; held at 1 while running. */
    private fun heat(i: Int) = if (activeSince[i] >= 0) 1.0 else exp(-(now - max(lastBegin[i], lastEnd[i])) / GLOW_MS)
    private fun depthOf(i: Int): Int { var d = 0; var p = parents[i]; while (p >= 0 && d < 64) { d++; p = parents[p] }; return d }
    private fun radius(i: Int) = when (CuratorMetaphors.of(types[i]).shape) {
        CuratorMetaphors.Shape.BOUGH -> 11.0; CuratorMetaphors.Shape.STAR, CuratorMetaphors.Shape.EYE -> 13.0
        CuratorMetaphors.Shape.SEED -> 6.0; else -> 9.0
    } + min(6.0, ln(1.0 + runs[i]) * 1.5)

    private fun curve(a: Vec3, c: Vec3, b: Vec3): Series<PathPart> = 13 j { k: Int ->
        val t = k / 12.0; val u = 1 - t
        val p = Vec3(u * u * a.x + 2 * u * t * c.x + t * t * b.x, u * u * a.y + 2 * u * t * c.y + t * t * b.y)
        if (k == 0) PathPart.Move(p) else PathPart.Line(p)
    }

    private fun disc(c: Vec3, r: Double, n: Int): Series<PathPart> = (n + 1) j { k: Int ->
        if (k == n) PathPart.Close else Vec3(c.x + cos(k * 2 * PI / n) * r, c.y + sin(k * 2 * PI / n) * r).let { if (k == 0) PathPart.Move(it) else PathPart.Line(it) }
    }

    /** Star-shaped outlines around their center, fanned from it, so the triangle provider needs no tessellator. */
    private fun shape(s: CuratorMetaphors.Shape, c: Vec3, r: Double): Series<PathPart> {
        val ring: List<Vec3> = when (s) {
            CuratorMetaphors.Shape.STAR -> (0 until 10).map { k -> val a = -PI / 2 + k * PI / 5; val q = if (k % 2 == 0) r else r * .45; Vec3(c.x + cos(a) * q, c.y + sin(a) * q) }
            CuratorMetaphors.Shape.LEAF -> (0 until 16).map { k -> val a = k * 2 * PI / 16; Vec3(c.x + cos(a) * r * .55, c.y + sin(a) * r * (1 - .45 * abs(cos(a)))) }
                .map { Vec3(c.x + (it.x - c.x) * .8 - (it.y - c.y) * .6, c.y + (it.x - c.x) * .6 + (it.y - c.y) * .8) }
            CuratorMetaphors.Shape.EYE -> (0 until 16).map { k -> val a = k * 2 * PI / 16; Vec3(c.x + cos(a) * r * 1.2, c.y + sin(a) * r * .6 * abs(sin(a)).pow(.5)) }
            CuratorMetaphors.Shape.PRISM -> (0 until 3).map { k -> val a = -PI / 2 + k * 2 * PI / 3; Vec3(c.x + cos(a) * r * 1.15, c.y + sin(a) * r * 1.15) }
            CuratorMetaphors.Shape.SCROLL -> listOf(Vec3(c.x - r * 1.1, c.y - r * .7), Vec3(c.x + r * 1.1, c.y - r * .7), Vec3(c.x + r * 1.1, c.y + r * .7), Vec3(c.x - r * 1.1, c.y + r * .7))
            CuratorMetaphors.Shape.HARBOR -> listOf(Vec3(c.x - r * 1.2, c.y - r * .2), Vec3(c.x + r * 1.2, c.y - r * .2), Vec3(c.x + r * .8, c.y + r * .8), Vec3(c.x - r * .8, c.y + r * .8))
            CuratorMetaphors.Shape.WHEEL -> (0 until 8).map { k -> val a = PI / 8 + k * PI / 4; Vec3(c.x + cos(a) * r, c.y + sin(a) * r) }
            CuratorMetaphors.Shape.BOUGH -> (0 until 6).map { k -> val a = k * PI / 3; Vec3(c.x + cos(a) * r, c.y + sin(a) * r) }
            CuratorMetaphors.Shape.FRUIT, CuratorMetaphors.Shape.SEED -> (0 until 14).map { k -> val a = k * 2 * PI / 14; Vec3(c.x + cos(a) * r, c.y + sin(a) * r) }
        }
        val pts = listOf(c) + ring + ring.first()
        return (pts.size + 1) j { k: Int -> if (k == pts.size) PathPart.Close else if (k == 0) PathPart.Move(pts[0]) else PathPart.Line(pts[k]) }
    }

    private fun mix(a: Rgba, b: Rgba, t: Double): Rgba { val u = t.coerceIn(0.0, 1.0)
        return Rgba((a.red + (b.red - a.red) * u).roundToInt(), (a.green + (b.green - a.green) * u).roundToInt(),
            (a.blue + (b.blue - a.blue) * u).roundToInt(), a.alpha + (b.alpha - a.alpha) * u) }
    private fun dim(c: Rgba) = Rgba(c.red / 3 + 20, c.green / 3 + 22, c.blue / 3 + 26)
    private fun num(v: Any?) = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull() ?: 0.0

    /** Growable primitive columns: the node table stays arrays, not rows of objects. */
    private class DoubleList { var a = DoubleArray(64); var n = 0
        fun add(v: Double) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        operator fun get(i: Int) = a[i]; operator fun set(i: Int, v: Double) { a[i] = v } }
    private class IntAccumulatorList { var a = IntArray(64); var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        operator fun get(i: Int) = a[i]; operator fun set(i: Int, v: Int) { a[i] = v } }

    companion object {
        // LcncTrail.Kind ordinals.
        const val RUN = 0; const val BEGIN = 1; const val END = 2; const val SKIP = 3; const val RING = 4
        const val YIELD = 5; const val TURN = 6; const val FAIL = 7
        const val SLOT = 150.0; const val LEVEL = 64.0; const val HUD = 70.0; const val ROW = 4
        const val GROW_MS = 700.0; const val GLOW_MS = 2400.0; const val EASE = .08
        const val FOLLOW = 10; const val TRAIL = 48; const val BINS = 120; const val BIN_MS = 250.0
        val STEM = Rgba(70, 84, 72); val SAP = Rgba(150, 214, 140)
    }
}
