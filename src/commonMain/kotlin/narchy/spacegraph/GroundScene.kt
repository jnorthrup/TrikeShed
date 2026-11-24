package narchy.spacegraph

import borg.trikeshed.landscape.*
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.*
import narchy.spacegraph.graphics.spi.*
import kotlin.math.*

/**
 * A thing standing on the ground in the room of its class [path] (root first; the empty path is the untyped room): a
 * column [height] tall (world units, a room's radius being 1), filled [share] of the way in [color] (null: the share is
 * unknown and only the outline stands). [satellites] stand round it, [marks] ticks ring its foot with [held] of them
 * drawn heavy, and [noun] names what an aggregate counts it as.
 */
class Stand(
    val id: String,
    val path: Series<String>,
    val label: Series<String>,
    val height: Double,
    val share: Double?,
    val color: Rgba,
    val satellites: Series<Stand> = emptySeriesOf(),
    val marks: Int = 0,
    val held: Double = 0.0,
    val noun: String = "things",
)

/** The territory of a class the hyperarchy does not hold (a domain induced under one it holds): its home disc, labelled. */
class Territory(val id: String, val path: Series<String>, val label: Series<String>, val color: Rgba)

/**
 * The ground every page shares: SUMO's classes at their homes ([SumoHyperarchy]) seen through a [GroundCamera] that
 * tilts and turns, with [stands] and [territories] at the homes of their classes, ground [links] between them, the
 * hyperarchy path in hand ([navigation]) and a screen-space figure ([figure]). A class opens into its children when its
 * home is [OPEN_PX] across on screen; what stands inside a class that is not open is counted on that class's home.
 * Platform code feeds input in and draws [frame]'s plan.
 */
class GroundScene(var viewport: Viewport = Viewport(1, 1)) {
    var hyperarchy: SumoHyperarchy? = null
    var navigation: HyperarchyNavigation? = null
    var camera = GroundCamera()
    var stands: Series<Stand> = emptySeriesOf()
    var territories: Series<Territory> = emptySeriesOf()
    /** Ground lines between two stands or territories, by id. */
    var links: Series<Join<String, String>> = emptySeriesOf()
    /** Lines of the screen-space figure, top left. */
    var figure: Series<String> = emptySeriesOf()
    var selected: String? = null
    /** A view read before the hyperarchy arrived, applied when it does. */
    var pending: GroundView? = null

    fun load(h: SumoHyperarchy) {
        hyperarchy = h; navigation = HyperarchyNavigation(h)
        val view = pending; pending = null
        if (view != null) { camera = view.camera; navigation!!.restore(view.path) } else fit()
    }

    // ── placement: where each stand, satellite and territory is on the ground ──

    /** Ground center and footprint radius of a stand or satellite, by id; territories by id to their discs. */
    val placed = HashMap<String, Disc>()
    var placedFor: Any? = null

    fun place(h: SumoHyperarchy) {
        val key = Triple(stands, territories, h)
        if (placedFor == key) return
        placedFor = key; placed.clear()
        val byRoom = LinkedHashMap<Disc, ArrayList<Stand>>()
        for (s in stands.view) byRoom.getOrPut(h.room(s.path)) { ArrayList() }.add(s)
        for ((room, inRoom) in byRoom) {
            inRoom.sortBy { it.id }
            val n = inRoom.size; val foot = room.r * min(.42, .78 / sqrt(n.toDouble()))
            for ((k, s) in inRoom.withIndex()) {
                val d = if (n == 1) 0.0 else room.r * .62 * sqrt((k + .5) / n); val a = k * GOLDEN
                val at = Disc(room.x + d * cos(a), room.y + d * sin(a), foot)
                placed[s.id] = at
                val m = s.satellites.size
                for ((j, t) in s.satellites.view.withIndex()) {
                    val b = 2 * PI * j / m + PI / 4
                    placed[t.id] = Disc(at.x + foot * .78 * cos(b), at.y + foot * .78 * sin(b), foot * min(.14, 1.6 / max(m, 1)))
                }
            }
        }
        for (t in territories.view) placed[t.id] = h.home(t.path)
    }

    /** Where the stand, satellite or territory [id] is on the ground, or null. */
    fun at(id: String): Disc? { hyperarchy?.let(::place); return placed[id] }

