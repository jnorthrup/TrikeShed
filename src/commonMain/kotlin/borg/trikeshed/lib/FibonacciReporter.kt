package borg.trikeshed.lib

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.jvm.JvmOverloads
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
@JvmOverloads
tailrec fun fib(n: Int, a: Int = 0, b: Int = 1): Int = when (n) {
    0 -> a
    1 -> b
    else -> fib(n - 1, b, a + b)
}
@OptIn(ExperimentalTime::class)
/**
 * this is a logger which reports mod-fibbonacci tick indexes.  the onyl method is report, which returns a string if
 * the tick is a report tick.
 */
class FibonacciReporter(/** if we know the size beforehand we provide estimation */val size: Int? = null, /** what do we report? */val noun: String = "rows") {
    var trigger: Int = 0
    var countdown: Int = 1
    val begin: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()
    var iy = 0
    fun report(): String? =(iy++).run {
        if (--countdown == 0) {
            //without -ea this benchmark only costs a unused variable decrement.
            countdown = fib(++trigger)
            val l = begin.elapsedNow()
            val slice = l / max(1, iy)
            val secondsSinceBegin = l.inWholeSeconds

            "logged $iy $noun in $l ${(iy.toDouble() / (secondsSinceBegin.toDouble())).toFloat()}/s " + (size?.let {
                val ticksLeft = size - iy
                val remaining: Duration = slice * ticksLeft
                "remaining: $remaining est ${
                    Clock.System.now().plus(remaining).toLocalDateTime(TimeZone.currentSystemDefault())
                }"
            } ?: "")
        } else null
    }
}