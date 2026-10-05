package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import kotlin.math.sqrt

/**
 * What a SUMO class reads of a proposition stated of the classes above it, on the sphere rather than by NAL deduction.
 *
 * A proposition's evidence for one class is a K = 2 row (w+, w−): a point on the circle √p, whose spot shrinks as 1/(2√T).
 * NAL deduction carried an ancestor's belief down with an axiomatic is-a premise (f 1, c 0.99), discounting it at every
 * step and revising it into the class's own evidence whenever the bases were disjoint, so a class stated to differ was
 * still pulled toward the default by however much evidence the default had. Here the rows are compared instead:
 * - an ancestor's row that cannot be told apart from the class's ([agree]: the two-sample Hellinger statistic
 *   8·T₁T₂/(T₁+T₂)·(1 − BC) under χ²₁ at 95%, 3.841) is the same point read twice, and pools into it at full weight;
 * - one that can be told apart is an exception: the class keeps its own row, and the ancestor's is not added.
 * A class with no evidence of its own reads its nearest ancestor's row, pooled with every further ancestor's that agrees
 * with what is pooled so far. Rows whose sources overlap what is pooled are not added, so no source counts twice.
 */
object Inheritance {
    /** χ²₁ at 95%. */
    private const val CHI2_1 = 3.841458820694124

    /** The pooled evidence the class reads ([belief]), the part of it that came from above ([inherited]), and from which rows ([from], indices into the ancestors given). */
    class Read(val belief: EvidenceCoord, val inherited: EvidenceCoord?, val from: IntArray)

    /** True when the two K = 2 rows cannot be told apart at 95%; true when either holds no evidence. */
    fun agree(a: EvidenceCoord, b: EvidenceCoord): Boolean {
        val ta = a.total.toDouble(); val tb = b.total.toDouble()
        if (ta <= 0.0 || tb <= 0.0) return true
        val bc = (sqrt(a.positive * b.positive.toDouble()) + sqrt(a.negative * b.negative.toDouble())) / sqrt(ta * tb)
        val t1 = ta / Nal.UNIT; val t2 = tb / Nal.UNIT
        return 8.0 * t1 * t2 / (t1 + t2) * (1.0 - bc.coerceIn(0.0, 1.0)) <= CHI2_1
    }

    /**
     * The class's reading, from its own row [own] (null when it states nothing) over sources [ownBasis], and its ancestors'
     * rows [above] (packed [EvidenceCoord]s) over sources [aboveBasis], nearest first. Null when there is nothing to read.
     */
    fun read(own: EvidenceCoord?, ownBasis: RoaringSeries?, above: LongArray, aboveBasis: List<RoaringSeries>): Read? {
        var belief = own
        var inherited: EvidenceCoord? = null
        var from = IntArray(above.size); var n = 0
        // Sources already pooled, checked pairwise (a source shared with any pooled row overlaps the pool).
        fun overlaps(b: RoaringSeries): Boolean {
            if (ownBasis != null && b.intersects(ownBasis)) return true
            for (x in 0 until n) if (b.intersects(aboveBasis[from[x]])) return true
            return false
        }
        for (i in above.indices) {
            val e = EvidenceCoord(above[i])
            val now = belief
            if (now != null && (!agree(now, e) || overlaps(aboveBasis[i]))) continue
            belief = if (now == null) e else revise(now, e)
            inherited = inherited?.let { revise(it, e) } ?: e
            from[n++] = i
        }
        if (n < from.size) from = from.copyOf(n)
        return belief?.let { Read(it, inherited, from) }
    }
}
