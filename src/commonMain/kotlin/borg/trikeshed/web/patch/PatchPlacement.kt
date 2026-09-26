package borg.trikeshed.web.patch

/** Budgets of the bounded patch layout solver (patch-layout.js limits). */
object PatchLimits {
    const val nodes = 1500
    const val edges = 4096
    const val steps = 8000000
    const val milliseconds = 2000.0
    const val ticks = 160
    const val gap = 32.0
    const val cable = 72.0
}

/** A center-anchored rectangle. */
class CenterBox(val x: Double, val y: Double, val w: Double, val h: Double)

object PatchPlacement {
    /** nearest vacant center for [n]: expand around each hit, cheapest candidate first */
    fun vacancy(n: CenterBox, gap: Double, findHit: (Double, Double) -> CenterBox?): Pair<Double, Double> {
        class Candidate(val x: Double, val y: Double, val cost: Double)
        val candidates = ArrayList<Candidate>()
        val seen = HashSet<String>()
        fun offer(x: Double, y: Double) {
            val key = x.toString() + "," + y.toString()
            if (!seen.add(key)) return
            if (seen.size > 4096) error("Layout clearance budget exceeded; select a smaller scope")
            candidates += Candidate(x, y, (x - n.x) * (x - n.x) + (y - n.y) * (y - n.y))
        }
        offer(n.x, n.y)
        while (candidates.isNotEmpty()) {
            candidates.sortByDescending { it.cost }
            val p = candidates.removeAt(candidates.size - 1)
            val hit = findHit(p.x, p.y) ?: return p.x to p.y
            val dx = (n.w + hit.w) / 2 + gap + .1
            val dy = (n.h + hit.h) / 2 + gap + .1
            offer(hit.x - dx, p.y); offer(hit.x + dx, p.y); offer(p.x, hit.y - dy); offer(p.x, hit.y + dy)
        }
        error("Layout has no vacant position")
    }

    /** top-left placement of a corner-anchored box clear of corner-anchored obstacles by a cable's width */
    fun placement(x: Double, y: Double, w: Double, h: Double, obstacles: List<CenterBox>): Pair<Double, Double> {
        if (obstacles.size > PatchLimits.nodes) error("Landscape placement budget exceeded")
        val n = CenterBox(x + w / 2, y + h / 2, w, h)
        val others = obstacles.map { CenterBox(it.x + it.w / 2, it.y + it.h / 2, it.w, it.h) }
        val p = vacancy(n, PatchLimits.cable) { px, py ->
            others.find { b -> kotlin.math.abs(px - b.x) < (n.w + b.w) / 2 + PatchLimits.cable && kotlin.math.abs(py - b.y) < (n.h + b.h) / 2 + PatchLimits.cable }
        }
        return (p.first - w / 2) to (p.second - h / 2)
    }
}
