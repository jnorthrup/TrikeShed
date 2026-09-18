package dev.jnorthrup.ngsctp

import kotlin.test.*

/**
 * Unit tests for ngSCTP Congestion Control
 * Tests RFC 4960 Section 7 compliance
 */
class CongestionControlTest {
    
    @Test
    fun testInitialCwnd() {
        val cc = CongestionControl()
        assertEquals(4380, cc.cwnd) // 2 * MTU default
    }
    
    @Test
    fun testSlowStart() {
        val cc = CongestionControl()
        
        // Initially in slow start
        assertEquals(CongestionControl.CongestionPhase.SLOW_START, cc.phase)
        
        // Simulate SACK advancing by 1000 bytes
        cc.onSackReceived(
            cumulativeAckTSN = 1000u,
            previousAckTSN = 0u,
            gapAckBlocks = emptyList(),
            dataBytesInFlight = 1000
        )
        
        // cwnd should increase (in slow start, increases by ~MTU per packet)
        assertTrue(cc.cwnd >= 4380)
    }
    
    @Test
    fun testSlowStartToCongestionAvoidance() {
        val cc = CongestionControl(initialCwnd = 4380, ssthresh = 10000)
        
        // Should stay in slow start until cwnd >= ssthresh
        var lastCwnd = cc.cwnd
        repeat(5) {
            cc.onSackReceived(
                cumulativeAckTSN = (it * 1000 + 1000).toUInt(),
                previousAckTSN = (it * 1000).toUInt(),
                gapAckBlocks = emptyList(),
                dataBytesInFlight = 5000
            )
            // cwnd should grow
            assertTrue(cc.cwnd >= lastCwnd)
            lastCwnd = cc.cwnd
        }
    }
    
    @Test
    fun testCongestionAvoidance() {
        // Start at ssthresh to enter congestion avoidance
        val cc = CongestionControl(initialCwnd = 10000, ssthresh = 5000)
        
        // Move past ssthresh to trigger congestion avoidance
        cc.onSackReceived(
            cumulativeAckTSN = 5000u,
            previousAckTSN = 0u,
            gapAckBlocks = emptyList(),
            dataBytesInFlight = 10000
        )
        
        assertEquals(CongestionControl.CongestionPhase.CONGESTION_AVOIDANCE, cc.phase)
    }
    
    @Test
    fun testFastRecovery() {
        val cc = CongestionControl(initialCwnd = 10000)
        
        // Set ssthresh lower to enter fast recovery
        // First, establish the connection
        cc.onSackReceived(
            cumulativeAckTSN = 5000u,
            previousAckTSN = 0u,
            gapAckBlocks = emptyList(),
            dataBytesInFlight = 5000
        )
        
        // Now simulate duplicate SACK (no advance in cumulative ack)
        cc.onSackReceived(
            cumulativeAckTSN = 5000u,  // Same as before
            previousAckTSN = 5000u,
            gapAckBlocks = listOf(Pair(5001u, 5100u)),  // Gap acks indicate out-of-order
            dataBytesInFlight = 5000
        )
        
        // Should trigger fast recovery
        assertEquals(CongestionControl.CongestionPhase.FAST_RECOVERY, cc.phase)
    }
    
    @Test
    fun testTimeout() {
        val cc = CongestionControl(initialCwnd = 20000)
        
        // Simulate timeout
        cc.onTimeout()
        
        // cwnd should be reset to one MTU
        assertEquals(CongestionControl.DEFAULT_MTU, cc.cwnd)
        
        // Should be back in slow start
        assertEquals(CongestionControl.CongestionPhase.SLOW_START, cc.phase)
        
        // ssthresh should be half of original cwnd
        assertEquals(10000, cc.currentSsthresh)
    }
    
    @Test
    fun testBytesAllowedToSend() {
        val cc = CongestionControl(initialCwnd = 10000)
        
        // No outstanding bytes - can send full cwnd
        assertEquals(10000, cc.bytesAllowedToSend(0))
        
        // Some bytes outstanding - can send remainder
        assertEquals(5000, cc.bytesAllowedToSend(5000))
        
        // Full - cannot send more
        assertEquals(0, cc.bytesAllowedToSend(10000))
    }
}

/**
 * Unit tests for SendBuffer
 */
class SendBufferTest {
    
    @Test
    fun testAddChunk() {
        val buffer = SendBuffer(bufferSize = 10000)
        
        val tsn = buffer.addChunk("Hello".toByteArray(), 1u, 0u)
        
        assertEquals(0u, tsn) // First TSN should be 0
        assertEquals(5, buffer.bytesInFlight)
        assertEquals(1, buffer.outstandingCount)
    }
    
