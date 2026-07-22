package borg.trikeshed.lcnc.rollup

import borg.trikeshed.lcnc.collections.associative.PropertyValue
import borg.trikeshed.lcnc.reduction.*
import borg.trikeshed.lib.Series
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

class RollupReducer(private val stage: RereduceStage = DefaultRereduceStage()) {

    fun reduce(ctx: RollupContext, spec: RollupSpec): RollupResult {
        val values = mutableListOf<Double>()
        val groupedValues = mutableMapOf<String, MutableList<Double>>()
        val groupTotalPages = mutableMapOf<String, Int>()
        var totalPages = 0

        for (i in 0 until ctx.source.pages.a) {
            val page = ctx.source.pages.b(i)
            totalPages++

            var foundValue: Double? = null
            var foundGroup: String? = null

            if (page.contentBlocks != null) {
                for (j in 0 until page.contentBlocks.a) {
                    val block = page.contentBlocks.b(j)
                    val content = block.content
                    if (content is PropertyValue && content.propertyId == spec.targetPropertyId) {
                        val num = (content.value as? Number)?.toDouble()
                        if (num != null) {
                            foundValue = num
                        }
                    }
                    if (spec.groupByPropertyId != null && content is PropertyValue && content.propertyId == spec.groupByPropertyId) {
                        foundGroup = content.value?.toString()
                    }
                }
            }
            if (foundValue != null) {
                values.add(foundValue)
            }
            if (spec.groupByPropertyId != null) {
                val groupKey = foundGroup ?: "(empty)"
                if (foundValue != null) {
                    groupedValues.getOrPut(groupKey) { mutableListOf() }.add(foundValue)
                }
                groupTotalPages[groupKey] = groupTotalPages.getOrElse(groupKey) { 0 } + 1
            }
        }

        val processedValuesRaw = stage.apply(values, ctx)
        @Suppress("UNCHECKED_CAST")
        val processedValues = (processedValuesRaw as? List<Double>) ?: values

        val groupsResult = if (spec.groupByPropertyId != null) {
            val groups = mutableMapOf<String, RollupResult>()
            for (groupKey in groupTotalPages.keys) {
                val groupVals = groupedValues[groupKey] ?: mutableListOf()
                val totalGroupPages = groupTotalPages[groupKey] ?: 0
                val rawGroupResult: Double? = calculateRawResult(groupVals, spec.function, totalGroupPages)
                val finalGroupResult = formatResult(rawGroupResult, ctx.config.decimalPlaces)
                groups[groupKey] = RollupResult(spec.function, finalGroupResult, totalGroupPages)
            }
            groups
        } else null

        if (processedValues.isEmpty() && totalPages == 0) {
             return RollupResult(spec.function, null, 0, groups = groupsResult)
        }

        val rawResult: Double? = calculateRawResult(processedValues, spec.function, totalPages)

        val finalResult = formatResult(rawResult, ctx.config.decimalPlaces)

        return RollupResult(
            function = spec.function,
            value = finalResult,
            sampleSize = totalPages,
            groups = groupsResult
        )
    }

    private fun calculateRawResult(processedValues: List<Double>, function: RollupFunction, totalPages: Int): Double? {
        return when (function) {
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
                    val p = function.p / 100.0
                    val sorted = processedValues.sorted()
                    val rank = p * sorted.size
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
    }

    private fun formatResult(rawResult: Double?, decimalPlaces: Int): Double? {
        return rawResult?.let {
            val factor = 10.0.pow(decimalPlaces)
            round(it * factor) / factor
        }
    }
}