    /** The class whose room [path] stands in: its last class the hyperarchy holds, or -1 for the untyped room. */
    fun anchor(h: SumoHyperarchy, path: Series<String>): Int {
        var c = -1
        for (name in path.view) { val i = h.index(name); if (i >= 0) c = i }
        return c
    }

    // ── camera and input ──

    /** Frames the stands and territories, or the whole ground when there are none. */
    fun fit() {
        val h = hyperarchy ?: return
        place(h)
        val discs = placed.values
        if (discs.isEmpty()) { camera = camera.frame(h.ground.x, h.ground.y, h.ground.r, viewport); return }
        val x0 = discs.minOf { it.x - it.r }; val x1 = discs.maxOf { it.x + it.r }
        val y0 = discs.minOf { it.y - it.r }; val y1 = discs.maxOf { it.y + it.r }
        camera = camera.frame((x0 + x1) / 2, (y0 + y1) / 2, max(hypot(x1 - x0, y1 - y0) / 2, 2.0), viewport)
    }

    fun wheel(deltaY: Double, deltaMode: Int, sx: Double, sy: Double) {
        camera = camera.zoomAt(LandscapeNavigation.wheelFactor(LandscapeMomentum.wheelPixels(deltaY, deltaMode, viewport.height.toDouble())), sx, sy, viewport)
    }

    /** A pointer drag from ([fromX], [fromY]) to ([toX], [toY]): the ground point in hand stays under the pointer. */
    fun drag(fromX: Double, fromY: Double, toX: Double, toY: Double) { camera = camera.drag(fromX, fromY, toX, toY, viewport) }

    /** A turning drag: across turns, down tilts toward the horizon. */
    fun orbit(dx: Double, dy: Double) { camera = camera.turn(-dx * TURN_PER_PX).tilt(dy * TILT_PER_PX) }

    /** The camera to the home of the class in hand, the whole ground when none is. */
    fun home() {
        val h = hyperarchy ?: return
        val c = navigation?.current ?: -1
        val d = if (c >= 0) h.home(c) else h.ground
        camera = camera.frame(d.x, d.y, d.r, viewport)
    }

    /** The camera to the whole ground; the path is kept. */
    fun root() { val g = hyperarchy?.ground ?: return; camera = camera.frame(g.x, g.y, g.r, viewport) }

    /** Moves through the hyperarchy or the camera by key name; true when the key was taken. The camera goes to the home of a class reached. */
    fun key(name: String): Boolean {
        val n = navigation
        val moved = when (name) {
            "ArrowUp" -> n != null && n.parents().let { ps -> ps.isNotEmpty() && n.up(n.path.getOrNull(n.path.size - 2)?.takeIf { it in ps } ?: ps[0]) }
            "ArrowDown" -> n != null && n.children().let { cs -> cs.isNotEmpty() && n.down(beneath(cs) ?: cs[0]) }
            "ArrowRight" -> n != null && n.sibling(1)
            "ArrowLeft" -> n != null && n.sibling(-1)
            "Backspace" -> n != null && n.back()
            "1", "2", "3", "4", "5", "6", "7", "8", "9" -> n != null && n.parents().let { ps -> name.toInt() <= ps.size && n.up(ps[name.toInt() - 1]) }
            "h", "H" -> { home(); return true }
            "Home", "0" -> { root(); return true }
            "q", "Q" -> { camera = camera.turn(TURN_STEP); return true }
            "e", "E" -> { camera = camera.turn(-TURN_STEP); return true }
            "PageUp" -> { camera = camera.tilt(TILT_STEP); return true }
            "PageDown" -> { camera = camera.tilt(-TILT_STEP); return true }
            "+", "=" -> { camera = camera.zoomAt(1.25, viewport.width / 2.0, viewport.height / 2.0, viewport); return true }
            "-" -> { camera = camera.zoomAt(.8, viewport.width / 2.0, viewport.height / 2.0, viewport); return true }
            else -> return false
        }
        if (moved) home()
        return moved
    }

    /** The child of [cs] whose home holds the ground point in the middle of the view. */
    fun beneath(cs: IntArray): Int? { val h = hyperarchy ?: return null; return cs.firstOrNull { h.home(it).contains(camera.x, camera.y) } }