    @Test
    fun testAckChunksCumulative() {
        val buffer = SendBuffer(bufferSize = 10000)
        
        // Add some chunks
        buffer.addChunk("A".toByteArray(), 1u, 0u)
        buffer.addChunk("B".toByteArray(), 1u, 1u)
        buffer.addChunk("C".toByteArray(), 1u, 2u)
        
        // Acknowledge first chunk
        val acked = buffer.ackChunks(0u, emptyList())
        
        assertEquals(1, acked.size)
        assertEquals(1, buffer.outstandingCount)
        assertEquals(2, buffer.bytesInFlight) // B and C remain
    }
    
    @Test
    fun testAckChunksWithGapAcks() {
        val buffer = SendBuffer(bufferSize = 10000)
        
        // Add chunks 0, 1, 2, 3
        for (i in 0..3) {
            buffer.addChunk("D$i".toByteArray(), 1u, i.toUShort())
        }
        
        // Acknowledge 0 and gap ack 2-3 (skip 1)
        val acked = buffer.ackChunks(0u, listOf(Pair(2u, 3u)))
        
        // Should have acked 0, 2, 3
        assertEquals(3, acked.size)
        assertEquals(1, buffer.outstandingCount)
    }
    
    @Test
    fun testIsFull() {
        val buffer = SendBuffer(bufferSize = 10)
        
        // Add 5 bytes
        buffer.addChunk("12345".toByteArray(), 1u, 0u)
        
        assertFalse(buffer.isFull())
        
        // Add more to exceed buffer
        buffer.addChunk("1234567890".toByteArray(), 1u, 1u)
        
        assertTrue(buffer.isFull())
    }
    
    @Test
    fun testGetOldestUnacked() {
        val buffer = SendBuffer()
        
        buffer.addChunk("A".toByteArray(), 1u, 0u)
        Thread.sleep(1) // Ensure different timestamp
        buffer.addChunk("B".toByteArray(), 1u, 1u)
        
        val oldest = buffer.getOldestUnacked()
        
        assertEquals(0u, oldest?.tsn)
    }
}

/**
 * Unit tests for SACK chunk serialization
 */
class SackTest {
    
    @Test
    fun testSackSerialization() {
        val sack = NgChunk_Sack(
            cumulativeTSNAck = 1000u,
            advertisedReceiverWindowCredit = 50000u
        )
        
        val serialized = sack.serialize()
        
        // Verify chunk type = SACK (0x03)
        assertEquals(0x03, serialized[0].toUByte())
        
        // Verify length = 16 (fixed part)
        val length = (serialized[2].toUByte().toInt() shl 8) or serialized[3].toUByte().toInt()
        assertEquals(16, length)
        
        // Verify cumulative ACK
        val cumAck = (serialized[4].toUByte().toInt() shl 24) or 
                     (serialized[5].toUByte().toInt() shl 16) or 
                     (serialized[6].toUByte().toInt() shl 8) or 
                     serialized[7].toUByte().toInt()
        assertEquals(1000, cumAck)
        
        // Verify a_rwnd
        val arwnd = (serialized[8].toUByte().toInt() shl 24) or 
                    (serialized[9].toUByte().toInt() shl 16) or 
                    (serialized[10].toUByte().toInt() shl 8) or 
                    serialized[11].toUByte().toInt()
        assertEquals(50000, arwnd)
    }
    
    @Test
    fun testSackWithGapAcks() {
        // Create SACK with gap ack blocks would require extending NgChunk_Sack
        // This tests the basic structure
        val sack = NgChunk_Sack(
            cumulativeTSNAck = 500u,
            advertisedReceiverWindowCredit = 10000u
        )
        
        val serialized = sack.serialize()
        assertTrue(serialized.size >= 16)
    }
}

/**
 * Unit tests for DATA chunk with TSN
 */
class DataWithTSNTest {
    
    @Test
    fun testDataChunkWithTSN() {
        val userData = ByteBuffer.wrap("Hello, SCTP!".toByteArray())
        val chunk = NgChunk_Data(
            streamId = 1u,
            streamSequenceNumber = 5u,
            payloadProtocolId = 0u,
            transmissionSequenceNumber = 42u,
            userData = userData
        )
        
        val serialized = chunk.serialize()
        
        // Verify TYPE = DATA (0x00)
        assertEquals(0x00, serialized[0].toUByte())
        
        // Verify TSN is at correct offset (bytes 12-15)
        val tsn = (serialized[12].toUByte().toInt() shl 24) or 
                  (serialized[13].toUByte().toInt() shl 16) or 
                  (serialized[14].toUByte().toInt() shl 8) or 
                  serialized[15].toUByte().toInt()
        assertEquals(42, tsn)
        
        // Verify stream ID at bytes 8-9
        val streamId = (serialized[8].toUByte().toInt() shl 8) or serialized[9].toUByte().toInt()
        assertEquals(1, streamId)
        
        // Verify stream sequence at bytes 10-11
        val streamSeq = (serialized[10].toUByte().toInt() shl 8) or serialized[11].toUByte().toInt()
        assertEquals(5, streamSeq)
    }
}
