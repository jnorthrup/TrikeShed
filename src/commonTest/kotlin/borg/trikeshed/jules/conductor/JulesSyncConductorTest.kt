package borg.trikeshed.jules.conductor

import borg.trikeshed.jules.sync.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class JulesSyncConductorTest {
    
    @Test
    fun testConnectAndDisconnect() = runTest {
        val sentMessages = mutableListOf<SyncMessage>()
        val conductor = JulesSyncConductor("client-1") { msg -> sentMessages.add(msg) }
        
        assertEquals(SyncState.DISCONNECTED, conductor.state.value.status)
        
        conductor.connect()
        assertEquals(SyncState.CONNECTING, conductor.state.value.status)
        
        conductor.markConnected()
        assertEquals(SyncState.CONNECTED, conductor.state.value.status)
        
        conductor.disconnect("User requested")
        assertEquals(SyncState.DISCONNECTED, conductor.state.value.status)
        assertEquals("User requested", conductor.state.value.errorDetails)
    }

    @Test
    fun testEnqueueAndDrain() = runTest {
        val sentMessages = mutableListOf<SyncMessage>()
        val conductor = JulesSyncConductor("client-1") { msg -> sentMessages.add(msg) }
        
        // Enqueue offline
        conductor.enqueuePayload("msg-1", JsonPrimitive("test data"), 1000L)
        assertEquals(1, conductor.state.value.offlineQueue.size)
        assertTrue(sentMessages.isEmpty())
        
        // Connect and drain
        conductor.connect()
        conductor.markConnected()
        conductor.drainQueue()
        
        assertEquals(0, conductor.state.value.offlineQueue.size)
        assertEquals(1, sentMessages.size)
        assertEquals("msg-1", sentMessages[0].id)
        assertEquals(1L, sentMessages[0].sequenceNumber) // local sequence
        assertEquals(1, conductor.state.value.unacknowledgedMessages.size)
    }

    @Test
    fun testAck() = runTest {
        val sentMessages = mutableListOf<SyncMessage>()
        val conductor = JulesSyncConductor("client-1") { msg -> sentMessages.add(msg) }
        
        conductor.connect()
        conductor.markConnected()
        conductor.enqueuePayload("msg-1", JsonPrimitive("test data"), 1000L)
        
        assertEquals(1, conductor.state.value.unacknowledgedMessages.size)
        
        val sentMsg = sentMessages[0]
        conductor.receiveAck(Ack(sentMsg.id, sentMsg.sequenceNumber, "server", 1001L))
        
        assertEquals(0, conductor.state.value.unacknowledgedMessages.size)
    }

    @Test
    fun testNackAndRetry() = runTest {
        val sentMessages = mutableListOf<SyncMessage>()
        val conductor = JulesSyncConductor("client-1") { msg -> sentMessages.add(msg) }
        
        conductor.connect()
        conductor.markConnected()
        conductor.enqueuePayload("msg-1", JsonPrimitive("test data"), 1000L)
        
        assertEquals(1, sentMessages.size)
        val sentMsg = sentMessages[0]
        
        // Nack it
        conductor.receiveNack(Nack(sentMsg.id, sentMsg.sequenceNumber, "server", "conflict", 1001L))
        
        // Drain is called automatically on Nack, so it should be re-sent
        assertEquals(2, sentMessages.size)
        assertEquals("msg-1", sentMessages[1].id)
    }

    @Test
    fun testReceiveRemoteMessageConflict() = runTest {
        val conductor = JulesSyncConductor("client-1") { }
        
        // Add a message locally that is offline (not sent yet)
        conductor.enqueuePayload("msg-1", JsonPrimitive("local data"), 1000L)
        
        // Receive remote message with same ID but newer timestamp
        val remoteMsg = SyncMessage("msg-1", 1L, "client-2", JsonPrimitive("remote data"), 2000L)
        val resolved = conductor.receiveRemoteMessage(remoteMsg, ResolutionStrategy.LAST_WRITER_WINS)
        
        // Remote should win because timestamp 2000 > 1000
        assertNotNull(resolved)
        assertEquals("remote data", (resolved.payload as JsonPrimitive).content)
        
        // Receive remote message with same ID but older timestamp
        val remoteMsgOld = SyncMessage("msg-1", 2L, "client-3", JsonPrimitive("old data"), 500L)
        val resolvedOld = conductor.receiveRemoteMessage(remoteMsgOld, ResolutionStrategy.LAST_WRITER_WINS)
        
        // Local should win, so resolved returns null meaning we ignore remote
        assertNull(resolvedOld)
    }
}
