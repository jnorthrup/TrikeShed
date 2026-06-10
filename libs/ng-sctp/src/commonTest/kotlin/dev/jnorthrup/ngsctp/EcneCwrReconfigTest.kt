package dev.jnorthrup.ngsctp

import kotlin.test.*

/**
 * Tests for ECNE, CWR, and RE-CONFIG chunks
 */
class EcneCwrReconfigTest {
    
    // ECNE Chunk Tests
    @Test
    fun `test ECNE serialization`() {
        val ecne = NgChunk_Ecne(lowestTSN = 1000u)
        val bytes = ecne.serialize()
        
        assertEquals(8, bytes.size)
        assertEquals(ChunkType.ECNE.value.toInt(), bytes[0].toInt())
        assertEquals(0, bytes[1].toInt()) // flags
        assertEquals(8, bytes[2].toInt()) // length low
        assertEquals(0, bytes[3].toInt()) // length high
        
        // TSN value (1000 = 0x3E8)
        assertEquals(0xE8, bytes[4].toInt())
        assertEquals(0x03, bytes[5].toInt())
        assertEquals(0, bytes[6].toInt())
        assertEquals(0, bytes[7].toInt())
    }
    
    @Test
    fun `test ECNE parsing`() {
        val bytes = byteArrayOf(
            0x0C, // type ECNE
            0x00, // flags
            0x08, 0x00, // length
            0xE8, 0x03, 0x00, 0x00 // TSN = 1000
        )
        
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        val ecne = NgChunk_Ecne.parse(buffer, 8UShort)
        
        assertEquals(1000u, ecne.lowestTSN)
    }
    
    @Test
    fun `test ECNE round-trip`() {
        val original = NgChunk_Ecne(lowestTSN = 50000u)
        val serialized = original.serialize()
        
        val buffer = java.nio.ByteBuffer.wrap(serialized)
        val parsed = NgChunk_Ecne.parse(buffer, serialized.size.toUShort())
        
        assertEquals(original.lowestTSN, parsed.lowestTSN)
    }
    
    // CWR Chunk Tests
    @Test
    fun `test CWR serialization`() {
        val cwr = NgChunk_Cwr(lowestTSN = 2000u)
        val bytes = cwr.serialize()
        
        assertEquals(8, bytes.size)
        assertEquals(ChunkType.CWR.value.toInt(), bytes[0].toInt())
        assertEquals(0, bytes[1].toInt()) // flags
        assertEquals(8, bytes[2].toInt()) // length
    }
    
    @Test
    fun `test CWR parsing`() {
        val bytes = byteArrayOf(
            0x0D, // type CWR
            0x00, // flags
            0x08, 0x00, // length
            0xD0, 0x07, 0x00, 0x00 // TSN = 2000
        )
        
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        val cwr = NgChunk_Cwr.parse(buffer, 8UShort)
        
        assertEquals(2000u, cwr.lowestTSN)
    }
    
    @Test
    fun `test CWR round-trip`() {
        val original = NgChunk_Cwr(lowestTSN = 75000u)
        val serialized = original.serialize()
        
        val buffer = java.nio.ByteBuffer.wrap(serialized)
        val parsed = NgChunk_Cwr.parse(buffer, serialized.size.toUShort())
        
        assertEquals(original.lowestTSN, parsed.lowestTSN)
    }
    
    // RE-CONFIG Chunk Tests
    @Test
    fun `test RE-CONFIG add outbound stream serialization`() {
        val request = NgChunk_ReConfig.ReConfigRequest(
            type = NgChunk_ReConfig.ReConfigType.ADD_OUTBOUND,
            streamIds = listOf(5u, 6u, 7u),
            requestSequenceNumber = 100u
        )
        val reconfig = NgChunk_ReConfig(requests = listOf(request))
        val bytes = reconfig.serialize()
        
        // Header: 4 bytes
        // Param: 2 bytes type + 2 bytes length + 4 bytes seq + 3*2 bytes stream IDs = 14 bytes
        assertTrue(bytes.size >= 18)
    }
    
