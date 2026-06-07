package dev.jnorthrup.ngsctp

import kotlin.test.*

/**
 * Unit tests for ngSCTP chunk serialization and parsing
 */
class ChunkTest {
    
    @Test
    fun testDataChunkSerialization() {
        val userData = ByteBuffer.wrap("Hello, SCTP!".toByteArray())
        val chunk = NgChunk_Data(
            streamId = 1u,
            streamSequenceNumber = 0u,
            payloadProtocolId = 0u,
            transmissionSequenceNumber = 100u,
            userData = userData
        )
        
        val serialized = chunk.serialize()
        
        // Verify header
        assertEquals(0x00, serialized[0].toUByte()) // TYPE = DATA
        assertEquals(0u, serialized[1].toUByte())   // FLAGS = 0
        // Length should be 16 (header) + data length
        val length = (serialized[2].toUByte().toInt() shl 8) or serialized[3].toUByte().toInt()
        assertTrue(length >= 16 + 13) // 16 header + "Hello, SCTP!".length
    }
    
    @Test
    fun testInitChunkSerialization() {
        val chunk = NgChunk_Init(
            initiateTag = 0x12345678u,
            initialTSN = 0xABCDEF01u,
            numOutboundStreams = 10u,
            numInboundStreams = 10u,
            fixedParameters = emptyList()
        )
        
        val serialized = chunk.serialize()
        
        // Verify header
        assertEquals(0x01, serialized[0].toUByte()) // TYPE = INIT
        assertEquals(0u, serialized[1].toUByte())  // FLAGS = 0
        
        // Verify initiate tag
        val tag = (serialized[4].toInt() shl 24) or 
                  (serialized[5].toInt() shl 16) or 
                  (serialized[6].toInt() shl 8) or 
                  serialized[7].toInt()
        assertEquals(0x12345678, tag)
    }
    
    @Test
    fun testCookieAckSerialization() {
        val chunk = NgChunk_CookieAck
        val serialized = chunk.serialize()
        
        assertEquals(0x0Bu, serialized[0].toUByte()) // TYPE = COOKIE_ACK
        assertEquals(0u, serialized[1].toUByte())    // FLAGS = 0
        assertEquals(4, serialized[2].toInt())       // Length high byte
        assertEquals(4, serialized[3].toInt())      // Length low byte (4 bytes total)
    }
    
    @Test
    fun testSackChunkSerialization() {
        val chunk = NgChunk_Sack(
            cumulativeTSNAck = 1000u,
            advertisedReceiverWindowCredit = 65536u
        )
        
        val serialized = chunk.serialize()
        
        // Verify header
        assertEquals(0x03, serialized[0].toUByte()) // TYPE = SACK
        assertEquals(16, serialized[2].toInt())     // Length = 16
        
        // Verify cumulative TSN ACK
        val cumAck = (serialized[4].toInt() shl 24) or 
                     (serialized[5].toInt() shl 16) or 
                     (serialized[6].toInt() shl 8) or 
                     serialized[7].toInt()
        assertEquals(1000, cumAck)
    }
    
    @Test
    fun testHeartbeatChunkSerialization() {
        val info = ByteBuffer.wrap("test heartbeat info".toByteArray())
        val chunk = NgChunk_Heartbeat(info = info.array())
        
        val serialized = chunk.serialize()
        
        assertEquals(0x04, serialized[0].toUByte()) // TYPE = HEARTBEAT
        val length = (serialized[2].toUByte().toInt() shl 8) or serialized[3].toUByte().toInt()
        assertEquals(4 + "test heartbeat info".length, length)
    }
    
    @Test
    fun testChunkTypeFromByte() {
        assertEquals(ChunkType.DATA, ChunkType.fromByte(0x00u))
        assertEquals(ChunkType.INIT, ChunkType.fromByte(0x01u))
        assertEquals(ChunkType.INIT_ACK, ChunkType.fromByte(0x02u))
        assertEquals(ChunkType.SACK, ChunkType.fromByte(0x03u))
        assertEquals(ChunkType.HEARTBEAT, ChunkType.fromByte(0x04u))
        assertEquals(ChunkType.COOKIE_ECHO, ChunkType.fromByte(0x0Au))
        assertEquals(ChunkType.COOKIE_ACK, ChunkType.fromByte(0x0Bu))
    }
    
    @Test
    fun testChunkFlags() {
        val flags = ChunkFlags(0x07u)
        
        assertTrue(flags.isEnd)
        assertTrue(flags.isBeginning)
        assertTrue(flags.isUnordered)
        
        val emptyFlags = ChunkFlags.empty()
        assertFalse(emptyFlags.isEnd)
        assertFalse(emptyFlags.isBeginning)
        assertFalse(emptyFlags.isUnordered)
    }
}