    /**
     * A click at ([sx], [sy]): an offered parent in the figure goes up to it; a stand or satellite is selected and its
     * id returned; else the class open under the pointer is reached (the camera going to its home).
     */
    fun click(sx: Double, sy: Double): String? {
        for ((box, parent) in offered) if (box.contains(Vec3(sx, sy))) { if (navigation?.up(parent) == true) home(); return null }
        var best: String? = null; var bestR = Double.MAX_VALUE
        for ((id, c) in drawn) { val r = max(c.r, 6.0); if (hypot(c.x - sx, c.y - sy) <= r && c.r < bestR) { best = id; bestR = c.r } }
        if (best != null) { selected = best; return best }
        val h = hyperarchy ?: return null
        val g = camera.ground(sx, sy, viewport) ?: return null
        var c = -1
        for (k in shown) if (h.home(k).contains(g.x, g.y) && (c < 0 || h.home(k).r < h.home(c).r)) c = k
        if (c >= 0) { navigation?.goto(c); home() }
        selected = null
        return null
    }

    /** The view as a URL hash: the camera with its angles, and the path in hand. */
    fun hash(): String = GroundView(camera, navigation?.names ?: emptyList()).encode()

    /** Takes the view in [hash]; false when it reads none. Before the hyperarchy arrives the view waits for it. */
    fun restore(hash: String): Boolean {
        val view = GroundView.decode(hash) ?: return false
        if (navigation == null) { pending = view; camera = view.camera; return true }
        camera = view.camera; navigation!!.restore(view.path)
        return true
    }

    // ── what the last frame drew, for picking ──

    /** Stands and satellites drawn: screen center and radius by id. */
    val drawn = ArrayList<Join<String, Disc>>()
    /** Classes whose homes were drawn. */
    val shown = ArrayList<Int>()
    /** Parents offered in the figure: screen box and class. */
    val offered = ArrayList<Join<Rect, Int>>()

    // ── the frame ──

    fun frame(): FramePlan {
        val vp = viewport.bounds
        val ground = ArrayList<DrawItem>()
        val solids = ArrayList<Join<Double, DrawItem>>()
        val labels = ArrayList<DrawItem>()
        drawn.clear(); shown.clear(); offered.clear()
        val cols = (vp.width / CELL).toInt() + 1; val rows = (vp.height / CELL).toInt() + 1
        val taken = BooleanArray(cols * rows)
        fun claim(b: Rect): Boolean {
            val c0 = (b.x / CELL).toInt(); val c1 = (b.right / CELL).toInt(); val r0 = (b.y / CELL).toInt(); val r1 = (b.bottom / CELL).toInt()
            if (c1 < 0 || r1 < 0 || c0 >= cols || r0 >= rows) return false
            for (y in max(0, r0)..min(rows - 1, r1)) for (x in max(0, c0)..min(cols - 1, c1)) if (taken[y * cols + x]) return false
            for (y in max(0, r0)..min(rows - 1, r1)) for (x in max(0, c0)..min(cols - 1, c1)) taken[y * cols + x] = true
            return true
        }
        fun text(id: String, s: String, x: Double, y: Double, color: Rgba, size: Double, weight: Int = 400, force: Boolean = false): Boolean {
            val w = s.length * size * .56
            if (!force && !claim(Rect(x, y - size, w, size * 1.3))) return false
            labels.add(DrawItem.Text(id, s, Vec3(x, y), color, size, weight = weight, maxWidth = max(w, 1.0)))
            return true
        }
        /** [lines] from baseline [y] down, claimed as one block kept inside the view: the first line in [color], bold, the rest dimmer. */
        fun block(id: String, lines: List<String>, x0: Double, y0: Double, color: Rgba, force: Boolean) {
            if (lines.isEmpty()) return
            val w = lines.maxOf { it.length } * 11.0 * .56
            val x = x0.coerceIn(4.0, max(4.0, vp.width - w - 4)); val y = y0.coerceIn(16.0, max(16.0, vp.height - LINE * lines.size))
            if (!force && !claim(Rect(x, y - 12, w, LINE * lines.size + 2))) return
            for ((i, s) in lines.withIndex()) labels.add(DrawItem.Text(id, s, Vec3(x, y + i * LINE), if (i == 0) color else Rgba(176, 188, 204),
                if (i == 0) 12.0 else 10.5, weight = if (i == 0) 600 else 400, maxWidth = max(w, 1.0)))
        }
        /** Class names wait until what stands has claimed its labels. */
        val names = ArrayList<() -> Unit>()
        val h = hyperarchy
        if (h == null) {
            figureItems(labels, h)
            return FramePlan(viewport, (labels.size) j { i: Int -> labels[i] }, BACKGROUND)
        }
        place(h)
        val eye = camera.eye(viewport); val f = camera.focal(viewport)
        val inHand = navigation?.current ?: -1
        val inPath = navigation?.path?.toHashSet() ?: emptySet()

        /** The disc [d] projected: its polygon, or null when it does not stand wholly before the eye. */
        fun outline(d: Disc, z: Double = 0.0): List<Vec3>? {
            val c = camera.project(Vec3(d.x, d.y, z), viewport)
            val est = if (c == null) 64.0 else d.r * f / c.z
            val n = (est / 4).toInt().coerceIn(if (est >= 30) 24 else 12, 72)
            val out = ArrayList<Vec3>(n)
            for (k in 0 until n) { val a = 2 * PI * k / n; out.add(camera.project(Vec3(d.x + d.r * cos(a), d.y + d.r * sin(a), z), viewport) ?: return null) }
            return out
        }
        fun polygon(id: String, p: List<Vec3>, fill: Rgba?, stroke: Rgba?, width: Double = 1.0): DrawItem.Path =
            DrawItem.Path(id, (p.size + 1) j { i: Int -> if (i == 0) PathPart.Move(p[0]) else if (i < p.size) PathPart.Line(p[i]) else PathPart.Close }, fill, stroke, width)
        fun box(p: List<Vec3>) = Rect(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x } - p.minOf { it.x }, p.maxOf { it.y } - p.minOf { it.y })