    @Test
    fun `test RE-CONFIG stream reset serialization`() {
        val request = NgChunk_ReConfig.ReConfigRequest(
            type = NgChunk_ReConfig.ReConfigType.STREAM_RESET,
            streamIds = listOf(0u),
            requestSequenceNumber = 1u
        )
        val reconfig = NgChunk_ReConfig(requests = listOf(request))
        val bytes = reconfig.serialize()
        
        // Should have type RE_CONFIG
        assertEquals(ChunkType.RE_CONFIG.value.toInt(), bytes[0].toInt())
    }
    
    @Test
    fun `test RE-CONFIG parsing`() {
        // Build a simple RE-CONFIG chunk with one ADD_OUTBOUND request
        val streamId = 5
        val bytes = byteArrayOf(
            0x82, // type RE_CONFIG
            0x00, // flags
            0x0C, 0x00, // length = 12
            0x01, 0x00, // param type = ADD_OUTBOUND
            0x0C, 0x00, // param length = 12
            0x01, 0x00, 0x00, 0x00, // seq num = 1
            0x05, 0x00 // stream id = 5
        )
        
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        val reconfig = NgChunk_ReConfig.parse(buffer, 12UShort)
        
        assertEquals(1, reconfig.requests.size)
        assertEquals(NgChunk_ReConfig.ReConfigType.ADD_OUTBOUND, reconfig.requests[0].type)
        assertEquals(1u, reconfig.requests[0].requestSequenceNumber)
        assertTrue(reconfig.requests[0].streamIds.contains(5u))
    }
    
    @Test
    fun `test RE-CONFIG round-trip`() {
        val requests = listOf(
            NgChunk_ReConfig.ReConfigRequest(
                type = NgChunk_ReConfig.ReConfigType.ADD_OUTBOUND,
                streamIds = listOf(10u, 11u),
                requestSequenceNumber = 42u
            )
        )
        val original = NgChunk_ReConfig(requests = requests)
        val serialized = original.serialize()
        
        val buffer = java.nio.ByteBuffer.wrap(serialized)
        val parsed = NgChunk_ReConfig.parse(buffer, serialized.size.toUShort())
        
        assertEquals(1, parsed.requests.size)
        assertEquals(original.requests[0].type, parsed.requests[0].type)
        assertEquals(original.requests[0].requestSequenceNumber, parsed.requests[0].requestSequenceNumber)
    }
    
    @Test
    fun `test RE-CONFIG multiple requests`() {
        val requests = listOf(
            NgChunk_ReConfig.ReConfigRequest(
                type = NgChunk_ReConfig.ReConfigType.ADD_OUTBOUND,
                streamIds = listOf(1u),
                requestSequenceNumber = 1u
            ),
            NgChunk_ReConfig.ReConfigRequest(
                type = NgChunk_ReConfig.ReConfigType.ADD_INBOUND,
                streamIds = listOf(2u),
                requestSequenceNumber = 2u
            )
        )
        
        val original = NgChunk_ReConfig(requests = requests)
        val serialized = original.serialize()
        
        val buffer = java.nio.ByteBuffer.wrap(serialized)
        val parsed = NgChunk_ReConfig.parse(buffer, serialized.size.toUShort())
        
        assertEquals(2, parsed.requests.size)
    }
    
    @Test
    fun `test RE-CONFIG reset outgoing parsing`() {
        val bytes = byteArrayOf(
            0x82, // type RE_CONFIG
            0x00, // flags
            0x0C, 0x00, // length = 12
            0x04, 0x00, // param type = RESET_OUTGOING
            0x0C, 0x00, // param length = 12
            0x0A, 0x00, 0x00, 0x00, // seq num = 10
            0x00, 0x00 // all streams
        )
        
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        val reconfig = NgChunk_ReConfig.parse(buffer, 12UShort)
        
        assertEquals(1, reconfig.requests.size)
        assertEquals(NgChunk_ReConfig.ReConfigType.RESET_OUTGOING, reconfig.requests[0].type)
    }
}
