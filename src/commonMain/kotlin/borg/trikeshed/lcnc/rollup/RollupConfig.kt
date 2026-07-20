package borg.trikeshed.lcnc.rollup

data class RollupConfig(
    val decimalPlaces: Int = 2,
    val showEmptyGroups: Boolean = false,
    val groupBy: List<String> = emptyList(),
) {
    init { require(decimalPlaces in 0..10) { "decimalPlaces out of range" } }
}
