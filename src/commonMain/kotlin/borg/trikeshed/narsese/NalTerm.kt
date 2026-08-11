package borg.trikeshed.narsese

/**
 * NAL (Non-Axiomatic Logic) statement copula. Sealed, data-only: copulas are
 * the language, not the machine. No lifecycle, no manager, no scope.
 */
enum class NalCopula(val symbol: String, val nalLevel: Int) {
    /** NAL1: subject --> predicate */
    INHERITANCE("-->", 1),

    /** NAL2: subject <-> predicate (symmetric) */
    SIMILARITY("<->", 2),

    /** NAL3: premise ==> conclusion */
    IMPLICATION("==>", 3),

    /** NAL4: premise =/> conclusion (predictive, temporal) */
    PREDICTIVE_IMPLICATION("=/>", 4),

    /** NAL5: premise =|> conclusion (concurrent, temporal) */
    CONCURRENT_IMPLICATION("=|>", 5),

    /** NAL8 structural product: (*, a, b) */
    PRODUCT("*", 8),

    /** NAL7 equivalence: premise <=> conclusion */
    EQUIVALENCE("<=>", 7),
    ;

    val isTemporal: Boolean get() = this == PREDICTIVE_IMPLICATION || this == CONCURRENT_IMPLICATION
    val isSymmetric: Boolean get() = this == SIMILARITY || this == EQUIVALENCE
}
