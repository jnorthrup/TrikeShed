package narchy.spacegraph

import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.DrawItem
import narchy.spacegraph.graphics.spi.FramePlan
import narchy.spacegraph.graphics.spi.PathPart
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private typealias Polygon = List<Pair<Double, Double>>

/**
 * Wood must not hide what stands on it. The scope paints back to front by one depth key per item, so a panel can be
 * painted after a book it does not actually stand in front of. These tests measure that on the real plan: every node
 * polygon is clipped against every wood face painted after it, in the bay the node stands in.
 */
class CuratorShelfOcclusionTest {
    private val width = 1280.0
    private val height = 800.0
    private val t0 = 100_000.0

    /** Project to its books as (sections, statements per section). Bays stand in project-name order. */
    private val projects = listOf(
        "alpha" to listOf(3 to 4, 5 to 6, 2 to 3, 6 to 8, 4 to 4, 3 to 5),
        "beta" to listOf(2 to 2, 3 to 3),
        "gamma" to listOf(8 to 6, 1 to 1, 4 to 4, 5 to 5, 2 to 2),
    )

    private class Library(val scope: CuratorScope, val bayOf: Map<Int, Int>)

    private fun library(): Library {
        val scope = CuratorScope(Viewport(width.toInt(), height.toInt()))
        val keys = ArrayList<String>(); val types = ArrayList<String>(); val parents = ArrayList<Int>()
        fun add(key: String, type: String, parent: Int): Int { keys.add(key); types.add(type); parents.add(parent); return keys.size - 1 }
        val root = add("book.curate", "program", -1)
        val bayOf = HashMap<Int, Int>()
        for ((k, entry) in projects.withIndex()) {
            val project = entry.first; val books = entry.second
            scope.shelve(project, books.indices.map { "$project-$it" })
            for ((b, shape) in books.withIndex()) {
                val book = add("book.curate/$project-$b", "scope", root); bayOf[book] = k
                for (s in 0 until shape.first) {
                    val section = add("book.curate/$project-$b/s$s", "book.section", book); bayOf[section] = k
                    for (t in 0 until shape.second) bayOf[add("book.curate/$project-$b/s$s/$t", "book.statement", section)] = k
                }
            }
        }
        scope.drain(mapOf(
            "now" to 1000, "epoch" to 1, "head" to 0, "nodesFrom" to 0,
            "nodeKeys" to keys, "nodeTypes" to types, "nodeParents" to parents, "runs" to listOf("curate"),
            "t" to emptyList<Int>(), "kind" to emptyList<Int>(), "run" to emptyList<Int>(), "node" to emptyList<Int>(), "note" to emptyList<String>(),
        ), 1000.0)
        return Library(scope, bayOf)
    }

    private class Cover(val node: Int, val wood: String, val fraction: Double, val sameBay: Boolean)

    private val node = Regex("^[dsc]-(\\d+)$")
    private val wood = Regex("^shelf-(\\d+)-(\\d+)-(\\d+)$")

    private fun polygon(path: DrawItem.Path): Polygon {
        val out = ArrayList<Pair<Double, Double>>()
        for (part in path.parts.view) when (part) {
            is PathPart.Move -> out.add(part.p.x to part.p.y)
            is PathPart.Line -> out.add(part.p.x to part.p.y)
            else -> {}
        }
        return out
    }

