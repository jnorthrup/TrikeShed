package borg.trikeshed.fsm

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.fold

interface State
interface Event

data class StateMachine<S : State, E : Event>(
    val initialState: S,
    val transitions: (S, E) -> S,
    val onEnter: (S) -> Unit = {},
    val onExit: (S) -> Unit = {}
) {
    fun replay(events: Series<E>): S {
        return events.fold(initialState) { currentState, event ->
            val nextState = transitions(currentState, event)
            if (currentState != nextState) {
                onExit(currentState)
                onEnter(nextState)
            }
            nextState
        }
    }
}
