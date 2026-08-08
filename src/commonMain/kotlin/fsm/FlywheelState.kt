package fsm
import metrics.FlywheelMetrics
sealed class FlywheelState {
    data object Idle : FlywheelState()
    data object Spinning : FlywheelState()
    data object Fault : FlywheelState()
    data object Stopped : FlywheelState()
}
data class StateChangedEvent(val from: FlywheelState, val to: FlywheelState)
object StateMachine {
    var current: FlywheelState = FlywheelState.Idle
        private set
    fun transition(to: FlywheelState) {
        if (current == to) return
        val from = current
        current = to
        FlywheelMetrics.recordTransition(from, to)
    }
}