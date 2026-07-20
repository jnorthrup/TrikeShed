package borg.trikeshed.oroboros

import borg.trikeshed.animation.AnimationTrigger
import borg.trikeshed.fsm.*
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OroborosAnimationTest {

    sealed interface OroborosState : State {
        object Idle : OroborosState
        object Upserting : OroborosState
        object Committing : OroborosState
    }

    sealed interface OroborosEvent : Event {
        object Upsert : OroborosEvent
        object Commit : OroborosEvent
    }

    @Test
    fun testAnimationTriggersOnStateTransition() {
        var triggered = false

        val trigger = AnimationTrigger {
            triggered = true
        }

        val fsm = StateMachine<OroborosState, OroborosEvent>(
            initialState = OroborosState.Idle,
            transitions = { state, event ->
                when (state) {
                    OroborosState.Idle -> if (event == OroborosEvent.Upsert) OroborosState.Upserting else state
                    OroborosState.Upserting -> if (event == OroborosEvent.Commit) OroborosState.Committing else state
                    OroborosState.Committing -> state
                }
            },
            onEnter = { state ->
                if (state == OroborosState.Upserting) {
                    trigger.fire()
                }
            }
        )

        fsm.replay(listOf<OroborosEvent>(OroborosEvent.Upsert).toSeries())

        assertTrue(triggered)
    }
}