    private fun signedArea(p: Polygon): Double {
        var s = 0.0
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.first * b.second - b.first * a.second }
        return s / 2
    }

    /** The part of [subject] inside the convex [clipper] (Sutherland-Hodgman). */
    private fun clipped(subject: Polygon, clipper: Polygon): Polygon {
        if (subject.isEmpty() || clipper.size < 3) return emptyList()
        val ccw = signedArea(clipper) > 0
        var out = subject
        for (i in clipper.indices) {
            val a = clipper[i]; val b = clipper[(i + 1) % clipper.size]
            fun inside(p: Pair<Double, Double>): Boolean {
                val cross = (b.first - a.first) * (p.second - a.second) - (b.second - a.second) * (p.first - a.first)
                return if (ccw) cross >= 0 else cross <= 0
            }
            fun cut(p: Pair<Double, Double>, q: Pair<Double, Double>): Pair<Double, Double> {
                val den = (p.first - q.first) * (a.second - b.second) - (p.second - q.second) * (a.first - b.first)
                if (abs(den) < 1e-12) return q
                val t = ((p.first - a.first) * (a.second - b.second) - (p.second - a.second) * (a.first - b.first)) / den
                return (p.first + t * (q.first - p.first)) to (p.second + t * (q.second - p.second))
            }
            val next = ArrayList<Pair<Double, Double>>()
            for (j in out.indices) {
                val cur = out[j]; val prev = out[(j + out.size - 1) % out.size]
                if (inside(cur)) { if (!inside(prev)) next.add(cut(prev, cur)); next.add(cur) }
                else if (inside(prev)) next.add(cut(prev, cur))
            }
            out = next
            if (out.isEmpty()) return out
        }
        return out
    }

    private class Painted(val order: Int, val id: String, val polygon: Polygon, val area: Double)

    /** Every (node, wood face painted after it) pair whose polygons overlap, as the fraction of the node covered. */
    private fun covers(plan: FramePlan, bayOf: Map<Int, Int>): List<Cover> {
        val nodes = ArrayList<Painted>(); val woods = ArrayList<Painted>()
        for ((order, item) in plan.items.view.withIndex()) {
            if (item !is DrawItem.Path) continue
            val isNode = node.matches(item.entityId); val isWood = wood.matches(item.entityId)
            if (!isNode && !isWood) continue
            val poly = polygon(item)
            if (poly.size < 3) continue
            val painted = Painted(order, item.entityId, poly, abs(signedArea(poly)))
            if (isNode) nodes.add(painted) else woods.add(painted)
        }
        val out = ArrayList<Cover>()
        for (n in nodes) {
            if (n.area < 16.0) continue
            val ordinal = node.matchEntire(n.id)!!.groupValues[1].toInt()
            val bay = bayOf[ordinal]
            for (w in woods) {
                if (w.order < n.order) continue
                val k = wood.matchEntire(w.id)!!.groupValues[1].toInt()
                val overlap = abs(signedArea(clipped(n.polygon, w.polygon)))
                if (overlap > 0.0) out.add(Cover(ordinal, w.id, overlap / n.area, bay == k))
            }
        }
        return out
    }

    private val faceNames = listOf("back", "front", "underside", "top", "left", "right")

    private fun describe(c: Cover, boards: Map<Int, Int>): String {
        val m = wood.matchEntire(c.wood)!!.groupValues
        val k = m[1].toInt(); val si = m[2].toInt(); val fi = m[3].toInt()
        val b = boards[k] ?: 0
        val solid = when {
            si == 0 -> "back panel"; si == 1 -> "left post"; si == 2 -> "right post"
            si < 3 + b -> "board ${si - 3}"; si < 5 + b -> "ladder rail"; else -> "ladder rung"
        }
        return "node ${c.node} ${(c.fraction * 100).toInt()}% by $solid ${faceNames[fi]} (${c.wood})"
    }

    private fun boardsPerBay(): Map<Int, Int> = projects.withIndex().associate { (k, p) -> k to (p.second.size + CuratorScope.ROW - 1) / CuratorScope.ROW + 1 }

    private fun poses(): List<Pair<String, (CuratorScope) -> Unit>> {
        val out = ArrayList<Pair<String, (CuratorScope) -> Unit>>()
        out.add("fit" to { _ -> })
        val spots = listOf("left" to (.15 to .5), "centre" to (.5 to .5), "right" to (.85 to .5), "top" to (.5 to .2), "bottom" to (.5 to .8))
        for (factor in listOf(1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0)) for ((name, at) in spots)
            out.add("zoom x$factor at $name" to { s -> s.zoom(factor, width * at.first, height * at.second) })
        for (dx in listOf(-400.0, -200.0, 200.0, 400.0)) out.add("pan $dx" to { s -> s.pan(dx, 0.0) })
        // An open book is lifted toward the eye, and the camera eases onto it (ordinals: alpha's first book, gamma's third).
        out.add("open alpha-0" to { s -> s.select(1); settle(s) })
        out.add("open gamma-2" to { s -> s.select(237); settle(s) })
        return out
    }

    private fun settle(s: CuratorScope) { for (k in 1..200) s.frame(t0 + 20.0 * k) }

    @Test fun noWoodHidesANodeInAnyPose() {
        val boards = boardsPerBay()
        val report = ArrayList<String>()
        var worstOverall = 0.0
        var occludedPoses = 0
        for ((name, pose) in poses()) {
            val lib = library()
            lib.scope.frame(t0)
            pose(lib.scope)
            val plan = lib.scope.frame(t0 + 16)
            val all = covers(plan, lib.bayOf)
            val own = all.filter { it.sameBay && it.fraction > TOLERANCE }
            val cross = all.filter { !it.sameBay && it.fraction > TOLERANCE }
            val nodes = plan.items.view.count { it is DrawItem.Path && node.matches(it.entityId) }
            val worst = own.maxByOrNull { it.fraction }
            if (worst != null) { occludedPoses++; worstOverall = maxOf(worstOverall, worst.fraction) }
            val kinds = own.groupBy { describe(it, boards).substringAfter(" by ").substringBefore(" (") }.mapValues { it.value.size }
            val line = "$name: nodes=$nodes covered=${own.map { it.node }.toSet().size} crossBay=${cross.map { it.node }.toSet().size}" +
                (if (worst != null) " worst=${describe(worst, boards)} kinds=$kinds" else "")
            println("OCCLUSION $line")
            if (own.isNotEmpty() || cross.isNotEmpty()) report.add(line)
        }
        println("OCCLUSION poses=${poses().size} occluded=$occludedPoses worst=${(worstOverall * 100).toInt()}%")
        assertTrue(report.isEmpty(), "wood is painted over nodes in ${report.size} of ${poses().size} poses:\n" + report.take(14).joinToString("\n"))
    }

    /** The statement the measurement above rests on: wood first, so nothing a depth key mis-orders can put it over a node. */
    @Test fun allWoodIsPaintedBeforeAnythingElseOfTheScene() {
        for ((name, pose) in poses()) {
            val lib = library()
            lib.scope.frame(t0)
            pose(lib.scope)
            val ids = lib.scope.frame(t0 + 16).items.view.map { it.entityId }.toList()
            val lastWood = ids.indexOfLast { wood.matches(it) }
            val firstOther = ids.indexOfFirst { !wood.matches(it) }
            assertTrue(firstOther < 0 || lastWood < firstOther, "$name: wood at $lastWood is painted after ${ids.getOrNull(firstOther)} at $firstOther")
        }
    }

    private companion object { const val TOLERANCE = 0.005 }
}
