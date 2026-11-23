package narchy.spacegraph

import kotlin.math.*

/**
 * Where the top circles of a [CuratorScope] stand. [SHELF] is the library as it is built, a bay per project; the
 * others take the same circles off their shelves and place them by the relations between them ([DistanceMapping]).
 */
enum class Layout { SHELF, TREE, RADIAL, FORCE }

/**
 * Relations mapped to distance: the tree, radial and force layouts of the breadcrumbs explorer (which follow
 * SpaceGraph's Graph2D and Force2D), for circles of their own radii [r]. Edge `e` joins circles [a]`[e]` and
 * [b]`[e]` with weight [w]`[e]`; [gap] is the clear space between two circles. A circle stands at ([x], [y]), y up.
 *
 * [tree] and [radial] hang each group of joined circles from its hub: the circle with the fewest hops to its
 * farthest one, of two such the better joined. Every other circle hangs from its heaviest edge one hop nearer the
 * hub ([up], [link]), so hops are rows under the hub or rings round it. The trees stand in rows about the origin, the
 * biggest first. [step] moves every circle one step of a force model instead: an edge pulls as hard as it is heavy.
 */
class DistanceMapping(val r: DoubleArray, val a: IntArray, val b: IntArray, val w: DoubleArray, val gap: Double) {
    val x = DoubleArray(r.size); val y = DoubleArray(r.size)
    /** Per circle: the circle it hangs from, -1 for a hub. */
    val up = IntArray(r.size) { -1 }
    /** Per edge: whether a circle hangs from it. */
    val link = BooleanArray(a.size)
    /** Per hub: the radii of the rings round it ([radial]). */
    val rings = HashMap<Int, DoubleArray>()
    private var kidStart = IntArray(r.size + 1); private var kidList = IntArray(0)
    private val vx = DoubleArray(r.size); private val vy = DoubleArray(r.size)

    /** Hangs every circle ([up], [link]) and returns the hubs, the hub of the biggest tree first. */
    private fun forest(): IntArray {
        val n = r.size
        val start = IntArray(n + 1)
        for (e in a.indices) { start[a[e] + 1]++; start[b[e] + 1]++ }
        for (i in 0 until n) start[i + 1] += start[i]
        val to = IntArray(start[n]); val by = IntArray(start[n]); val fill = start.copyOf()
        for (e in a.indices) { to[fill[a[e]]] = b[e]; by[fill[a[e]]++] = e; to[fill[b[e]]] = a[e]; by[fill[b[e]]++] = e }
        val hop = IntArray(n); val near = IntArray(n)
        /** Hops from [k] into [hop] (-1: not joined to it); returns how many circles it reaches, listed in [near] nearest first. */
        fun hops(k: Int): Int {
            hop.fill(-1); hop[k] = 0; near[0] = k
            var head = 0; var tail = 1
            while (head < tail) { val p = near[head++]; for (s in start[p] until start[p + 1]) { val m = to[s]; if (hop[m] < 0) { hop[m] = hop[p] + 1; near[tail++] = m } } }
            return tail
        }
        val seen = BooleanArray(n); val many = IntArray(n); val hubs = ArrayList<Int>()
        for (i in 0 until n) {
            if (seen[i]) continue
            val group = near.copyOf(hops(i))
            var hub = i; var least = Double.MAX_VALUE
            for (k in group) { val far = hop[near[hops(k) - 1]] - (start[k + 1] - start[k]) / 1e3; if (far < least) { least = far; hub = k } }
            hops(hub); group.sort()
            for (m in group) {
                seen[m] = true
                if (hop[m] == 0) continue
                var at = -1
                for (s in start[m] until start[m + 1]) if (hop[to[s]] == hop[m] - 1 && (at < 0 || w[by[s]] > w[by[at]])) at = s
                link[by[at]] = true; up[m] = to[at]
            }
            many[hub] = group.size; hubs.add(hub)
        }
        kidStart = IntArray(n + 1)
        for (i in 0 until n) if (up[i] >= 0) kidStart[up[i] + 1]++
        for (i in 0 until n) kidStart[i + 1] += kidStart[i]
        kidList = IntArray(kidStart[n]); val at = kidStart.copyOf()
        for (i in 0 until n) if (up[i] >= 0) kidList[at[up[i]]++] = i
        return hubs.sortedByDescending { many[it] }.toIntArray()
    }

