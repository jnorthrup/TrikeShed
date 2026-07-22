package borg.trikeshed.reduction

import borg.trikeshed.lib.*

/**
 * Single-value fold (Map → Reduce). `fun interface` enables SAM conversion so callers
 * may pass a trailing lambda `(acc, input) -> acc` directly to [ReductionCarrier.fold].
 */
fun interface Folder<In, Acc> {
    fun fold(acc: Acc, input: In): Acc
}

/**
 * Partial merge for rereduce / distributed reduction.
 */
fun interface Merger<Acc> {
    fun merge(partials: Series<Acc>): Acc
}

/**
 * Built-in reducers matching Forge BuiltinReduce + CRMS needs.
 */
enum class BuiltinReducer {
    SUM, COUNT, MIN, MAX, AVG, STDDEV,
    PERCENTILE_50, PERCENTILE_95, PERCENTILE_99,
    CONCAT, FIRST, LAST,
    // CRMS-specific
    PAIR_BEFORE_AFTER,  // pairs BEFORE+AFTER at same key
    EIGSORT_BY_DEPTH    // sort by lattice depth desc
}

/**
 * Value algebra interface combining folder and merger.
 */
interface ValueAlg<V, Acc> {
    val folder: Folder<V, Acc>
    val merger: Merger<Acc>
    val initial: Acc
}

/**
 * Accumulator state for multi-metric reduction (Forge).
 */
data class MultiMetricAccumulator(
    val sums: Map<String, Double> = emptyMap(),
    val counts: Map<String, Int> = emptyMap(),
    val mins: Map<String, Double> = emptyMap(),
    val maxs: Map<String, Double> = emptyMap(),
    val concatBuffers: Map<String, StringBuilder> = emptyMap(),
    val firstValues: Map<String, Any?> = emptyMap(),
    val lastValues: Map<String, Any?> = emptyMap(),
    /** Individual values accumulated for SUM — used to compute order-independent sums. */
    val sumEntries: Map<String, List<Double>> = emptyMap(),
    // CRMS-specific
    val beforeAfterPairs: List<Pair<Any?, Any?>> = emptyList(),
    val depthSortedCells: List<ConflictCell> = emptyList()
) {
    fun with(key: String, reducer: BuiltinReducer, value: Any?): MultiMetricAccumulator {
        val numValue = (value as? Number)?.toDouble() ?: 0.0
        return when (reducer) {
            BuiltinReducer.SUM -> {
                // Track individual values and recompute from canonical (sorted) order
                // so summation is independent of input ordering (IEEE-754 associativity fix).
                val entries = (sumEntries[key] ?: emptyList()) + numValue
                val canonicalSum = entries.sorted().reduce { a, b -> a + b }
                copy(sums = sums + (key to canonicalSum), sumEntries = sumEntries + (key to entries))
            }
            BuiltinReducer.COUNT -> copy(counts = counts + (key to (counts[key] ?: 0) + 1))
            BuiltinReducer.MIN -> copy(mins = mins + (key to minOf(mins[key] ?: Double.POSITIVE_INFINITY, numValue)))
            BuiltinReducer.MAX -> copy(maxs = maxs + (key to maxOf(maxs[key] ?: Double.NEGATIVE_INFINITY, numValue)))
            BuiltinReducer.CONCAT -> copy(concatBuffers = concatBuffers + (key to (concatBuffers[key] ?: StringBuilder()).apply { append(value) }))
            BuiltinReducer.FIRST -> if (firstValues.containsKey(key)) this else copy(firstValues = firstValues + (key to value))
            BuiltinReducer.LAST -> copy(lastValues = lastValues + (key to value))
            else -> this
        }
    }
}

/**
 * CRMS ConflictCell for paired BEFORE/AFTER with eigsort.
 * Top-level so [LcncReductionCoreTest] can reference it unqualified.
 */
data class ConflictCell(
    val callsiteHash: Int,
    val beforeEvents: List<TraceEvent> = emptyList(),
    val afterEvents: List<TraceEvent> = emptyList(),
    val depth: Int = 0,
    val frequency: Long = 0,
    val latencyNanos: Long = 0,
    val severity: Double = 0.0
) {
    companion object {
        fun init(): ConflictCell = ConflictCell(callsiteHash = 0)
    }
}

/**
 * Confix tree-builder accumulator. Top-level so [LcncReductionCoreTest] and the
 * ConfixReducers can reference it unqualified. [TreeNode] uses mutable children so a
 * node referenced from `roots` (or a parent) sees children added after it was rooted.
 */
data class TreeBuilderState(
    val stack: List<TreeNode> = emptyList(),
    val roots: List<TreeNode> = emptyList()
) {
    class TreeNode(
        val tag: String,
        val children: MutableList<TreeNode> = mutableListOf(),
        val span: SpanEvent.Span? = null,
        val depth: Int = 0
    )
}

/**
 * Default implementations and factories.
 */
object LcncValueAlg {

    /** Empty accumulator for multi-metric. */
    fun emptyMultiMetricAccumulator(): MultiMetricAccumulator = MultiMetricAccumulator()

