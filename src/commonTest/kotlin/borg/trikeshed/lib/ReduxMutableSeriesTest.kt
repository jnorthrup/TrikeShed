package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals

class ReduxMutableSeriesTest {

    sealed class Action {
        data class Increment(val amount: Int) : Action()
        data class Decrement(val amount: Int) : Action()
        object Reset : Action()
    }

    data class State(val counter: Int)

    class CounterReducer : Reducer<Action, State> {
        override val zero: State = State(0)
        
        override fun combine(acc: State, element: Action): State = when (element) {
            is Action.Increment -> acc.copy(counter = acc.counter + element.amount)
            is Action.Decrement -> acc.copy(counter = acc.counter - element.amount)
            is Action.Reset -> zero
        }
    }

    @Test
    fun testDelayedReification() {
        val journal = ChunkedMutableSeries<Action>()
        val redux = ReduxMutableSeries(journal, CounterReducer(), capture = Action.Increment(0))

        assertEquals(0, redux.state.counter)

        redux.dispatch(Action.Increment(5))
        redux.dispatch(Action.Increment(3))
        
        // State is only evaluated upon access.
        assertEquals(8, redux.state.counter)

        redux.dispatch(Action.Decrement(2))
        assertEquals(6, redux.state.counter)

        redux.dispatch(Action.Reset)
        redux.dispatch(Action.Increment(10))
        assertEquals(10, redux.state.counter)
    }

    @Test
    fun testSequence() {
        val series = listOf(1, 2, 3).toSeries()
        val seq = series.toSequence()
        assertEquals(listOf(1, 2, 3), seq.toList())
    }
}