    /** A tree under each hub: a leaf takes columns as wide as itself, a circle stands over the middle of what hangs from it, and each hop is a row as tall as its tallest circle. */
    fun tree(aspect: Double) {
        val hubs = forest()
        val wide = DoubleArray(hubs.size); val tall = DoubleArray(hubs.size)
        for ((t, hub) in hubs.withIndex()) {
            val row = ArrayList<Double>()
            fun measure(i: Int, d: Int) {
                if (d == row.size) row.add(0.0)
                row[d] = max(row[d], 2 * r[i])
                for (k in kidStart[i] until kidStart[i + 1]) measure(kidList[k], d + 1)
            }
            measure(hub, 0)
            val top = DoubleArray(row.size)
            for (d in 1 until row.size) top[d] = top[d - 1] + row[d - 1] + 3 * gap
            var col = 0.0
            fun put(i: Int, d: Int) {
                val from = col
                for (k in kidStart[i] until kidStart[i + 1]) put(kidList[k], d + 1)
                val need = 2 * r[i] + gap
                // What hangs from a circle narrower than the circle is centred under it.
                if (col - from < need) { for (k in kidStart[i] until kidStart[i + 1]) move(kidList[k], (need - (col - from)) / 2, 0.0); col = from + need }
                x[i] = (from + col - gap) / 2; y[i] = top[d] + row[d] / 2
            }
            put(hub, 0)
            wide[t] = col - gap; tall[t] = top[row.size - 1] + row[row.size - 1]
        }
        rows(hubs, wide, tall, aspect)
    }

    /** Rings round each hub, a ring for each hop. A circle's angle is the middle of its part of the turn; a leaf's part is as wide as the leaf, and a ring is wide enough that no two circles on it touch. */
    fun radial(aspect: Double) {
        val hubs = forest()
        val side = DoubleArray(hubs.size)
        val part = DoubleArray(r.size); val angle = DoubleArray(r.size)
        for ((t, hub) in hubs.withIndex()) {
            fun measure(i: Int): Double {
                var sum = 0.0
                for (k in kidStart[i] until kidStart[i + 1]) sum += measure(kidList[k])
                part[i] = max(sum, 2 * r[i] + gap); return part[i]
            }
            measure(hub)
            val ring = ArrayList<ArrayList<Int>>()
            fun turn(i: Int, from: Double, share: Double, d: Int) {
                angle[i] = from + share / 2
                if (d == ring.size) ring.add(ArrayList())
                ring[d].add(i)
                var sum = 0.0; var at = from
                for (k in kidStart[i] until kidStart[i + 1]) sum += part[kidList[k]]
                for (k in kidStart[i] until kidStart[i + 1]) { val c = kidList[k]; val s = share * part[c] / sum; turn(c, at, s, d + 1); at += s }
            }
            turn(hub, -PI / 2, 2 * PI, 0)
            val radii = DoubleArray(ring.size - 1)
            var radius = 0.0; var inner = r[hub]
            x[hub] = 0.0; y[hub] = 0.0
            for (d in 1 until ring.size) {
                val on = ring[d]
                val big = on.maxOf { r[it] }
                radius += inner + big + gap
                for (j in on.indices) {
                    if (on.size == 1) break
                    val i = on[j]; val next = on[(j + 1) % on.size]
                    val between = if (j + 1 < on.size) angle[next] - angle[i] else 2 * PI - angle[i] + angle[next]
                    radius = max(radius, (r[i] + r[next] + gap) / (2 * sin(min(PI, between) / 2)))
                }
                for (i in on) { x[i] = radius * cos(angle[i]); y[i] = radius * sin(angle[i]) }
                radii[d - 1] = radius; inner = big
            }
            rings[hub] = radii
            side[t] = 2 * (radius + inner)
            move(hub, side[t] / 2, side[t] / 2)
        }
        rows(hubs, side, side, aspect)
    }

