package borg.trikeshed.lcnc.reduction

import borg.trikeshed.lib.*
import borg.trikeshed.lcnc.reduction.TraceEvent
import borg.trikeshed.lcnc.reduction.SpanEvent

/**
 * Single-value fold (Map → Reduce).
 */
@FunctionalInterface
interface Folder<In, Acc> {
    fun fold(acc: Acc, input: In): Acc
}

/**
 * Partial merge for rereduce / distributed reduction.
 */
@FunctionalInterface
interface Merger<Acc> {
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
    // CRMS-specific
    val beforeAfterPairs: List<Pair<Any?, Any?>> = emptyList(),
    val depthSortedCells: List<ConflictCell> = emptyList()
) {
    fun with(key: String, reducer: BuiltinReducer, value: Any?): MultiMetricAccumulator {
        val numValue = (value as? Number)?.toDouble() ?: 0.0
        return when (reducer) {
            BuiltinReducer.SUM -> copy(sums = sums + (key to (sums[key] ?: 0.0) + numValue))
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
 * Default implementations and factories.
 */
object LcncValueAlg {

    /** Empty accumulator for multi-metric. */
    fun emptyMultiMetricAccumulator(): MultiMetricAccumulator = MultiMetricAccumulator()

    /** Forge: multi-metric reduce with rereduce support. */
    fun forgeMultiMetricReducer(metrics: List<Pair<String, BuiltinReducer>>): Folder<Map<String, Any>, MultiMetricAccumulator> =
        object : Folder<Map<String, Any>, MultiMetricAccumulator> {
            override fun fold(acc: MultiMetricAccumulator, input: Map<String, Any>): MultiMetricAccumulator {
                var result = acc
                for ((key, reducer) in metrics) {
                    val value = input[key]
                    result = result.with(key, reducer, value)
                }
                return result
            }
        }

    /** Forge merger: combines partial accumulators. */
    fun forgeMerger(): Merger<MultiMetricAccumulator> = object : Merger<MultiMetricAccumulator> {
        override fun merge(partials: Series<MultiMetricAccumulator>): MultiMetricAccumulator {
            if (partials.size == 0) return emptyMultiMetricAccumulator()
            var result = partials[0]
            for (i in 1 until partials.size) {
                result = mergeTwo(result, partials[i])
            }
            return result
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
                concatBuffers = mergeMaps(a.concatBuffers, b.concatBuffers) { x, y -> x.append(y).also { } },
                firstValues = a.firstValues + b.firstValues,  // first wins
                lastValues = b.lastValues + a.lastValues,     // last wins
                beforeAfterPairs = a.beforeAfterPairs + b.beforeAfterPairs,
                depthSortedCells = (a.depthSortedCells + b.depthSortedCells).sortedByDescending { it.depth }
            )
        }
    }

    /** Confix: tree construction (buildTree) = fold over spans. */
    data class TreeBuilderState(
        val stack: List<TreeNode> = emptyList(),
        val roots: List<TreeNode> = emptyList()
    ) {
        data class TreeNode(val tag: String, val children: List<TreeNode> = emptyList(), val span: SpanEvent.Span? = null)
    }

    fun confixTreeBuilder(): Folder<SpanEvent, TreeBuilderState> = object : Folder<SpanEvent, TreeBuilderState> {
        override fun fold(acc: TreeBuilderState, input: SpanEvent): TreeBuilderState {
            // Single-pass parent tracking using stack (O(n))
            val node = TreeBuilderState.TreeNode(tag = "span", span = input.span)
            val newStack = acc.stack.toMutableList()
            val newRoots = acc.roots.toMutableList()

            while (newStack.isNotEmpty() && newStack.last().span!!.endInclusive <= input.span.start) {
                val closed = newStack.removeAt(newStack.lastIndex)
                if (newStack.isEmpty()) {
                    newRoots.add(closed)
                } else {
                    val parent = newStack.last()
                    newStack[newStack.lastIndex] = parent.copy(children = parent.children + closed)
                }
            }

            newStack.add(node)
            return TreeBuilderState(newStack, newRoots)
        }
    }

    /** CRMS: BEFORE/AFTER pairing + eigsort. */
    fun crmsPairAndEigsort(): Folder<TraceEvent, ConflictCell> = object : Folder<TraceEvent, ConflictCell> {
        override fun fold(acc: ConflictCell, input: TraceEvent): ConflictCell {
            val (before, after) = when (input.opcode) {
                0xA5 -> Pair(listOf(input), emptyList<TraceEvent>())   // L_GET = BEFORE
                0xA6 -> Pair(emptyList<TraceEvent>(), listOf(input))   // L_SET = AFTER
                0xA7 -> Pair(listOf(input), emptyList<TraceEvent>())   // P_GET = BEFORE
                0xA8 -> Pair(emptyList<TraceEvent>(), listOf(input))   // P_SET = AFTER
                else -> Pair(emptyList<TraceEvent>(), emptyList<TraceEvent>())
            }
            return ConflictCell(
                callsiteHash = input.siteIdx, // placeholder
                beforeEvents = acc.beforeEvents + before,
                afterEvents = acc.afterEvents + after,
                depth = maxOf(acc.depth, input.methodIdx), // placeholder
                frequency = acc.frequency + 1
            )
        }
    }

    /** CRMS merger: combines partial ConflictCells. */
    fun crmsMerger(): Merger<ConflictCell> = object : Merger<ConflictCell> {
        override fun merge(partials: Series<ConflictCell>): ConflictCell {
            if (partials.size == 0) return ConflictCell.init()
            var result = partials[0]
            for (i in 1 until partials.size) {
                result = mergeTwo(result, partials[i])
            }
            return result.sortedByDescending()
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

        private fun ConflictCell.sortedByDescending(): ConflictCell =
            copy()
    }
}