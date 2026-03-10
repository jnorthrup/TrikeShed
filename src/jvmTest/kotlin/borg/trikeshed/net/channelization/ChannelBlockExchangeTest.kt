package borg.trikeshed.net.channelization

import borg.trikeshed.net.ProtocolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChannelBlockExchangeTest {

    @Test
    fun blockValueEquality() {
        val sessionId = ChannelSessionId("session-1")
        val blockId = ChannelBlockId(1L)
        val seq = BlockSequence(10L)
        val payload = byteArrayOf(1, 2, 3)

        val block1 = ChannelBlock(blockId, sessionId, seq, payload)
        val block2 = ChannelBlock(blockId, sessionId, seq, payload.copyOf())
        val block3 = ChannelBlock(ChannelBlockId(2L), sessionId, seq, payload)
        val block4 = ChannelBlock(blockId, sessionId, seq, byteArrayOf(1, 2, 4))

        assertEquals(block1, block2)
        assertEquals(block1.hashCode(), block2.hashCode())
        assertNotEquals(block1, block3)
        assertNotEquals(block1, block4)
    }

    @Test
    fun blockFlagsSemantics() {
        val flagsNone = BlockFlags.None
        val flagsEos = BlockFlags.EndOfStream
        val flagsAck = BlockFlags.RequireAck
        
        val composite = BlockFlags.Composite(setOf(flagsEos, flagsAck))
        
        assertTrue(flagsNone is BlockFlags)
        assertTrue(flagsEos is BlockFlags)
        assertTrue(flagsAck is BlockFlags)
        assertTrue(composite.flags.contains(BlockFlags.EndOfStream))
        assertTrue(composite.flags.contains(BlockFlags.RequireAck))
    }

    @Test
    fun envelopeProperties() {
        val sessionId = ChannelSessionId("session-envelope")
        val block = ChannelBlock(
            ChannelBlockId(100L),
            sessionId,
            BlockSequence(1L),
            byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        )
        
        val envelope = ChannelEnvelope(
            block = block,
            direction = TransferDirection.Ingress,
            protocol = ProtocolId.HTTP,
            timestamp = 123456789L
        )

        assertEquals(block, envelope.block)
        assertEquals(TransferDirection.Ingress, envelope.direction)
        assertEquals(ProtocolId.HTTP, envelope.protocol)
        assertEquals(123456789L, envelope.timestamp)
    }

    @Test
    fun blockAckCreation() {
        val sessionId = ChannelSessionId("session-ack")
        val blockId = ChannelBlockId(50L)
        val seq = BlockSequence(5L)
        
        val ack = BlockAck(
            sessionId = sessionId,
            blockId = blockId,
            sequence = seq,
            acknowledged = true,
            reason = "OK"
        )

        assertEquals(sessionId, ack.sessionId)
        assertEquals(blockId, ack.blockId)
        assertEquals(seq, ack.sequence)
        assertTrue(ack.acknowledged)
        assertEquals("OK", ack.reason)
    }
}
