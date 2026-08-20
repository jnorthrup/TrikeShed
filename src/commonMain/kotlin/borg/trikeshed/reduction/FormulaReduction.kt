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
 *
 * **Carrier caveat.** `seriesCarrierAlg` dispatches on `is Join<*, *>`, and a [RowVec] is
 * *itself* a `Join` — so passing a single row where a Cursor is expected is not rejected;
 * each cell-`Join` is then treated as a row and the output is garbage. The collision is
 * structural and cannot be resolved at runtime: always hand this a `Series<RowVec>`.
 */
class FormulaReduction private constructor(
    val source: String,
    private val ast: FormulaAST,
) : AbstractLcncReduction<String, RowVec, List<Any?>, List<Any?>>(
    keyAlg = formulaKeyAlg(source),
    valueAlg = formulaValueAlg(ast),
    phaseAlg = LcncPhaseAlg.forgePhaseAlg,
    carrierAlg = LcncCarrierAlg.seriesCarrierAlg(),
) {
    constructor(source: String) : this(source, FormulaParser(source).parse())

    /**
     * Every row carries the same key, so the MAP/REDUCE/REREDUCE grouping is a no-op here and the
     * result is exactly the per-row projection. Taking it directly keeps the hot path a single lazy
     * `α` projection; going through the template's persistent-list fold would copy the accumulator
     * once per row (O(n²)). [executeWithCheckpoints] still runs the full pipeline for inspection,
     * and `executeMatchesCheckpointPipeline` in FormulaRowVecTest pins the two to the same answer.
     */
    @Suppress("UNCHECKED_CAST")
    override fun execute(input: ReductionCarrier<*>): List<Any?> =
        (input as ReductionCarrier<RowVec>).map { ast.evaluate(it) }.toList()

    override fun formatOutput(reduced: Any): List<Any?> {
        @Suppress("UNCHECKED_CAST")
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
 *
 * **Bootstrap only.** The read-modify-write is not atomic and `registry` cannot be `@Volatile`
 * in commonMain, so concurrent registrations may lose one another or go unseen by other threads.
 * Call this during single-threaded startup, not from running pipelines.
 */
fun registerReduction(key: String, reduction: LcncReduction<*, *, *, *>): LcncReduction<*, *, *, *> {
    ReducerRegistry.registry = ReducerRegistry.registry + (key to reduction)
    return reduction
}

/**
 * Parse [source] into a [FormulaReduction] and admit it to the registry under [key].
 *
 * [key] is a **direct-lookup** key (`ReducerRegistry.registry[key]`), not a dispatch category:
 * [ReducerRegistry.runFor] resolves by `Capability.category`, and no `Capability` yields
 * `"formula"`, so a formula is not reachable through `runFor` until a category is minted for it.
 * The default key means two different formulas registered without an explicit [key] collide —
 * pass a distinct [key] (e.g. the block id) when registering more than one.
 */
fun registerFormulaReduction(source: String, key: String = "formula"): FormulaReduction =
    FormulaReduction(source).also { registerReduction(key, it) }
