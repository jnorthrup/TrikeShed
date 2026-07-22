package borg.trikeshed.jules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JulesSessionCardTest {
    @Test
    fun awaitingSnapshotIsCausallyBlocked() {
        val card = JulesSessionCard.capture(snapshot(state = "AWAITING_USER_FEEDBACK"))

        assertEquals(JulesLane.CAUSAL_BLOCKED, card.lane)
        assertEquals("Causal Blocked", card.card.columnId.value)
        assertIs<JulesCause.StateObserved>(card.causes.single())
    }

    @Test
    fun completedPatchIsReadyUntilDrainIsRecorded() {
        val ready = JulesSessionCard.capture(snapshot(state = "COMPLETED", patchBytes = 128))
        assertEquals(JulesLane.CAUSAL_READY, ready.lane)

        val drained = ready.markDrained(commitSha = "abc123", rejects = 0, at = 200)
        assertEquals(JulesLane.DONE, drained.lane)
        assertTrue(drained.drained)
        val cause = assertIs<JulesCause.DrainApplied>(drained.causes.last())
        assertEquals("abc123", cause.commitSha)
        assertEquals(0, cause.rejects)
    }

    @Test
    fun transitionAppendsItsConcreteCauseAndMovesTheCard() {
        val working = JulesSessionCard.capture(snapshot(state = "IN_PROGRESS"))
        val blocked = snapshot(state = "AWAITING_USER_FEEDBACK", capturedAt = 150)
        val cause = JulesCause.AgentMessaged("Which codec?", at = 150, activityId = "a7", activitySeq = 7)

        val transitioned = working.transition(blocked, cause)

        assertEquals(JulesLane.CAUSAL_BLOCKED, transitioned.lane)
        assertEquals(cause, transitioned.causes.last())
        assertEquals("AWAITING_USER_FEEDBACK", transitioned.card.metadata["julesState"])
    }

    private fun snapshot(
        state: String,
        patchBytes: Long = 0,
        capturedAt: Long = 100,
    ) = JulesSnapshot(
        sessionId = "session-1",
        state = state,
        title = "test session",
        patchBytes = patchBytes,
        headSha = "deadbeef",
        activeCount = 1,
        awaitingCount = if (state == "AWAITING_USER_FEEDBACK") 1 else 0,
        capturedAt = capturedAt,
    )
}