    /** Moves [i] and everything that hangs from it. */
    private fun move(i: Int, dx: Double, dy: Double) {
        x[i] += dx; y[i] += dy
        for (k in kidStart[i] until kidStart[i + 1]) move(kidList[k], dx, dy)
    }

    /**
     * Stands the trees in rows about as wide as the view is for its height ([aspect]), then centres the whole on the
     * origin and turns y up. Each tree comes measured from the top left of its box of [wide] by [tall], y down.
     */
    private fun rows(hubs: IntArray, wide: DoubleArray, tall: DoubleArray, aspect: Double) {
        var area = 0.0
        for (t in hubs.indices) area += (wide[t] + gap) * (tall[t] + gap)
        val across = max(sqrt(area * aspect), wide.maxOrNull() ?: 0.0)
        var left = 0.0; var top = 0.0; var row = 0.0
        for ((t, hub) in hubs.withIndex()) {
            if (left > 0 && left + wide[t] > across) { left = 0.0; top += row + 2 * gap; row = 0.0 }
            move(hub, left, top)
            left += wide[t] + gap; row = max(row, tall[t])
        }
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in r.indices) { x0 = min(x0, x[i] - r[i]); x1 = max(x1, x[i] + r[i]); y0 = min(y0, y[i] - r[i]); y1 = max(y1, y[i] + r[i]) }
        for (i in r.indices) { x[i] -= (x0 + x1) / 2; y[i] = (y0 + y1) / 2 - y[i] }
    }

    /**
     * One step of the force model at temperature [temp], from where the circles stand: every two repel, an edge pulls
     * its ends together like a spring (the heaviest edge hardest), a weak pull to the origin keeps the map compact,
     * and no two circles overlap.
     */
    fun step(temp: Double) {
        val n = r.size; val unit = gap / PAD
        val fx = DoubleArray(n) { -x[it] * GRAVITY }; val fy = DoubleArray(n) { -y[it] * GRAVITY }
        for (i in 0 until n) for (j in i + 1 until n) {
            var dx = x[j] - x[i]; var dy = y[j] - y[i]; var d2 = dx * dx + dy * dy
            // Two circles on one spot part along a direction of their own.
            if (d2 < 1e-6) { dx = cos(i * 7.0 + j); dy = sin(i * 7.0 + j); d2 = 1.0 }
            val f = REPEL * unit * r[i] * r[j] / d2 / sqrt(d2)
            fx[i] -= dx * f; fy[i] -= dy * f; fx[j] += dx * f; fy[j] += dy * f
        }
        val heaviest = w.maxOrNull() ?: 1.0
        for (e in a.indices) {
            val i = a[e]; val j = b[e]
            val dx = x[j] - x[i]; val dy = y[j] - y[i]; val d = max(hypot(dx, dy), 1e-3)
            val f = SPRING * w[e] / heaviest * (d - r[i] - r[j] - LINK * unit) / d
            fx[i] += dx * f; fy[i] += dy * f; fx[j] -= dx * f; fy[j] -= dy * f
        }
        val limit = LIMIT * unit
        for (i in 0 until n) {
            vx[i] = (vx[i] + fx[i] * temp) * DRAG; vy[i] = (vy[i] + fy[i] * temp) * DRAG
            x[i] += vx[i].coerceIn(-limit, limit); y[i] += vy[i].coerceIn(-limit, limit)
        }
        for (i in 0 until n) for (j in i + 1 until n) {
            val least = r[i] + r[j] + GAP * unit
            val dx = x[j] - x[i]; val dy = y[j] - y[i]; val d = hypot(dx, dy)
            if (d >= least || d < 1e-6) continue
            val push = (least - d) / 2 / d
            x[i] -= dx * push; y[i] -= dy * push; x[j] += dx * push; y[j] += dy * push
        }
    }

    companion object {
        /** The explorer's force model, in its units: a circle's clear space there is [PAD]. */
        const val REPEL = 6.0; const val GRAVITY = .003; const val SPRING = .02; const val GAP = 26.0; const val LINK = 40.0
        const val PAD = 22.0; const val LIMIT = 30.0; const val DRAG = .7
    }
}
