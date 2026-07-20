package borg.trikeshed.lcnc.rollup

sealed class RollupFunction(val name: String) {
    object Sum : RollupFunction("sum")
    object Count : RollupFunction("count")
    object Min : RollupFunction("min")
    object Max : RollupFunction("max")
    object Avg : RollupFunction("avg")
    object Stddev : RollupFunction("stddev")
    data class Percentile(val p: Double) : RollupFunction("percentile_$p")
    object CountValues : RollupFunction("count_values")
    object CountUnique : RollupFunction("count_unique")
    object Empty : RollupFunction("empty")
    object NotEmpty : RollupFunction("not_empty")
    object Earliest : RollupFunction("earliest_date")
    object Latest : RollupFunction("latest_date")

    companion object {
        fun fromName(name: String): RollupFunction? = when (name) {
            "sum" -> Sum
            "count" -> Count
            "min" -> Min
            "max" -> Max
            "avg" -> Avg
            "stddev" -> Stddev
            else -> if (name.startsWith("percentile_")) {
                Percentile(name.substringAfter("percentile_").toDouble())
            } else null
        }
    }
}
