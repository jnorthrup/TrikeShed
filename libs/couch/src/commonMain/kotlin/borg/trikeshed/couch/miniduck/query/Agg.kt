package borg.trikeshed.couch.miniduck.query

/**
 * Aggregation functions for GROUP BY operations.
 *
 * Each aggregation knows:
 *   - its output column name
 *   - how to initialize an accumulator
 *   - how to fold a row value into the accumulator
 *   - how to finalize the accumulator to a result value
 *
 * Donor: DuckDB aggregate functions, SQL standard aggregates.
 */
sealed class Agg {
    abstract val outputName: String
    abstract fun createAccumulator(): AggAccumulator

    /** COUNT(*) — counts all rows, including nulls. */
    data class Count(val column: String = "*") : Agg() {
        override val outputName get() = "count"
        override fun createAccumulator() = object : AggAccumulator {
            var count = 0L
            override fun add(value: Any?) { count++ }
            override fun result(): Any = count
        }
    }

    /** SUM(column) — sums numeric values, ignoring nulls. */
    data class Sum(val column: String) : Agg() {
        override val outputName get() = "sum_$column"
        override fun createAccumulator() = object : AggAccumulator {
            var sum = 0.0
            override fun add(value: Any?) {
                if (value is Number) sum += value.toDouble()
            }
            override fun result(): Any = sum
        }
    }

    /** AVG(column) — average of numeric values, ignoring nulls. */
    data class Avg(val column: String) : Agg() {
        override val outputName get() = "avg_$column"
        override fun createAccumulator() = object : AggAccumulator {
            var sum = 0.0
            var count = 0L
            override fun add(value: Any?) {
                if (value is Number) { sum += value.toDouble(); count++ }
            }
            override fun result(): Any = if (count > 0) sum / count else 0.0
        }
    }

    /** MIN(column) — minimum value, ignoring nulls. */
    data class Min(val column: String) : Agg() {
        override val outputName get() = "min_$column"
        override fun createAccumulator() = object : AggAccumulator {
            var min: Double = Double.MAX_VALUE
            override fun add(value: Any?) {
                if (value is Number) min = minOf(min, value.toDouble())
            }
            override fun result(): Any = min
        }
    }

    /** MAX(column) — maximum value, ignoring nulls. */
    data class Max(val column: String) : Agg() {
        override val outputName get() = "max_$column"
        override fun createAccumulator() = object : AggAccumulator {
            var max: Double = Double.MIN_VALUE
            override fun add(value: Any?) {
                if (value is Number) max = maxOf(max, value.toDouble())
            }
            override fun result(): Any = max
        }
    }

    companion object {
        fun count(): Count = Count()
        fun sum(column: String): Sum = Sum(column)
        fun avg(column: String): Avg = Avg(column)
        fun min(column: String): Min = Min(column)
        fun max(column: String): Max = Max(column)
    }
}

/** Accumulator interface for aggregation functions. */
interface AggAccumulator {
    fun add(value: Any?)
    fun result(): Any
}
