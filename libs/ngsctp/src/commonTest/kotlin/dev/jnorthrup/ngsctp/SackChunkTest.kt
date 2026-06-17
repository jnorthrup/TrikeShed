package dev.jnorthrup.ngsctp

import kotlin.test.*
import java.nio.ByteBuffer

/**
 * Unit tests for SACK chunk serialization with gap ack blocks
 * Tests RFC 4960 Section 3.3.4 compliance
 */
class SackChunkTest {
    
    @Test
    fun testSackSerializationBasic() {
        val sack = NgChunk.Sack(
            cumulativeTSNAck = 1000u,
            advertisedReceiverWindowCredit = 65536u
        )
        
        val serialized = sack.serialize()
        
        // Verify header
        assertEquals(ChunkType.SACK.value, serialized[0].toUByte())
        assertEquals(0u, serialized[1].toUByte()) // flags
        assertEquals(16, serialized[2].toUByte().toInt() and 0xFF) // length (high byte)
        assertEquals(4, serialized[3].toUByte().toInt() and 0xFF)  // length (low byte)
        
        // Verify cumulative ACK
        val buffer = ByteBuffer.wrap(serialized)
        buffer.position(4)
        assertEquals(1000, buffer.getInt())
        assertEquals(65536, buffer.getInt())
        
        // Verify no gap ack blocks
        assertEquals(0, buffer.getShort().toUShort().toInt())
        assertEquals(0, buffer.getShort().toUShort().toInt())
    }
    
    @Test
    fun testSackSerializationWithGapAckBlocks() {
        val sack = NgChunk.Sack(
            cumulativeTSNAck = 1000u,
            advertisedReceiverWindowCredit = 65536u,
            gapAckBlocks = listOf(
                Pair(5u, 10u),   // Gap block 1: TSNs 1005-1010
                Pair(20u, 25u)   // Gap block 2: TSNs 1020-1025
            ),
            duplicateTSNs = listOf(999u, 1001u)
        )
        
        val serialized = sack.serialize()
        
        // Length should be: 12 (header) + 8 (2 gap blocks) + 8 (2 dup TSNs) = 28
        assertEquals(28, serialized.size)
        
        val buffer = ByteBuffer.wrap(serialized)
        buffer.position(4)
        assertEquals(1000, buffer.getInt())
        assertEquals(65536, buffer.getInt())
        
        // Gap ack blocks
        assertEquals(2, buffer.getShort().toUShort().toInt()) // num gap blocks
        assertEquals(2, buffer.getShort().toUShort().toInt()) // num duplicate TSNs
        
        // Gap block 1
        assertEquals(5, buffer.getShort().toUShort().toInt())
        assertEquals(10, buffer.getShort().toUShort().toInt())
        
        // Gap block 2
        assertEquals(20, buffer.getShort().toUShort().toInt())
        assertEquals(25, buffer.getShort().toUShort().toInt())
        
        // Duplicate TSNs
        assertEquals(999, buffer.getInt())
        assertEquals(1001, buffer.getInt())
    }
    
    @Test
    fun testSackRoundTrip() {
        val original = NgChunk.Sack(
            cumulativeTSNAck = 5000u,
            advertisedReceiverWindowCredit = 131072u,
            gapAckBlocks = listOf(Pair(1u, 5u), Pair(15u, 20u)),
            duplicateTSNs = listOf(4999u)
        )
        
        val serialized = original.serialize()
        val buffer = ByteBuffer.wrap(serialized)
        
        val parsed = NgChunk.parse(buffer) as? NgChunk.Sack
        
        assertNotNull(parsed)
        assertEquals(original.cumulativeTSNAck, parsed.cumulativeTSNAck)
        assertEquals(original.advertisedReceiverWindowCredit, parsed.advertisedReceiverWindowCredit)
        assertEquals(original.gapAckBlocks.size, parsed.gapAckBlocks.size)
        assertEquals(original.gapAckBlocks[0], parsed.gapAckBlocks[0])
        assertEquals(original.gapAckBlocks[1], parsed.gapAckBlocks[1])
        assertEquals(original.duplicateTSNs, parsed.duplicateTSNs)
    }
    
    @Test
    fun testSackGapAckBlockCalculation() {
        // Simulate real-world gap ack scenario
        // Cumulative ACK = 1000, received TSNs = 1001-1005, 1010-1015
        // Gap 1: 1-5 (1001-1005)
        // Gap 2: 10-15 (1010-1015)
        val sack = NgChunk.Sack(
            cumulativeTSNAck = 1000u,
            advertisedReceiverWindowCredit = 65536u,
            gapAckBlocks = listOf(
                Pair(1u, 5u),   // TSNs 1001-1005
                Pair(10u, 15u)  // TSNs 1010-1015
            )
        )
        
        val serialized = sack.serialize()
        
        // Verify gap ack blocks are properly serialized
        val buffer = ByteBuffer.wrap(serialized)
        buffer.position(8) // Skip to gap ack section
        assertEquals(2, buffer.getShort().toUShort().toInt())
        
        val gap1Start = buffer.getShort().toUShort()
        val gap1End = buffer.getShort().toUShort()
        assertEquals(1u, gap1Start)
        assertEquals(5u, gap1End)
        
        val gap2Start = buffer.getShort().toUShort()
        val gap2End = buffer.getShort().toUShort()
        assertEquals(10u, gap2Start)
        assertEquals(15u, gap2End)
    }
}
