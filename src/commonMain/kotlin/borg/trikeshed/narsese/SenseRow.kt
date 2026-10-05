package borg.trikeshed.narsese

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * A word's sense judgments as one categorical row: the classes offered ([classes], ascending ids, the judge's
 * none-of-these option among them), the probability mass each received ([positive], Σp in [Nal.UNIT] milli-units), the
 * judgments counted ([judgments]), and the menus they were asked under ([menus]). Every judgment spreads one unit over
 * its menu, so the row is a Dirichlet count over its K classes, and √(w_c / T) over it is a point on the unit sphere.
 *
 * The estimate of each class's share is the Jeffreys (Dirichlet ½,…,½) posterior mean, (w_c + ½)/(T + K/2), whose
 * confidence T/(T + K/2) is NARS confidence over K outcomes: at K = 2 the row is one NARS belief at horizon 1, and its
 * shares are the expectations of the class and its complement. Where the row pools menus that differ (one book offered a
 * class another did not), a judgment that chose none-of-these may have meant a class its menu lacked: its none mass is
 * shared over those classes and the unlisted residue in proportion to their shares, to the fixed point (EM over the
 * censored count). A class offered in every judgment takes no shared mass, so its share stays (w_c + ½)/(T + K/2).
 *
 * The angle between two rows is the Bhattacharyya angle on that sphere; twice it is the Fisher–Rao distance, the only
 * metric left unchanged by splitting or merging classes consistently (Čencov). Rows are compared over the classes both
 * offered in every judgment, the rest merged as one, which that invariance makes a fair comparison.
 */
