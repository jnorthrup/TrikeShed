package borg.trikeshed.reduction

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lcnc.formula.FormulaAST
import borg.trikeshed.lcnc.formula.FormulaParser
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.toList

/**
 * Formula-as-reduction: wraps a parsed LCNC formula so it participates in the
 * unified reduction pipeline. Each carried [RowVec] is evaluated against the
 * formula AST; the output is the ordered list of per-row results.
 *
 * K   = the formula source text (single-group key)
 * V   = [RowVec] (Cursor substrate row)
 * Acc = accumulated per-row results (ordered)
 * Out = flat ordered list of results across all groups
 *
 * The carrier input is a `Series<RowVec>` (i.e. a Cursor) or an `Array<RowVec>`,
 * per [LcncCarrierAlg.seriesCarrierAlg].
 */
class FormulaReduction private constructor(
    val source: String,
    ast: FormulaAST,
) : AbstractLcncReduction<String, RowVec, List<Any?>, List<Any?>>(
    keyAlg = formulaKeyAlg(source),
    valueAlg = formulaValueAlg(ast),
    phaseAlg = LcncPhaseAlg.forgePhaseAlg,
    carrierAlg = LcncCarrierAlg.seriesCarrierAlg(),
) {
    constructor(source: String) : this(source, FormulaParser(source).parse())

    @Suppress("UNCHECKED_CAST")
    override fun formatOutput(reduced: Any): List<Any?> {
        val carrier = reduced as ReductionCarrier<Join<String, List<Any?>>>
        return carrier.map { it.b }.toList().flatten()
    }
}

/** Single-group key algebra: every row of a formula run belongs to the one group named by [source]. */
private fun formulaKeyAlg(source: String): KeyAlg<String> {
    val sourceExtractor = KeyExtractor<Any, String> { source }
    return object : KeyAlg<String> {
        override val extractor = sourceExtractor
        override val hierarchy = object : KeyHierarchy<String> {
            override val levels = listOf(sourceExtractor)
            override fun compositeKey(input: Any): List<String> = levels.map { it.extract(input) }
            override fun prefix(key: List<String>, depth: Int): List<String> = key.take(minOf(depth, key.size))
        }
        override val order = LcncKeyAlg.naturalKeyOrder<String>()
    }
}

private fun formulaValueAlg(ast: FormulaAST): ValueAlg<RowVec, List<Any?>> =
    object : ValueAlg<RowVec, List<Any?>> {
        override val initial: List<Any?> = emptyList()
        override val folder = Folder<RowVec, List<Any?>> { acc, row -> acc + ast.evaluate(row) }
        override val merger = Merger<List<Any?>> { partials -> partials.toList().flatten() }
    }

/**
 * Registration = copy + reassign of [ReducerRegistry.registry] (the object carries a
 * plain `var` map and no register() method). Additive: existing keys are preserved,
 * an existing binding under [key] is replaced.
 */
fun registerReduction(key: String, reduction: LcncReduction<*, *, *, *>): LcncReduction<*, *, *, *> {
    ReducerRegistry.registry = ReducerRegistry.registry + (key to reduction)
    return reduction
}

/** Parse [source] into a [FormulaReduction] and admit it to the registry under [key]. */
fun registerFormulaReduction(source: String, key: String = "formula"): FormulaReduction =
    FormulaReduction(source).also { registerReduction(key, it) }
