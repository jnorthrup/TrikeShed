@file:OptIn(kotlin.time.ExperimentalTime::class)

package vec.util

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.*
import kotlin.math.max
import kotlin.time.*
import kotlin.time.TimeSource
//
//class FibonacciReporter(
//    private val totalSize: Int? = null,
//    private val itemName: String = DEFAULT_ITEM_NAME,
//) {
//    private var sequencePosition: Int = 0
//    private var remainingSteps: Int = 1
//    private val startTime: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()
//
//    fun report(currentCount: Int): String? {
//        if (--remainingSteps > 0) return null
//
//        remainingSteps = fib(++sequencePosition)
//        val elapsedTime = startTime.elapsedNow()
//        val timePerItem = elapsedTime / max(1, currentCount)
//
//        return buildString {
//            append(createBasicReport(currentCount, elapsedTime))
//            totalSize?.let {
//                append(createEstimatedCompletion(currentCount, timePerItem))
//            }
//        }
//    }
//
//    private fun createBasicReport(currentCount: Int, elapsedTime: Duration): String {
//        val itemsPerSecond = (currentCount.toDouble() / elapsedTime.inWholeSeconds.toDouble()).toFloat()
//        return "logged $currentCount $itemName in $elapsedTime $itemsPerSecond/s "
//    }
//
//    private fun createEstimatedCompletion(currentCount: Int, timePerItem: Duration): String {
//        val remainingItems = totalSize!! - currentCount
//        val estimatedTimeRemaining = timePerItem * remainingItems
//        val estimatedCompletionTime = Clock.System.now()
//            .plus(estimatedTimeRemaining)
//            .toLocalDateTime(TimeZone.currentSystemDefault())
//
//        return "remaining: $estimatedTimeRemaining est $estimatedCompletionTime"
//    }
//
//    companion object {
//        private const val DEFAULT_ITEM_NAME = "rows"
//    }
//}
//