        // Classes: a class's home is drawn when it shows, and opens into its canonical children when it is OPEN_PX across.
        val open = BooleanArray(h.size)
        fun visit(c: Int) {
            val d = h.home(c); val p = outline(d)
            val across: Double
            if (p != null) {
                val b = box(p)
                if (b.intersection(vp) == null) return
                across = max(b.width, b.height)
                if (across < MIN_PX) return
                val hand = c == inHand; val onPath = c in inPath
                ground.add(polygon("class-$c", p, Rgba(40 + 6 * min(h.depth[c], 8), 50 + 6 * min(h.depth[c], 8), 66 + 7 * min(h.depth[c], 8), .55),
                    if (hand) GOLD else if (onPath) Rgba(200, 170, 110, .8) else Rgba(86, 102, 126, .9), if (hand) 2.2 else if (onPath) 1.4 else .7))
                shown.add(c)
                if (across >= LABEL_PX && b.height >= 10) {
                    val top = p.minByOrNull { it.y }!!
                    val size = (across / 16).coerceIn(10.0, 18.0)
                    val y = if (across >= OPEN_PX) top.y + size * 1.4 else b.center.y + size * .35
                    val x = (if (across >= OPEN_PX) top.x else b.center.x) - h.names[c].length * size * .28
                    names.add { text("class-$c", h.names[c], x, y, if (hand) GOLD else Rgba(205, 214, 228), size, if (hand || onPath) 600 else 400, force = hand) }
                }
            } else across = Double.MAX_VALUE
            if (across >= OPEN_PX) {
                open[c] = true
                // the room, where things typed by the class stand
                outline(h.room(c))?.let { q -> if (box(q).width >= MIN_PX) ground.add(polygon("room-$c", q, Rgba(24, 30, 42, .7), Rgba(120, 128, 146, .5), .6)) }
                for (k in h.treeChildren(c)) visit(k)
            }
        }
        // The untyped room stands beside the roots; under OPEN_PX across, what stands in it is counted on it.
        var untypedOpen = false
        outline(h.untyped)?.let { p ->
            val b = box(p); val across = max(b.width, b.height)
            untypedOpen = across >= OPEN_PX
            if (b.intersection(vp) != null && across >= MIN_PX) {
                ground.add(polygon("untyped", p, Rgba(70, 44, 52, .6), Rgba(200, 120, 130, .9), 1.2))
                if (b.width >= LABEL_PX) names.add { text("untyped", "untyped", b.center.x - 24, if (untypedOpen) b.y + 16 else b.center.y, Rgba(240, 190, 196), 12.0, 600) }
            }
        } ?: run { untypedOpen = true }
        for (r in h.roots) visit(r)

