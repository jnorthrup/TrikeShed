package dev.jnorthrup.ngsctp

import com.ngsctp.protocol.SctpCommonHeader
import com.ngsctp.protocol.SctpPacket
import com.ngsctp.protocol.TransportAddress
import java.nio.ByteBuffer
import kotlin.test.*

/**
 * Unit tests for SCTP transport and packet handling
 */
class TransportTest {
    
    @Test
    fun testSctpPacketConstruction() {
        val header = SctpCommonHeader(
            sourcePort = 8080.toUShort(),
            destinationPort = 38412.toUShort(),
            verificationTag = 0x12345678u,
            checksum = 0u
        )
        
        val initChunk = NgChunk_Init(
            initiateTag = 0xDEADBEEFu,
            initialTSN = 0x12345678u,
            numOutboundStreams = 10u,
            numInboundStreams = 10u,
            fixedParameters = listOf(
                SctpParameter.ForwardTSNSupported(true)
            )
        )
        
        val remote = TransportAddress(
            address = "192.168.1.100",
            port = 38412.toUShort()
        )
        
        val packet = SctpPacket(
            header = header,
            chunks = listOf(initChunk),
            remote = remote
        )
        
        assertEquals(1, packet.chunks.size)
        assertEquals(0x12345678u, packet.header.verificationTag)
    }
    
    @Test
    fun testFullPacketSerialization() {
        // Build complete SCTP packet with INIT chunk
        val initChunk = NgChunk_Init(
            initiateTag = 0xDEADBEEFu,
            initialTSN = 0x12345678u,
            numOutboundStreams = 10u,
            numInboundStreams = 10u,
            fixedParameters = listOf(
                SctpParameter.ForwardTSNSupported(true)
            )
        )
        
        val chunkBytes = initChunk.serialize()
        
        // Total packet: 12 byte header + chunk
        val totalSize = SctpCommonHeader.SIZE + chunkBytes.size
        val buffer = ByteBuffer.allocate(totalSize)
        
        // Write header
        val header = SctpCommonHeader(
            sourcePort = 8080.toUShort(),
            destinationPort = 38412.toUShort(),
            verificationTag = 0xDEADBEEFu,
            checksum = 0u
        )
        header.serialize(buffer)
        buffer.put(chunkBytes)
        
        // Flip and verify
        buffer.flip()
        
        // Parse back
        val parsedHeader = SctpCommonHeader.parse(buffer)
        assertEquals(8080.toUShort(), parsedHeader.sourcePort)
        assertEquals(38412.toUShort(), parsedHeader.destinationPort)
        assertEquals(0xDEADBEEFu, parsedHeader.verificationTag)
        
        // Parse chunk
        val parsedChunk = NgChunk.parse(buffer)
        assertNotNull(parsedChunk)
        assertTrue(parsedChunk is NgChunk_Init)
    }
    
    @Test
    fun testMultiChunkPacket() {
        // Build packet with DATA + SACK chunks
        val dataChunk = NgChunk_Data(
            streamId = 1u,
            streamSequenceNumber = 0u,
            payloadProtocolId = 0u,
            transmissionSequenceNumber = 100u,
            userData = ByteBuffer.wrap("Hello".toByteArray())
        )
        
        val sackChunk = NgChunk_Sack(
            cumulativeTSNAck = 99u,
            advertisedReceiverWindowCredit = 65536u
        )
        
        // Serialize both chunks
        val dataBytes = dataChunk.serialize()
        val sackBytes = sackChunk.serialize()
        
        val totalSize = SctpCommonHeader.SIZE + dataBytes.size + sackBytes.size
        val buffer = ByteBuffer.allocate(totalSize)
        
        val header = SctpCommonHeader(
            sourcePort = 8080.toUShort(),
            destinationPort = 38412.toUShort(),
            verificationTag = 0xABCDEF00u,
            checksum = 0u
        )
        header.serialize(buffer)
        buffer.put(dataBytes)
        buffer.put(sackBytes)
        
        buffer.flip()
        
        // Parse chunks
        val parsedData = NgChunk.parse(buffer)
        assertNotNull(parsedData)
        assertTrue(parsedData is NgChunk_Data)
        
        val parsedSack = NgChunk.parse(buffer)
        assertNotNull(parsedSack)
        assertTrue(parsedSack is NgChunk_Sack)
    }
    
    @Test
    fun testParameterSerialization() {
        val forwardTSN = SctpParameter.ForwardTSNSupported(true)
        assertEquals(0, forwardTSN.data.size)
        
        val maxInbound = SctpParameter.NegotiatedMaxInboundStreams(50u)
        assertEquals(2, maxInbound.data.size)
        
        val cookie = SctpParameter.StateCookie("test-cookie".toByteArray())
        assertEquals(12, cookie.data.size)
    }
    
    @Test
    fun testChunkFlags() {
        // Test unordered DATA chunk
        val unorderedData = NgChunk_Data(
            streamId = 1u,
            streamSequenceNumber = 0u,
            payloadProtocolId = 0u,
            transmissionSequenceNumber = 100u,
            userData = ByteBuffer.wrap("test".toByteArray()),
            flags = ChunkFlags(0x04u) // Unordered flag
        )
        
        val serialized = unorderedData.serialize()
        assertEquals(0x04u, serialized[1].toUByte()) // Flags byte
    }
    
    @Test
    fun testHeartbeatChunk() {
        val heartbeatInfo = "192.168.1.1:8080".toByteArray()
        val heartbeat = NgChunk_Heartbeat(heartbeatInfo)
        
        val serialized = heartbeat.serialize()
        
        // Verify type is HEARTBEAT (0x04)
        assertEquals(0x04, serialized[0].toInt())
        
        // Parse back
        val parsed = NgChunk.parse(ByteBuffer.wrap(serialized))
        assertNotNull(parsed)
        assertTrue(parsed is NgChunk_Heartbeat)
    }
    
    @Test
    fun testAbortChunk() {
        val abort = NgChunk_Abort("Connection refused".takeIf { true })
        
        val serialized = abort.serialize()
        assertEquals(0x06, serialized[0].toInt()) // ABORT type
    }
    
    @Test
    fun testErrorChunk() {
        val error = NgChunk_Error(
            errorCode = 0x0001US, // Invalid Stream Identifier
            additionalInfo = "Stream 5 not found".toByteArray()
        )
        
        val serialized = error.serialize()
        assertEquals(0x09, serialized[0].toInt()) // ERROR type
        
        // Parse back
        val parsed = NgChunk.parse(ByteBuffer.wrap(serialized))
        assertNotNull(parsed)
        assertTrue(parsed is NgChunk_Error)
    }
}
