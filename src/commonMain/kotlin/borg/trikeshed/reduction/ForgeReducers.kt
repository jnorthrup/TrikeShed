package borg.trikeshed.reduction

/**
 * Forge-specific Folder/Merger implementations extracted from ForgeWorkspaceImpl.
 * These are the concrete reducers that Forge cascades use.
 */
object ForgeReducers {

    private inline fun folder(
        crossinline fold: (MultiMetricAccumulator, Map<String, Any>) -> MultiMetricAccumulator,
    ): Folder<Map<String, Any>, MultiMetricAccumulator> =
        Folder { acc, input -> fold(acc, input) }

    private fun numeric(input: Map<String, Any>, column: String): Double? =
        (input[column] as? Number)?.toDouble()

    /** Sum reducer for numeric columns. */
    fun sumReducer(column: String): Folder<Map<String, Any>, MultiMetricAccumulator> =
        folder { acc, input -> acc.with(column, BuiltinReducer.SUM, numeric(input, column) ?: 0.0) }

    /** Count reducer. */
    fun countReducer(column: String): Folder<Map<String, Any>, MultiMetricAccumulator> =
        folder { acc, input -> if (input.containsKey(column)) acc.with(column, BuiltinReducer.COUNT, 1) else acc }

    /** Min reducer. */
    fun minReducer(column: String): Folder<Map<String, Any>, MultiMetricAccumulator> =
        folder { acc, input -> acc.with(column, BuiltinReducer.MIN, numeric(input, column)) }

    /** Max reducer. */
    fun maxReducer(column: String): Folder<Map<String, Any>, MultiMetricAccumulator> =
        folder { acc, input -> acc.with(column, BuiltinReducer.MAX, numeric(input, column)) }

    /** Avg reducer (requires sum + count). */
    fun avgReducer(column: String): Pair<Folder<Map<String, Any>, MultiMetricAccumulator>, (MultiMetricAccumulator) -> Double> {
        val folder = folder { acc, input ->
            acc.with(column, BuiltinReducer.SUM, numeric(input, column) ?: 0.0)
                .with(column, BuiltinReducer.COUNT, 1)
        }
        val extractor: (MultiMetricAccumulator) -> Double = { acc ->
            val sum = acc.sums[column] ?: 0.0
            val count = acc.counts[column] ?: 0
            if (count == 0) 0.0 else sum / count
        }
        return folder to extractor
    }

    /** StdDev reducer (Welford's online algorithm). */
    data class StdDevState(val count: Long = 0, val mean: Double = 0.0, val m2: Double = 0.0)

    fun stdDevReducer(column: String): Folder<Map<String, Any>, MultiMetricAccumulator> =
        folder { acc, input ->
            acc.with(column, BuiltinReducer.SUM, numeric(input, column) ?: 0.0)
                .with(column, BuiltinReducer.COUNT, 1)
        }

    /** Percentile reducer (requires storing all values or using t-digest). */
    fun percentileReducer(column: String, percentile: Double): Folder<Map<String, Any>, MultiMetricAccumulator> =
        folder { acc, input -> acc.with(column, BuiltinReducer.CONCAT, numeric(input, column)?.toString()) }

    /** Concat reducer. */
    fun concatReducer(column: String): Folder<Map<String, Any>, MultiMetricAccumulator> =
        folder { acc, input -> acc.with(column, BuiltinReducer.CONCAT, input[column]?.toString()) }

    /** First value reducer. */
    fun firstReducer(column: String): Folder<Map<String, Any>, MultiMetricAccumulator> =
        folder { acc, input -> acc.with(column, BuiltinReducer.FIRST, input[column]) }

    /** Last value reducer. */
    fun lastReducer(column: String): Folder<Map<String, Any>, MultiMetricAccumulator> =
        folder { acc, input -> acc.with(column, BuiltinReducer.LAST, input[column]) }

    /** Build folder from metric specifications. */
    fun buildMultiMetricFolder(metrics: List<Pair<String, BuiltinReducer>>): Folder<Map<String, Any>, MultiMetricAccumulator> =
        LcncValueAlg.forgeMultiMetricReducer(metrics)

    /** Forge merger for rereduce stage. */
    val forgeMerger: Merger<MultiMetricAccumulator> = LcncValueAlg.forgeMerger()

    /** Empty accumulator. */
    val emptyAccumulator: MultiMetricAccumulator = LcncValueAlg.emptyMultiMetricAccumulator()
}