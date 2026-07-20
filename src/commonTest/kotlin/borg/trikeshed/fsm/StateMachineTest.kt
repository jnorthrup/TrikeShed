package borg.trikeshed.fsm

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals

class StateMachineTest {

    sealed interface TestState : State {
        object Idle : TestState
        object Active : TestState
        data class Composite(val fsm: StateMachine<TestState, TestEvent>) : TestState
    }

    sealed interface TestEvent : Event {
        object Start : TestEvent
        object Stop : TestEvent
        data class ChildEvent(val event: TestEvent) : TestEvent
    }

    @Test
    fun testEventSourcedFsm() {
        var enterCount = 0
        var exitCount = 0

        val fsm = StateMachine<TestState, TestEvent>(
            initialState = TestState.Idle,
            transitions = { state, event ->
                when (state) {
                    TestState.Idle -> if (event == TestEvent.Start) TestState.Active else state
                    TestState.Active -> if (event == TestEvent.Stop) TestState.Idle else state
                    is TestState.Composite -> state
                }
            },
            onEnter = { enterCount++ },
            onExit = { exitCount++ }
        )

        val events = listOf<TestEvent>(TestEvent.Start, TestEvent.Stop, TestEvent.Start).toSeries()
        val finalState = fsm.replay(events)

        assertEquals(TestState.Active, finalState)
        assertEquals(3, enterCount) // Idle -> Active, Active -> Idle, Idle -> Active
        assertEquals(3, exitCount)  // Idle -> Active, Active -> Idle, Idle -> Active
    }
    
    @Test
    fun testHierarchicalFsm() {
        val childFsm = StateMachine<TestState, TestEvent>(
            initialState = TestState.Idle,
            transitions = { state, event ->
                when (state) {
                    TestState.Idle -> if (event == TestEvent.Start) TestState.Active else state
                    TestState.Active -> if (event == TestEvent.Stop) TestState.Idle else state
                    is TestState.Composite -> state
                }
            }
        )

        val parentFsm = StateMachine<TestState, TestEvent>(
            initialState = TestState.Composite(childFsm),
            transitions = { state, event ->
                when (state) {
                    is TestState.Composite -> {
                        if (event is TestEvent.ChildEvent) {
                            TestState.Composite(state.fsm.copy(initialState = state.fsm.replay(listOf(event.event).toSeries())))
                        } else {
                            state
                        }
                    }
                    else -> state
                }
            }
        )

        val events = listOf(TestEvent.ChildEvent(TestEvent.Start)).toSeries()
        val finalState = parentFsm.replay(events)

        require(finalState is TestState.Composite)
        assertEquals(TestState.Active, finalState.fsm.initialState)
    }
}
