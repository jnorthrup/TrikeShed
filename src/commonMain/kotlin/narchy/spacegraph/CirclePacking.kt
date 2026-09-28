package narchy.spacegraph

import kotlin.math.*

/**
 * Front-chain circle packing (Wang et al. 2006, the algorithm of d3.packSiblings): each circle is placed
 * tangent to two on the front chain, none overlapping, then all are enclosed and centered on the origin.
 */
object CirclePacking {
    /** Places circles of radius [r] into [x], [y] about the origin; returns the radius of a circle enclosing all. */
    fun pack(r: DoubleArray, x: DoubleArray, y: DoubleArray): Double {
        val n = r.size
        if (n == 0) return 0.0
        x[0] = 0.0; y[0] = 0.0
        if (n == 1) return r[0]
        x[0] = -r[1]; x[1] = r[0]; y[1] = 0.0
        if (n == 2) return r[0] + r[1]
        val next = IntArray(n); val prev = IntArray(n)
        /** Puts [c] tangent to [p] and [q] (d3's place(b, a, c) with b = [p], a = [q]). */
        fun place(p: Int, q: Int, c: Int) {
            val dx = x[p] - x[q]; val dy = y[p] - y[q]; val d2 = dx * dx + dy * dy
            if (d2 > 0) {
                val a2 = (r[q] + r[c]).let { it * it }; val b2 = (r[p] + r[c]).let { it * it }
                if (a2 > b2) {
                    val u = (d2 + b2 - a2) / (2 * d2); val v = sqrt(max(0.0, b2 / d2 - u * u))
                    x[c] = x[p] - u * dx - v * dy; y[c] = y[p] - u * dy + v * dx
                } else {
                    val u = (d2 + a2 - b2) / (2 * d2); val v = sqrt(max(0.0, a2 / d2 - u * u))
                    x[c] = x[q] + u * dx - v * dy; y[c] = y[q] + u * dy + v * dx
                }
            } else { x[c] = x[q] + r[c]; y[c] = y[q] }
        }
        fun intersects(a: Int, b: Int): Boolean {
            val dr = r[a] + r[b] - 1e-6; val dx = x[b] - x[a]; val dy = y[b] - y[a]
            return dr > 0 && dr * dr > dx * dx + dy * dy
        }
        fun score(a: Int): Double {
            val b = next[a]; val ab = r[a] + r[b]
            val dx = (x[a] * r[b] + x[b] * r[a]) / ab; val dy = (y[a] * r[b] + y[b] * r[a]) / ab
            return dx * dx + dy * dy
        }
        place(1, 0, 2)
        var a = 0; var b = 1
        next[0] = 1; prev[2] = 1; next[1] = 2; prev[0] = 2; next[2] = 0; prev[1] = 0
        var i = 3
        pack@ while (i < n) {
            val c = i
            place(a, b, c)
            var j = next[b]; var k = prev[a]; var sj = r[b]; var sk = r[a]
            do {
                if (sj <= sk) {
                    if (intersects(j, c)) { b = j; next[a] = b; prev[b] = a; continue@pack }
                    sj += r[j]; j = next[j]
                } else {
                    if (intersects(k, c)) { a = k; next[a] = b; prev[b] = a; continue@pack }
                    sk += r[k]; k = prev[k]
                }
            } while (j != next[k])
            prev[c] = a; next[c] = b; next[a] = c; prev[b] = c; b = c
            var best = score(a); var q = next[c]
            while (q != b) { val s = score(q); if (s < best) { a = q; best = s }; q = next[q] }
            b = next[a]
            i++
        }
        // Enclosure: centered on the packing's extent, reaching the farthest rim.
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (m in 0 until n) { x0 = min(x0, x[m] - r[m]); x1 = max(x1, x[m] + r[m]); y0 = min(y0, y[m] - r[m]); y1 = max(y1, y[m] + r[m]) }
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2; var rim = 0.0
        for (m in 0 until n) { x[m] -= cx; y[m] -= cy; rim = max(rim, hypot(x[m], y[m]) + r[m]) }
        return rim
    }
}
