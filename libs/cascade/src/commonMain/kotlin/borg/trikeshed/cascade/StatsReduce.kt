package borg.trikeshed.cascade

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlin.math.min
import kotlin.math.max

/* ── Stats Reduce Monoid ─────────────────────────────────────────────── *
 *
 * Commutative monoid producing {sum, avg, min, max, count} per metric column.
 *
 * Associative: (a ⊕ b) ⊕ c = a ⊕ (b ⊕ c)
 * Commutative: a ⊕ b = b ⊕ a
 * Identity:    StatsAccum(count=0, sum=0.0, min=+∞, max=-∞)
 *
 * This supports both:
 *   - direct reduce:  fold values → partial accumulators
 *   - rereduce:       combine partial accumulators → final
 *
 * RowVec K carries the ColumnMeta↻ for each cell — the accumulator
 * materializes as a RowVec when the reduce completes.
 */

data class StatsAccum(
    val sum: Double = 0.0,
    val min: Double = Double.POSITIVE_INFINITY,
    val max: Double = Double.NEGATIVE_INFINITY,
    val count: Int = 0,
) {
    val avg: Double get() = if (count > 0) sum / count else 0.0

    /** Monoid combine — associative, commutative. */
    infix fun combine(other: StatsAccum): StatsAccum = StatsAccum(
        sum = this.sum + other.sum,
        min = min(this.min, other.min),
        max = max(this.max, other.max),
        count = this.count + other.count,
    )

    companion object {
        /** Identity element. */
        val IDENTITY = StatsAccum()

        /** Lift a single numeric value into the monoid. */
        fun lift(value: Any?): StatsAccum = when (value) {
            is Double -> StatsAccum(sum = value, min = value, max = value, count = 1)
            is Float  -> StatsAccum(sum = value.toDouble(), min = value.toDouble(), max = value.toDouble(), count = 1)
            is Int    -> StatsAccum(sum = value.toDouble(), min = value.toDouble(), max = value.toDouble(), count = 1)
            is Long   -> StatsAccum(sum = value.toDouble(), min = value.toDouble(), max = value.toDouble(), count = 1)
            else      -> IDENTITY
        }
    }
}

/**
 * RowReducer for stats monoid over metric columns.
 * Key columns (non-metric) take the first-seen value.
 * Metric columns accumulate via StatsAccum combine.
 */
object StatsReduce : RowReducer {

    override fun invoke(acc: Any?, value: Any?): Any? = when {
        acc == null  -> value
        value == null -> acc
        acc is StatsAccum -> acc combine StatsAccum.lift(value)
        value is Number -> StatsAccum.lift(acc) combine StatsAccum.lift(value)
        else -> acc  // non-metric: first-wins
    }
}

/**
 * Apply stats reduce to a grouped Cursor, producing a new Cursor
 * where each metric column holds a StatsAccum and key columns hold their group value.
 */
fun Cursor.statsGroupBy(axis: IntArray): Cursor =
    this.groupBy(axis) { acc, value -> StatsReduce(acc, value) }

/** Extract StatsAccum from a RowVec cell, or null. */
fun RowVec.statsAt(col: Int): StatsAccum? = this[col].a as? StatsAccum

/** Render a stats accumulator as a map. */
fun StatsAccum.toMap(): Map<String, Any> = mapOf(
    "sum" to sum, "avg" to avg,
    "min" to min, "max" to max, "count" to count,
)
