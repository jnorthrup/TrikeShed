package borg.trikeshed.lcnc.rollup

data class RollupSpec(
    val targetPropertyId: String,           // property on the related database
    val function: RollupFunction,
    val groupByPropertyId: String? = null,
) {
    init { require(targetPropertyId.isNotBlank()) { "targetPropertyId blank" } }
}
