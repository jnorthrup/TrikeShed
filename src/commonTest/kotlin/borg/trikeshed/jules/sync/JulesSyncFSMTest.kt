package borg.trikeshed.jules.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JulesSyncFSMTest {

    @Test
    fun testTransitions() {
        var state = SyncSessionState()
        assertEquals(SyncState.DISCONNECTED, state.status)

        state = JulesSyncFSM.reduce(SyncEvent.Connect, state)
        assertEquals(SyncState.CONNECTING, state.status)

        state = JulesSyncFSM.reduce(SyncEvent.Connected, state)
        assertEquals(SyncState.CONNECTED, state.status)

        state = JulesSyncFSM.reduce(SyncEvent.Disconnect("timeout"), state)
        assertEquals(SyncState.DISCONNECTED, state.status)
        assertEquals("timeout", state.errorDetails)
    }

    @Test
    fun testEnqueueAndSend() {
        var state = SyncSessionState(status = SyncState.CONNECTED)

        val msg = SyncMessage("1", 0L, "client", JsonPrimitive("a"), 100)
        state = JulesSyncFSM.reduce(SyncEvent.EnqueueMessage(msg), state)

        assertEquals(1L, state.localSequenceNumber)
        assertEquals(1, state.offlineQueue.size)
        assertEquals(1L, state.offlineQueue[0].sequenceNumber)

        state = JulesSyncFSM.reduce(SyncEvent.MessageSent(state.offlineQueue[0]), state)
        assertTrue(state.offlineQueue.isEmpty())
        assertEquals(1, state.unacknowledgedMessages.size)
    }

    @Test
    fun testAckAndNack() {
        var state = SyncSessionState(status = SyncState.CONNECTED)
        val msg = SyncMessage("1", 1L, "client", JsonPrimitive("a"), 100)

        state = state.copy(unacknowledgedMessages = mapOf("1" to msg))

        // Ack
        val ackState = JulesSyncFSM.reduce(SyncEvent.ReceiveAck(Ack("1", 1L, "server", 101L)), state)
        assertTrue(ackState.unacknowledgedMessages.isEmpty())
        assertTrue(ackState.offlineQueue.isEmpty())

        // Nack
        val nackState = JulesSyncFSM.reduce(SyncEvent.ReceiveNack(Nack("1", 1L, "server", "reason", 101L)), state)
        assertTrue(nackState.unacknowledgedMessages.isEmpty())
        assertEquals(1, nackState.offlineQueue.size) // Moved back to offline queue
    }
}