    /** Forge: multi-metric reduce with rereduce support. */
    fun forgeMultiMetricReducer(metrics: List<Pair<String, BuiltinReducer>>): Folder<Map<String, Any>, MultiMetricAccumulator> =
        Folder { acc, input ->
            var result = acc
            for ((key, reducer) in metrics) {
                val value = input[key]
                result = result.with(key, reducer, value)
            }
            result
        }

    /** Forge merger: combines partial accumulators. */
    fun forgeMerger(): Merger<MultiMetricAccumulator> = Merger { partials ->
        if (partials.size == 0) return@Merger emptyMultiMetricAccumulator()
        var result = partials[0]
        for (i in 1 until partials.size) {
            result = mergeTwo(result, partials[i])
        }
        result
    }

    private fun mergeTwo(a: MultiMetricAccumulator, b: MultiMetricAccumulator): MultiMetricAccumulator {
        fun <K, V> mergeMaps(mapA: Map<K, V>, mapB: Map<K, V>, merge: (V, V) -> V): Map<K, V> {
            val allKeys = (mapA.keys + mapB.keys).toSet()
            return allKeys.associateWith { k ->
                merge(mapA[k] as V, mapB[k] as V)
            }
        }

        return MultiMetricAccumulator(
            sums = mergeMaps(a.sums, b.sums) { x, y -> x + y },
            counts = mergeMaps(a.counts, b.counts) { x, y -> x + y },
            mins = mergeMaps(a.mins, b.mins) { x, y -> minOf(x, y) },
            maxs = mergeMaps(a.maxs, b.maxs) { x, y -> maxOf(x, y) },
            concatBuffers = mergeMaps(a.concatBuffers, b.concatBuffers) { x, y -> x.append(y) },
            firstValues = a.firstValues + b.firstValues,  // first wins
            lastValues = b.lastValues + a.lastValues,     // last wins
            sumEntries = mergeMaps(a.sumEntries, b.sumEntries) { x, y -> x + y },
            beforeAfterPairs = a.beforeAfterPairs + b.beforeAfterPairs,
            depthSortedCells = (a.depthSortedCells + b.depthSortedCells).sortedByDescending { it.depth }
        )
    }

    /** Confix: tree construction (buildTree) = fold over spans. Depth-based rooting:
     *  a span is a child of the most recent open span with strictly smaller depth; when
     *  the stack empties (depth-0 sibling) the node becomes a root. Open spans remain
     *  in `roots`/`children` via mutable nodes, so the final tree is complete even when
     *  the last span is never "closed" by a successor. */
    fun confixTreeBuilder(): Folder<SpanEvent, TreeBuilderState> = Folder { acc, input ->
        val node = TreeBuilderState.TreeNode(tag = "span", span = input.span, depth = input.depth)
        val newStack = acc.stack.toMutableList()
        val newRoots = acc.roots.toMutableList()
        // pop closed siblings/descendants (depth >= input.depth)
        while (newStack.isNotEmpty() && newStack.last().depth >= input.depth) {
            newStack.removeAt(newStack.lastIndex)
        }
        if (newStack.isEmpty()) {
            newRoots.add(node)
        } else {
            newStack.last().children.add(node)
        }
        newStack.add(node)
        TreeBuilderState(newStack, newRoots)
    }

    /** CRMS: BEFORE/AFTER pairing + eigsort. */
    fun crmsPairAndEigsort(): Folder<TraceEvent, ConflictCell> = Folder { acc, input ->
        val (before, after) = when (input.opcode) {
            0xA5 -> listOf(input) to emptyList<TraceEvent>()   // L_GET = BEFORE
            0xA6 -> emptyList<TraceEvent>() to listOf(input)   // L_SET = AFTER
            0xA7 -> listOf(input) to emptyList<TraceEvent>()   // P_GET = BEFORE
            0xA8 -> emptyList<TraceEvent>() to listOf(input)   // P_SET = AFTER
            else -> emptyList<TraceEvent>() to emptyList<TraceEvent>()
        }
        ConflictCell(
            callsiteHash = input.siteIdx, // placeholder
            beforeEvents = acc.beforeEvents + before,
            afterEvents = acc.afterEvents + after,
            depth = maxOf(acc.depth, input.methodIdx), // placeholder
            frequency = acc.frequency + 1
        )
    }

    /** CRMS merger: combines partial ConflictCells. */
    fun crmsMerger(): Merger<ConflictCell> = Merger { partials ->
        if (partials.size == 0) return@Merger ConflictCell.init()
        var result = partials[0]
        for (i in 1 until partials.size) {
            result = mergeTwo(result, partials[i])
        }
        result
    }

    private fun mergeTwo(a: ConflictCell, b: ConflictCell): ConflictCell {
        require(a.callsiteHash == b.callsiteHash) { "Cannot merge different callsite hashes" }
        return ConflictCell(
            callsiteHash = a.callsiteHash,
            beforeEvents = a.beforeEvents + b.beforeEvents,
            afterEvents = a.afterEvents + b.afterEvents,
            depth = maxOf(a.depth, b.depth),
            frequency = a.frequency + b.frequency,
            latencyNanos = a.latencyNanos + b.latencyNanos,
            severity = maxOf(a.severity, b.severity)
        )
    }
}
