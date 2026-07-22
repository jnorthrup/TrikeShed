package borg.trikeshed.lcnc.rollup

data class RollupResult(
    val function: RollupFunction,
    val value: Double?,                     // null when no rows / not applicable
    val sampleSize: Int,
    val isApproximation: Boolean = false,
    val groups: Map<String, RollupResult>? = null,
)