class SenseRow internal constructor(
    val classes: IntArray,
    val positive: LongArray,
    val judgments: Int,
    /** The class id of the none-of-these option, or -1. */
    val noneClass: Int,
    /** Per menu the row pools: the indices into [classes] it did not offer, and the none mass its judgments gave. */
    private val menuMissing: Array<IntArray>,
    private val menuNone: LongArray,
) {
    /** K: the classes the row holds. */
    val width: Int get() = classes.size

    /** T: the row's mass in milli-units, one unit per judgment. */
    val mass: Long = positive.sum()

    /** The index of the none option in [classes], or -1. */
    val none: Int = if (noneClass < 0) -1 else classes.seek(noneClass)

    /** T / (T + K/2): the weight of the row against the prior, NARS confidence over K outcomes. */
    val confidence: Double get() = mass.toDouble() / (mass + width * Nal.UNIT / 2.0)

    /** True when class [i] was offered in every judgment the row holds. */
    fun everywhere(i: Int): Boolean {
        for (m in menuMissing) if (m.seek(i) >= 0) return false
        return true
    }

    /** Each class's mass with the censored none mass shared out: the completed count, in milli-units. */
    val completed: DoubleArray by lazy { complete() }

    /** Each class's estimated share. */
    val share: DoubleArray by lazy {
        val c = completed; val d = mass + width * Nal.UNIT / 2.0
        DoubleArray(width) { (c[it] + Nal.UNIT / 2.0) / d }
    }

    private fun complete(): DoubleArray {
        val k = width
        val c = DoubleArray(k) { positive[it].toDouble() }
        val open = (menuMissing.indices).filter { menuMissing[it].isNotEmpty() && menuNone[it] > 0 }
        if (none < 0 || open.isEmpty()) return c
        val d = mass + k * Nal.UNIT / 2.0
        var pi = DoubleArray(k) { (c[it] + Nal.UNIT / 2.0) / d }
        repeat(EM_ROUNDS) {
            val next = DoubleArray(k) { positive[it].toDouble() }
            for (g in open) {
                val miss = menuMissing[g]
                var z = pi[none]
                for (i in miss) z += pi[i]
                if (z <= 0.0) continue
                for (i in miss) { val a = menuNone[g] * pi[i] / z; next[i] += a; next[none] -= a }
            }
            var delta = 0.0
            val p = DoubleArray(k) { val v = (next[it] + Nal.UNIT / 2.0) / d; delta = maxOf(delta, abs(v - pi[it])); v }
            pi = p
            for (i in 0 until k) c[i] = next[i]
            if (delta < EM_TOLERANCE) return c
        }
        return c
    }

    /** The index of the leading class other than none, by share; -1 for a row with none. */
    val top: Int
        get() {
            val s = share; var best = -1
            for (i in 0 until width) if (i != none && (best < 0 || s[i] > s[best])) best = i
            return best
        }

    /** Confidence T/(T + K/2) reaches num/den, exactly: 2(den − num)·T ≥ num·K units. */
    fun eternal(num: Long = ETERNAL_NUM, den: Long = ETERNAL_DEN): Boolean =
        2 * (den - num) * mass >= num * width * Nal.UNIT

    /**
     * Class [i]'s share reaches num/den. For a class offered in every judgment the share is (w + ½)/(T + K/2), so the
     * test is the integer plane den·(2w + 1) ≥ num·(2T + K) in units; a class some menu lacked is read off the fixed point.
     */
    fun holds(i: Int, num: Long = HOLDS_NUM, den: Long = HOLDS_DEN): Boolean =
        if (everywhere(i)) den * (2 * positive[i] + Nal.UNIT) >= num * (2 * mass + width * Nal.UNIT)
        else share[i] * den >= num

    /** The index of the class a production types the word as: the leading class, when the row is eternal and it holds; else -1. */
    fun production(): Int {
        val t = top
        return if (t >= 0 && eternal() && holds(t)) t else -1
    }

    /** True when some menu lacked a class and its none mass is shared out ([completed] differs from [positive]). */
    private val censored: Boolean by lazy {
        none >= 0 && menuMissing.indices.any { menuMissing[it].isNotEmpty() && menuNone[it] > 0 }
    }

    /** True when every class was offered in every judgment: [positive] are the counts and every plane is exact in integers. */
    val exact: Boolean get() = menuMissing.all { it.isEmpty() }

    /**
     * The classes at indices [members] (ascending, none excluded) merged as one hold a share ≥ num/den. Merging Dirichlet
     * classes adds their counts and their Jeffreys halves, so the merged share is (Σw + n/2)/(T + K/2) and the test is
     * the integer plane den·(2Σw + n) ≥ num·(2T + K) in units. One member is [holds]; a SUMO class over several of the
     * row's classes is a nested cap, decided by the same plane. A censored row reads its members off the fixed point.
     */
    fun holdsAll(members: IntArray, num: Long = HOLDS_NUM, den: Long = HOLDS_DEN): Boolean {
        val n = members.size
        if (!censored) {
            var w = 0L
            for (i in members) w += positive[i]
            return den * (2 * w + n * Nal.UNIT) >= num * (2 * mass + width * Nal.UNIT)
        }
        val c = completed; var w = 0.0
        for (i in members) w += c[i]
        return den * (2 * w + n * Nal.UNIT) >= num * (2.0 * mass + width * Nal.UNIT)
    }

    /** The merged share of the classes at [members]: (Σw + n/2)/(T + K/2). */
    fun shareOf(members: IntArray): Double {
        val c = completed; var w = 0.0
        for (i in members) w += c[i]
        return (w + members.size * Nal.UNIT / 2.0) / (mass + width * Nal.UNIT / 2.0)
    }

    /**
     * The angular radius of the row's evidence on the sphere, in radians: 1/(2√T), T in judgments. In √p coordinates the
     * Jeffreys prior is the sphere's uniform measure, so the posterior is ∝ Π y_c^(2w_c): it peaks at y_c² = w_c/T and
     * curves at −4T in every direction among the classes holding mass, a round spot that only T shrinks.
     */
    val radius: Double get() = if (mass <= 0) PI / 2 else 0.5 / sqrt(mass.toDouble() / Nal.UNIT)

    /**
     * Where class [i]'s cap (share ≥ num/den: the angle arccos √(num/den) about the class's corner) stands against the
     * row's spot: the cap's angle less the angle from the corner to the row's point, in spot radii. Positive inside; a
     * |z| under 2 is a spot straddling the cap's edge, a point no one flat face answers for.
     */
    fun margin(i: Int, num: Long = HOLDS_NUM, den: Long = HOLDS_DEN): Double =
        (acos(sqrt(num.toDouble() / den)) - acos(sqrt(share[i].coerceIn(0.0, 1.0)))) / radius

    /** The classes offered in every judgment of both rows, none excepted, as index pairs (this row's, the other's). */
    private fun common(other: SenseRow): Pair<IntArray, IntArray> {
        val a = ArrayList<Int>(); val b = ArrayList<Int>()
        var i = 0; var j = 0
        while (i < width && j < other.width) when {
            classes[i] < other.classes[j] -> i++
            classes[i] > other.classes[j] -> j++
            else -> { if (i != none && everywhere(i) && other.everywhere(j)) { a.add(i); b.add(j) }; i++; j++ }
        }
        return a.toIntArray() to b.toIntArray()
    }

    /** Σ √(p_c q_c) over the classes both rows offered everywhere plus the rest merged as one: 1 for one point. */
    fun bhattacharyya(other: SenseRow): Double {
        if (mass <= 0 || other.mass <= 0) return 1.0
        val (ia, ib) = common(other)
        var s = 0.0; var ra = mass.toDouble(); var rb = other.mass.toDouble()
        for (x in ia.indices) {
            val pa = positive[ia[x]].toDouble(); val pb = other.positive[ib[x]].toDouble()
            s += sqrt(pa * pb); ra -= pa; rb -= pb
        }
        s += sqrt(maxOf(0.0, ra) * maxOf(0.0, rb))
        return (s / sqrt(mass.toDouble() * other.mass)).coerceIn(0.0, 1.0)
    }

    /** The angle between the rows' points, in radians. */
    fun angle(other: SenseRow): Double = acos(bhattacharyya(other))

    /**
     * True when the two rows cannot be told apart as draws of one distribution at 95%: the two-sample Hellinger statistic
     * 8·T₁T₂/(T₁+T₂)·(1 − BC) against χ²(K−1), over the K classes both offered everywhere with the rest merged as one.
     * Jev's soft answers vary less than one-hot counts, so the test is conservative: rows it calls different differ.
     */
    fun homogeneous(other: SenseRow): Boolean {
        if (mass <= 0 || other.mass <= 0) return true
        val k = common(other).first.size + 1
        if (k < 2) return true
        val t1 = mass.toDouble() / Nal.UNIT; val t2 = other.mass.toDouble() / Nal.UNIT
        return 8.0 * t1 * t2 / (t1 + t2) * (1.0 - bhattacharyya(other)) <= chiSquare95(k - 1)
    }

    /** The two rows read as one: classes united, counts summed, each side's menus kept, so a class one side lacked is censored there. */
    operator fun plus(other: SenseRow): SenseRow {
        val ids = IntArray(unionWidth(other)); val sum = LongArray(ids.size)
        val fromA = IntArray(width); val fromB = IntArray(other.width)
        var i = 0; var j = 0; var n = 0
        while (i < width || j < other.width) {
            when {
                j == other.width || (i < width && classes[i] < other.classes[j]) -> { ids[n] = classes[i]; sum[n] = positive[i]; fromA[i] = n; i++ }
                i == width || classes[i] > other.classes[j] -> { ids[n] = other.classes[j]; sum[n] = other.positive[j]; fromB[j] = n; j++ }
                else -> { ids[n] = classes[i]; sum[n] = positive[i] + other.positive[j]; fromA[i] = n; fromB[j] = n; i++; j++ }
            }
            n++
        }
        // A side's menus lack, in the union, what they lacked before and every class only the other side holds.
        fun lifted(row: SenseRow, into: IntArray): Pair<List<IntArray>, List<Long>> {
            val held = BooleanArray(ids.size); for (x in 0 until row.width) held[into[x]] = true
            val absent = (0 until ids.size).filter { !held[it] }
            val missing = ArrayList<IntArray>(); val none = ArrayList<Long>()
            if (row.menuMissing.isEmpty()) { missing.add(absent.toIntArray()); none.add(if (row.none >= 0) row.positive[row.none] else 0L) }
            for (g in row.menuMissing.indices) {
                missing.add((row.menuMissing[g].map { into[it] } + absent).sorted().toIntArray()); none.add(row.menuNone[g])
            }
            return missing to none
        }
        val (ma, na) = lifted(this, fromA); val (mb, nb) = lifted(other, fromB)
        return SenseRow(ids, sum, judgments + other.judgments, if (noneClass >= 0) noneClass else other.noneClass,
            (ma + mb).toTypedArray(), (na + nb).toLongArray())
    }

    private fun unionWidth(other: SenseRow): Int {
        var i = 0; var j = 0; var n = 0
        while (i < width || j < other.width) {
            when {
                j == other.width || (i < width && classes[i] < other.classes[j]) -> i++
                i == width || classes[i] > other.classes[j] -> j++
                else -> { i++; j++ }
            }
            n++
        }
        return n
    }

    companion object {
        /** The myelination cutoff, one in fifty: confidence ≥ 49/50. */
        const val ETERNAL_NUM = 49L
        const val ETERNAL_DEN = 50L

        /** A production holds its class at a share ≥ 7/10. */
        const val HOLDS_NUM = 7L
        const val HOLDS_DEN = 10L

        private const val EM_ROUNDS = 500
        private const val EM_TOLERANCE = 1e-12

        /** One menu's judgments: every class offered in each of them. */
        fun of(classes: IntArray, positive: LongArray, judgments: Int, noneClass: Int): SenseRow =
            SenseRow(classes, positive, judgments, noneClass, emptyArray(), LongArray(0))

        /** The 95th percentile of χ² with [df] degrees of freedom, by the Wilson–Hilferty cube. */
        fun chiSquare95(df: Int): Double {
            val t = 2.0 / (9.0 * df)
            val c = 1.0 - t + 1.6448536 * sqrt(t)
            return df * c * c * c
        }
    }
}

/** The index of [x] in this ascending array, or -1. */
private fun IntArray.seek(x: Int): Int {
    var lo = 0; var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = this[mid]
        if (v < x) lo = mid + 1 else if (v > x) hi = mid - 1 else return mid
    }
    return -1
}
