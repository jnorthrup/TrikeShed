package metrics
import fsm.FlywheelState
import kotlin.time.TimeSource
object FlywheelMetrics {
    private val transitionCounts = mutableMapOf<String, Long>()
    private var lastTransitionMark = TimeSource.Monotonic.markNow()
    fun recordTransition(from: FlywheelState, to: FlywheelState) {
        val duration = lastTransitionMark.elapsedNow()
        lastTransitionMark = TimeSource.Monotonic.markNow()
        val key = "${from::class.simpleName}->${to::class.simpleName}"
        transitionCounts[key] = (transitionCounts[key] ?: 0L) + 1L
    }
}