        /** Where what stands in [c] is counted: the first class on [c]'s canonical path that is not open ([UNTYPED] for the
         *  untyped room when it is not), or -1 when it stands open. */
        fun closedAt(c: Int): Int {
            if (c < 0) return if (untypedOpen) -1 else UNTYPED
            val p = h.path(c)
            for (i in 0 until p.size) if (!open[p[i]]) return p[i]
            return -1
        }
        fun countedOn(c: Int): Disc = if (c == UNTYPED) h.untyped else h.home(c)
        val counted = LinkedHashMap<Int, LinkedHashMap<String, Int>>()
        fun count(c: Int, noun: String) { val m = counted.getOrPut(c) { LinkedHashMap() }; m[noun] = (m[noun] ?: 0) + 1 }

        // Territories of induced classes: their home discs, labelled.
        for (t in territories.view) {
            val shut = closedAt(anchor(h, t.path))
            if (shut != -1) { count(shut, "domains"); continue }
            val d = placed[t.id] ?: continue
            val p = outline(d) ?: continue
            val b = box(p); if (b.intersection(vp) == null) continue
            ground.add(polygon(t.id, p, t.color.copy(alpha = .22), t.color, if (t.id == selected) 2.4 else 1.3))
            if (max(b.width, b.height) >= 18) {
                val lines = if (max(b.width, b.height) >= LABEL_PX) t.label.view.toList() else listOf(t.label[0])
                block(t.id, lines, b.x, b.y - 4 - (lines.size - 1) * LINE, t.color, force = true)
            }
        }

