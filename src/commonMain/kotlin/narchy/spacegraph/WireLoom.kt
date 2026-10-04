package narchy.spacegraph

import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*
import kotlin.math.*

/**
 * A wire loom: bundled strands pass one horizontal band where they meet, entering its left edge and leaving its
 * right, pins ranked by where each strand's ends stand, so the bundles flow through side by side without crossing
 * and one label names them all instead of a label per bundle. Looms stand near where their bundles meet, relaxed
 * apart so no two overlap.
 */
object WireLoom {
    /**
     * [threads] (projected polylines, either direction) woven through [band]: each is returned oriented left end
     * first, eased onto its in-pin on the band's left edge up to its middle, straight across, and eased off its
     * out-pin on the right edge after. Pin `k` stands at `band.y + (k + .5) * gap`; [rows], when given, pins each
     * thread to its own row on both edges, else pins rank by where each end stands.
     */
    fun weave(threads: List<List<Vec3>>, band: Rect, gap: Double, rows: IntArray? = null): List<List<Vec3>> {
        val live = threads.map { if (it.first().x <= it.last().x) it else it.asReversed() }
        val ins = rows ?: rank(live.map { it.first().y }); val outs = rows ?: rank(live.map { it.last().y })
        return live.mapIndexed { t, pts ->
            val pin = Vec3(band.x, band.y + (ins[t] + .5) * gap); val pout = Vec3(band.x + band.width, band.y + (outs[t] + .5) * gap)
            val m = pts.size / 2; val tail = max(1, pts.size - 1 - m)
            val flow = ArrayList<Vec3>(pts.size + 2)
            for (k in 0..m) flow.add(pts[k].lerp(pin, sin(PI / 2 * k / m).pow(2)))
            flow.add(pout)
            for (k in m until pts.size) flow.add(pts[k].lerp(pout, cos(PI / 2 * (k - m) / tail).pow(2)))
            flow
        }
    }

    /** Each value's rank in ascending order. */
    fun rank(v: List<Double>): IntArray {
        val r = IntArray(v.size); v.indices.sortedBy { v[it] }.forEachIndexed { k, t -> r[t] = k }; return r
    }

    /**
     * Band centers for looms of [sizes] (width in x, height in y) drawn toward their [anchors] and pushed apart:
     * each round pulls every loom [pull] of the way to its anchor, then separates every overlapping pair (plus
     * [pad]) along the axis it overlaps least, half each; the last rounds only separate. Starts from [prior] where
     * known, so looms settle rather than jump from frame to frame.
     */
    fun relax(anchors: List<Vec3>, sizes: List<Vec3>, prior: List<Vec3?>, pad: Double = 8.0, pull: Double = .12, rounds: Int = 32): List<Vec3> {
        val n = anchors.size
        val x = DoubleArray(n) { (prior[it] ?: anchors[it]).x }; val y = DoubleArray(n) { (prior[it] ?: anchors[it]).y }
        for (round in 0 until rounds) {
            if (round < rounds - 8) for (i in 0 until n) { x[i] += (anchors[i].x - x[i]) * pull; y[i] += (anchors[i].y - y[i]) * pull }
            for (i in 0 until n) for (j in i + 1 until n) {
                val dx = x[j] - x[i]; val dy = y[j] - y[i]
                val ox = (sizes[i].x + sizes[j].x) / 2 + pad - abs(dx); val oy = (sizes[i].y + sizes[j].y) / 2 + pad - abs(dy)
                if (ox <= 0 || oy <= 0) continue
                if (ox < oy) { val s = if (dx >= 0) ox / 2 else -ox / 2; x[i] -= s; x[j] += s }
                else { val s = if (dy >= 0) oy / 2 else -oy / 2; y[i] -= s; y[j] += s }
            }
        }
        return List(n) { Vec3(x[it], y[it]) }
    }
}
