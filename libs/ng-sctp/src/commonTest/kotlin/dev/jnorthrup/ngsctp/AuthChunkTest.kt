package dev.jnorthrup.ngsctp

import kotlin.test.*
import java.nio.ByteBuffer

/**
 * Unit tests for SCTP AUTH chunk (RFC 4895) serialization and parsing
 */
class AuthChunkTest {
    
    @Test
    fun testAuthChunkSerialization() {
        // Create AUTH chunk with HMAC-SHA1 (20-byte HMAC)
        val hmacValue = ByteArray(20) { it.toByte() }
        val chunk = NgChunk_Auth(
            keyId = 1u,
            algorithm = NgChunk_Auth.AuthAlgorithm.HMAC_SHA_1,
            hmac = hmacValue
        )
        
        val serialized = chunk.serialize()
        
        // Verify header
        assertEquals(0x0Fu, serialized[0].toUByte()) // TYPE = AUTH
        assertEquals(0u, serialized[1].toUByte())   // FLAGS = 0
        
        // Verify length (4 header + 2 keyId + 2 algorithm + 20 HMAC = 28, padded to 28)
        val length = (serialized[2].toUByte().toInt() shl 8) or serialized[3].toUByte().toInt()
        assertEquals(28, length)
        
        // Verify key ID
        val keyId = (serialized[4].toInt() shl 8) or serialized[5].toInt()
        assertEquals(1, keyId)
        
        // Verify algorithm (HMAC-SHA-1 = 1)
        val algorithm = (serialized[6].toInt() shl 8) or serialized[7].toInt()
        assertEquals(1, algorithm)
    }
    
    @Test
    fun testAuthChunkSerializationSHA256() {
        // Create AUTH chunk with HMAC-SHA-256 (32-byte HMAC)
        val hmacValue = ByteArray(32) { (it * 2).toByte() }
        val chunk = NgChunk_Auth(
            keyId = 0u,
            algorithm = NgChunk_Auth.AuthAlgorithm.HMAC_SHA_256,
            hmac = hmacValue
        )
        
        val serialized = chunk.serialize()
        
        // Verify length (4 header + 2 keyId + 2 algorithm + 32 HMAC = 40, padded to 40)
        val length = (serialized[2].toUByte().toInt() shl 8) or serialized[3].toUByte().toInt()
        assertEquals(40, length)
        
        // Verify algorithm (HMAC-SHA-256 = 3)
        val algorithm = (serialized[6].toInt() shl 8) or serialized[7].toInt()
        assertEquals(3, algorithm)
    }
    
    @Test
    fun testAuthChunkParsing() {
        // Manually create AUTH chunk bytes
        val hmac = ByteArray(20) { 0xAA.toByte() }
        val totalLength = 4 + 2 + 2 + hmac.size  // 28 bytes
        val buffer = ByteBuffer.allocate(totalLength)
        buffer.put(0x0F)                          // TYPE = AUTH
        buffer.put(0)                             // FLAGS
        buffer.putShort(totalLength.toShort())   // Length
        buffer.putShort(5.toShort())              // Key ID = 5
        buffer.putShort(1.toShort())              // Algorithm = HMAC-SHA-1
        buffer.put(hmac)
        
        buffer.flip()
        
        val parsed = NgChunk_Auth.parse(buffer, totalLength.toUShort())
        
        assertEquals(5u, parsed.keyId)
        assertEquals(NgChunk_Auth.AuthAlgorithm.HMAC_SHA_1, parsed.algorithm)
        assertEquals(20, parsed.hmac.size)
    }
    
    @Test
    fun testAuthChunkRoundTrip() {
        // Original chunk
        val originalHmac = ByteArray(20) { (it + 1).toByte() }
        val original = NgChunk_Auth(
            keyId = 42u,
            algorithm = NgChunk_Auth.AuthAlgorithm.HMAC_SHA_1,
            hmac = originalHmac
        )
        
        // Serialize
        val serialized = original.serialize()
        
        // Parse back
        val buffer = ByteBuffer.wrap(serialized)
        val parsed = NgChunk_Auth.parse(buffer, serialized.size.toUShort())
        
        // Verify
        assertEquals(original.keyId, parsed.keyId)
        assertEquals(original.algorithm, parsed.algorithm)
        assertTrue(original.hmac.contentEquals(parsed.hmac))
    }
    
    @Test
    fun testAuthChunkRoundTripSHA256() {
        // Original chunk with SHA-256
        val originalHmac = ByteArray(32) { (it * 3).toByte() }
        val original = NgChunk_Auth(
            keyId = 100u,
            algorithm = NgChunk_Auth.AuthAlgorithm.HMAC_SHA_256,
            hmac = originalHmac
        )
        
        // Serialize
        val serialized = original.serialize()
        
        // Parse back
        val buffer = ByteBuffer.wrap(serialized)
        val parsed = NgChunk_Auth.parse(buffer, serialized.size.toUShort())
        
        // Verify
        assertEquals(original.keyId, parsed.keyId)
        assertEquals(original.algorithm, parsed.algorithm)
        assertTrue(original.hmac.contentEquals(parsed.hmac))
    }
    
    @Test
    fun testAuthAlgorithmEnum() {
        // Test fromUShort
        assertEquals(NgChunk_Auth.AuthAlgorithm.HMAC_SHA_1, 
            NgChunk_Auth.AuthAlgorithm.fromUShort(1u))
        assertEquals(NgChunk_Auth.AuthAlgorithm.HMAC_SHA_256, 
            NgChunk_Auth.AuthAlgorithm.fromUShort(3u))
        
        // Test unknown algorithm defaults to SHA-1
        assertEquals(NgChunk_Auth.AuthAlgorithm.HMAC_SHA_1, 
            NgChunk_Auth.AuthAlgorithm.fromUShort(99u))
    }
    
    @Test
    fun testAuthChunk4ByteAlignment() {
        // Test with HMAC that would cause non-4-byte alignment
        // HMAC-SHA-256 = 32 bytes, plus 8 bytes header = 40, already aligned
        // Let's use a hypothetical 20-byte + 4 = 24 which is already aligned
        
        val hmac = ByteArray(20)
        val chunk = NgChunk_Auth(
            keyId = 0u,
            algorithm = NgChunk_Auth.AuthAlgorithm.HMAC_SHA_1,
            hmac = hmac
        )
        
        val serialized = chunk.serialize()
        
        // Total should be 4-byte aligned
        assertEquals(0, serialized.size % 4)
    }
    
    @Test
    fun testAuthChunkTypeValue() {
        val chunk = NgChunk_Auth()
        
        // AUTH chunk type is 0x0F (15)
        assertEquals(ChunkType.AUTH, chunk.type)
        assertEquals(0x0Fu, chunk.type.value)
    }
}