        /** A prism of [sides] faces about ([x], [y]) of radius [r] from [z0] to [z1]: the faces turned to the eye, with their depths. */
        fun prism(id: String, x: Double, y: Double, r: Double, z0: Double, z1: Double, fill: Rgba, stroke: Rgba?) {
            if (z1 - z0 < 1e-9) return
            val sides = 8
            val base = (0 until sides).map { k -> val a = 2 * PI * k / sides + PI / 8; Vec3(x + r * cos(a), y + r * sin(a)) }
            for (k in 0 until sides) {
                val a = base[k]; val b = base[(k + 1) % sides]
                val mid = Vec3((a.x + b.x) / 2, (a.y + b.y) / 2, (z0 + z1) / 2)
                val normal = Vec3(mid.x - x, mid.y - y)
                if (normal.dot(eye - mid) <= 0) continue
                val q = listOf(Vec3(a.x, a.y, z0), Vec3(b.x, b.y, z0), Vec3(b.x, b.y, z1), Vec3(a.x, a.y, z1)).map { camera.project(it, viewport) ?: return }
                val shade = .62 + .38 * max(0.0, normal.normalized().dot(LIGHT))
                solids.add(q.sumOf { it.z } / 4 j polygon(id, q, fill.copy(red = (fill.red * shade).roundToInt(), green = (fill.green * shade).roundToInt(), blue = (fill.blue * shade).roundToInt()), stroke, .6))
            }
            if (eye.z > z1) {
                val top = base.map { camera.project(Vec3(it.x, it.y, z1), viewport) ?: return }
                solids.add(top.sumOf { it.z } / sides - 1e-6 j polygon(id, top, fill, stroke, .6))
            }
        }
        /** A ring on the ground about [d]: [marks] ticks (at most [MARKS_MAX]), the share [held] of them heavy, clockwise from the top: a gauge of how many, not of which. */
        fun ring(id: String, d: Disc, marks: Int, held: Double, color: Rgba) {
            val r0 = d.r * 1.08; val r1 = d.r * 1.22
            val m = min(marks, MARKS_MAX); val heavy = (m * held.coerceIn(0.0, 1.0)).roundToInt()
            for (k in 0 until m) {
                val a = PI / 2 - 2 * PI * k / m
                val p = camera.project(Vec3(d.x + r0 * cos(a), d.y + r0 * sin(a)), viewport) ?: return
                val q = camera.project(Vec3(d.x + r1 * cos(a), d.y + r1 * sin(a)), viewport) ?: return
                val on = k < heavy
                ground.add(DrawItem.Path(id, s_[PathPart.Move(p), PathPart.Line(q)], stroke = if (on) color else Rgba(90, 100, 116, .8), width = if (on) 1.6 else .7))
            }
        }
        /** Draws [s] at [d], and its satellites round it. */
        fun stand(s: Stand, d: Disc, satellite: Boolean) {
            val c = camera.project(Vec3(d.x, d.y), viewport) ?: return
            val px = d.r * f / c.z
            if (c.x + px * 3 < 0 || c.x - px * 3 > vp.width || c.y + px * 6 < 0 || c.y - px * 3 > vp.height) return
            val hot = s.id == selected
            val r = d.r * .42; val top = s.height * d.r
            val filled = (s.share ?: 0.0).coerceIn(0.0, 1.0) * top
            prism(s.id, d.x, d.y, r, 0.0, filled, s.color, if (hot) GOLD else null)
            prism(s.id, d.x, d.y, r, filled, top, Rgba(s.color.red, s.color.green, s.color.blue, if (s.share == null) .10 else .18), if (hot) GOLD else s.color.copy(alpha = .55))
            drawn.add(s.id j Disc(c.x, c.y, px))
            if (s.marks > 0 && px >= 18) ring(s.id, d, s.marks, s.held, s.color)
            val head = camera.project(Vec3(d.x, d.y, top), viewport) ?: c
            if (px >= (if (satellite) 22.0 else 9.0) || hot) {
                val lines = if (px >= 46 || hot) s.label.view.toList() else listOf(s.label[0])
                block(s.id, lines, head.x + 6, head.y - 6 - (lines.size - 1) * LINE, Rgba(236, 240, 246), force = hot)
            }
            if (!satellite) for (t in s.satellites.view) {
                val e = placed[t.id] ?: continue
                val a = camera.project(Vec3(e.x, e.y), viewport); if (a != null && px >= 12) ground.add(DrawItem.Path(t.id, s_[PathPart.Move(a), PathPart.Line(c)], stroke = t.color.copy(alpha = .7), width = 1.0))
                if (px >= 12) stand(t, e, true)
            }
        }
        for (s in stands.view) {
            val shut = closedAt(anchor(h, s.path))
            if (shut != -1) { count(shut, s.noun); continue }
            stand(s, placed[s.id] ?: continue, false)
        }

        // Ground lines between what stands, from wherever each end is drawn or counted.
        fun end(id: String): Vec3? {
            val d = placed[id] ?: return null
            val owner = stands.view.firstOrNull { it.id == id }?.path ?: territories.view.firstOrNull { it.id == id }?.path ?: return null
            val shut = closedAt(anchor(h, owner))
            val g = if (shut != -1) countedOn(shut).let { Vec3(it.x, it.y) } else Vec3(d.x, d.y)
            return camera.project(g, viewport)
        }
        for (l in links.view) {
            val a = end(l.a) ?: continue; val b = end(l.b) ?: continue
            if (hypot(a.x - b.x, a.y - b.y) < 2) continue
            ground.add(DrawItem.Path(l.a + ">" + l.b, s_[PathPart.Move(a), PathPart.Line(b)], stroke = Rgba(120, 200, 190, .35), width = 1.0))
        }

        // What stands in a class that is not open: counted on its home.
        for ((c, nouns) in counted) {
            val d = countedOn(c); val p = camera.project(Vec3(d.x, d.y), viewport) ?: continue
            if (!vp.contains(p)) continue
            val pin = s_[PathPart.Move(Vec3(p.x, p.y - 9)), PathPart.Line(Vec3(p.x + 6, p.y)), PathPart.Line(Vec3(p.x, p.y + 9)), PathPart.Line(Vec3(p.x - 6, p.y)), PathPart.Close]
            labels.add(DrawItem.Path("count-$c", pin, Rgba(240, 200, 112), Rgba(20, 24, 30), 1.0))
            text("count-$c", nouns.entries.joinToString(" · ") { "${it.value} ${if (it.value == 1) it.key.removeSuffix("s") else it.key}" } + " · " + (if (c == UNTYPED) "untyped" else h.names[c]), p.x + 9, p.y + 4, Rgba(240, 214, 150), 11.5, 600, force = true)
        }
        for (name in names) name()

