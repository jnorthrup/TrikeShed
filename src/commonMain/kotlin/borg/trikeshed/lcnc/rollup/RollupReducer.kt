package borg.trikeshed.lcnc.rollup

import borg.trikeshed.lcnc.collections.associative.PropertyValue
import borg.trikeshed.reduction.*
import borg.trikeshed.lib.Series
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

class RollupReducer(private val stage: RereduceStage = DefaultRereduceStage()) {

    fun reduce(ctx: RollupContext, spec: RollupSpec): RollupResult {
        // 1. Walk ctx.source.pages
        // 2. For each page, extract the PropertyValue for spec.targetPropertyId
        val values = mutableListOf<Double>()
        var totalPages = 0

        for (i in 0 until ctx.source.pages.a) {
            val page = ctx.source.pages.b(i)
            totalPages++

            var foundValue: Double? = null
            if (page.contentBlocks != null) {
                for (j in 0 until page.contentBlocks.a) {
                    val block = page.contentBlocks.b(j)
                    val content = block.content
                    if (content is PropertyValue && content.propertyId == spec.targetPropertyId) {
                        val num = (content.value as? Number)?.toDouble()
                        if (num != null) {
                            foundValue = num
                            break
                        }
                    }
                }
            }
            if (foundValue != null) {
                values.add(foundValue)
            }
        }

        val processedValuesRaw = stage.apply(values, ctx)
        @Suppress("UNCHECKED_CAST")
        val processedValues = (processedValuesRaw as? List<Double>) ?: values

        // 3. Apply spec.function across all values
        if (processedValues.isEmpty() && totalPages == 0) {
             return RollupResult(spec.function, null, 0)
        }

        val rawResult: Double? = when (spec.function) {
            is RollupFunction.Sum -> if (processedValues.isEmpty()) 0.0 else processedValues.sum()
            is RollupFunction.Count -> totalPages.toDouble()
            is RollupFunction.Min -> if (processedValues.isEmpty()) null else processedValues.minOrNull()
            is RollupFunction.Max -> if (processedValues.isEmpty()) null else processedValues.maxOrNull()
            is RollupFunction.Avg -> if (processedValues.isEmpty()) null else processedValues.sum() / totalPages.toDouble()
            is RollupFunction.Stddev -> {
                if (processedValues.size < 2) null
                else {
                    val mean = processedValues.sum() / processedValues.size
                    val variance = processedValues.sumOf { (it - mean).pow(2) } / (processedValues.size - 1)
                    sqrt(variance)
                }
            }
            is RollupFunction.Percentile -> {
                if (processedValues.isEmpty()) null
                else {
                    val p = spec.function.p / 100.0
                    val sorted = processedValues.sorted()
                    val rank = p * sorted.size
                    // We need to return the exact 95th element for 95th percentile out of 100
                    // which is index 94.
                    // For [1..100], p=0.95 -> we want 95.0.
                    // By nearest rank method, rank = 0.95 * 100 = 95 -> index 94.
                    val index = kotlin.math.ceil(rank).toInt() - 1
                    sorted[maxOf(0, minOf(index, sorted.size - 1))]
                }
            }
            is RollupFunction.CountValues -> processedValues.size.toDouble()
            is RollupFunction.CountUnique -> processedValues.distinct().size.toDouble()
            is RollupFunction.Empty -> if (processedValues.isEmpty()) 1.0 else 0.0
            is RollupFunction.NotEmpty -> if (processedValues.isNotEmpty()) 1.0 else 0.0
            is RollupFunction.Earliest -> if (processedValues.isEmpty()) null else processedValues.minOrNull()
            is RollupFunction.Latest -> if (processedValues.isEmpty()) null else processedValues.maxOrNull()
        }

        // 4. Format with ctx.config.decimalPlaces
        val finalResult = rawResult?.let {
            val factor = 10.0.pow(ctx.config.decimalPlaces)
            round(it * factor) / factor
        }

        return RollupResult(
            function = spec.function,
            value = finalResult,
            sampleSize = totalPages
        )
    }
}
