package borg.trikeshed.web.patch

/**
 * The concentric pass of a scope ring (patch-layout.js ringLayout). Every ring lays its
 * children on square shells around one shared center: scope.in on the left edge (seed),
 * scope.out on the right edge (emit), statements alternate top/bottom, nested rings stack
 * inward at the center. Child coordinates are ring-local.
 */
class RingChild(val w: Double, val h: Double, val entry: Boolean, val exit: Boolean, val ring: Boolean)

class RingPlacement(val index: Int, val x: Double, val y: Double)

class RingPlan(val width: Double, val height: Double, val placements: List<RingPlacement>)

class RingCamera(var x: Double, var y: Double, var z: Double)

object PatchRing {
    const val EDGE = 26.0
    const val GAP = 18.0

    /** the initial camera a freshly laid ring window gets */
    fun view(width: Double, height: Double): RingCamera = RingCamera(0.0, 0.0, minOf(1.0, 560 / width, 360 / height))

    fun plan(kids: List<RingChild>): RingPlan {
        val rings = kids.indices.filter { kids[it].ring }
        val ins = kids.indices.filter { kids[it].entry }
        val outs = kids.indices.filter { kids[it].exit }
        val mids = kids.indices.filter { it !in ins && it !in outs && it !in rings }
        fun stackH(a: List<Int>) = if (a.isEmpty()) 0.0 else a.sumOf { kids[it].h } + (a.size - 1) * GAP
        fun rowW(a: List<Int>) = if (a.isEmpty()) 0.0 else a.sumOf { kids[it].w } + (a.size - 1) * GAP
        fun rowH(a: List<Int>) = if (a.isEmpty()) 0.0 else a.maxOf { kids[it].h }
        fun colW(a: List<Int>) = if (a.isEmpty()) 0.0 else a.maxOf { kids[it].w }
        val top = mids.filterIndexed { i, _ -> i % 2 == 0 }
        val bot = mids.filterIndexed { i, _ -> i % 2 == 1 }
        val centerW = maxOf(rowW(rings), rowW(top), rowW(bot), 140.0)
        val centerH = maxOf(rowH(rings), stackH(ins), stackH(outs), 60.0)
        val width = colW(ins) + centerW + colW(outs) + 4 * EDGE
        val height = (if (top.isNotEmpty()) rowH(top) + EDGE else 0.0) + centerH +
            (if (bot.isNotEmpty()) rowH(bot) + EDGE else 0.0) + 2 * EDGE
        val cx = colW(ins) + 2 * EDGE + centerW / 2
        val cy = (if (top.isNotEmpty()) rowH(top) + EDGE else 0.0) + EDGE + centerH / 2
        val placed = ArrayList<RingPlacement>()
        var y = cy - stackH(ins) / 2
        for (c in ins) { placed += RingPlacement(c, EDGE, y); y += kids[c].h + GAP }
        y = cy - stackH(outs) / 2
        for (c in outs) { placed += RingPlacement(c, width - EDGE - kids[c].w, y); y += kids[c].h + GAP }
        var x = cx - rowW(top) / 2
        for (c in top) { placed += RingPlacement(c, x, EDGE); x += kids[c].w + GAP }
        x = cx - rowW(bot) / 2
        for (c in bot) { placed += RingPlacement(c, x, height - EDGE - kids[c].h); x += kids[c].w + GAP }
        x = cx - rowW(rings) / 2
        for (c in rings) { placed += RingPlacement(c, x, cy - kids[c].h / 2); x += kids[c].w + GAP }
        return RingPlan(width, height, placed)
    }

    /** product of the enclosing ring zooms, outermost excluded from nothing: pointer math divides by it */
    fun scaleOf(zooms: List<Double>): Double = zooms.fold(1.0) { s, z -> s * z }

    /** the elastic frame floor: pan + world × zoom */
    fun frameFloor(camera: RingCamera, worldW: Double, worldH: Double): Pair<Double, Double> =
        (camera.x + worldW * camera.z) to (camera.y + worldH * camera.z)
}