        solids.sortByDescending { it.a }
        figureItems(labels, h)
        val items = ArrayList<DrawItem>(ground.size + solids.size + labels.size)
        items.addAll(ground); for (s in solids) items.add(s.b); items.addAll(labels)
        return FramePlan(viewport, items.size j { i: Int -> items[i] }, BACKGROUND)
    }

    /** The figure (top left) and the path in hand with its offered parents (bottom left), in screen space. */
    fun figureItems(labels: ArrayList<DrawItem>, h: SumoHyperarchy?) {
        fun panel(id: String, lines: List<String>, x: Double, y: Double, size: Double) {
            if (lines.isEmpty()) return
            val w = lines.maxOf { it.length } * size * .58 + 16; val tall = lines.size * size * 1.45 + 10
            labels.add(DrawItem.Path(id, SceneProjection.rectangle(Rect(x, y, w, tall)), Rgba(14, 18, 26, .86), Rgba(52, 62, 80), 1.0))
            for ((i, s) in lines.withIndex()) labels.add(DrawItem.Text(id, s, Vec3(x + 8, y + 5 + (i + 1) * size * 1.45 - size * .35),
                if (i == 0) GOLD else Rgba(220, 226, 236), size, weight = if (i == 0) 600 else 400, maxWidth = w - 16))
        }
        panel("figure", figure.view.toList(), 12.0, 12.0, 13.0)
        if (h == null) { panel("hyperarchy", listOf("SUMO hyperarchy: loading…"), 12.0, viewport.height - 40.0, 12.0); return }
        val n = navigation ?: return
        val lines = ArrayList<String>()
        lines.add(if (n.path.isEmpty()) "SUMO hyperarchy · ${h.size} classes · ↓ to a root" else n.names.joinToString(" › "))
        val ps = n.parents()
        val parentLine = if (ps.isEmpty()) "" else "↑ " + ps.withIndex().joinToString("   ") { (i, p) -> "${i + 1} ${h.names[p]}" }
        if (parentLine.isNotEmpty()) lines.add(parentLine)
        val cs = n.children(); val ss = n.siblings(); val at = ss.indexOf(n.current)
        lines.add("↓ ${cs.size} children" + (if (at >= 0) "   ←→ ${at + 1} of ${ss.size}" else "") + "   ⌫ back   H home   0 root   Q/E turn   PgUp/PgDn tilt")
        val size = 12.0; val tall = lines.size * size * 1.45 + 10; val y = viewport.height - tall - 12
        panel("hyperarchy", lines, 12.0, y, size)
        if (parentLine.isNotEmpty()) {
            // each offered parent's words in the second line: a box the pointer can take
            var x = 12.0 + 8 + 2 * size * .58; val baseline = y + 5 + 2 * size * 1.45 - size * .35
            for ((i, p) in ps.withIndex()) {
                val word = "${i + 1} ${h.names[p]}"; val w = word.length * size * .58
                offered.add(Rect(x, baseline - size, w, size * 1.3) j p)
                x += w + 3 * size * .58
            }
        }
    }

    companion object {
        val BACKGROUND = Rgba(11, 14, 20)
        val GOLD = Rgba(240, 200, 112)
        val LIGHT = Vec3(-.4, .7, .6).normalized()
        val GOLDEN = PI * (3 - sqrt(5.0))
        /** A class's home opens into its children at this many pixels across; under [MIN_PX] it is not drawn; labels from [LABEL_PX]. */
        const val OPEN_PX = 90.0
        const val MIN_PX = 3.0
        const val LABEL_PX = 36.0
        const val CELL = 14.0
        /** Pixels between the lines of a label. */
        const val LINE = 13.0
        const val MARKS_MAX = 240
        /** Where what stands in the untyped room is counted while that room is not open. */
        const val UNTYPED = -2
        const val TURN_STEP = 15.0
        const val TILT_STEP = 10.0
        const val TURN_PER_PX = .35
        const val TILT_PER_PX = .25
    }
}
