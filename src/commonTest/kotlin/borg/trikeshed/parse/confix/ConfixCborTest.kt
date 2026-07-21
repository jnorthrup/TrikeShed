package borg.trikeshed.parse.confix

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Serializable
data class CborTestRecord(
    val id: Int,
    val name: String,
    val active: Boolean,
    val tags: List<String>,
    val metadata: Map<String, String>
)

class ConfixCborTest {

    @Test
    fun testIntegersMinimalWidths() {
        val cbor = ConfixCbor
        // Minimal integer encoding tests
        val v0 = 0
        assertContentEquals(byteArrayOf(0x00.toByte()), cbor.encode(v0))
        val v23 = 23
        assertContentEquals(byteArrayOf(0x17.toByte()), cbor.encode(v23))
        val v24 = 24
        assertContentEquals(byteArrayOf(0x18.toByte(), 0x18.toByte()), cbor.encode(v24))
        val v255 = 255
        assertContentEquals(byteArrayOf(0x18.toByte(), 0xFF.toByte()), cbor.encode(v255))
        val v256 = 256
        assertContentEquals(byteArrayOf(0x19.toByte(), 0x01.toByte(), 0x00.toByte()), cbor.encode(v256))
        
        // Decode check
        assertEquals(v0, cbor.decode(cbor.encode(v0)))
        assertEquals(v23, cbor.decode(cbor.encode(v23)))
        assertEquals(v24, cbor.decode(cbor.encode(v24)))
        assertEquals(v255, cbor.decode(cbor.encode(v255)))
        assertEquals(v256, cbor.decode(cbor.encode(v256)))
    }
    
    @Test
    fun testStringAndBooleans() {
        val cbor = ConfixCbor
        assertEquals(true, cbor.decode(cbor.encode(true)))
        assertEquals(false, cbor.decode(cbor.encode(false)))
        val str = "hello"
        assertContentEquals(byteArrayOf(0x65.toByte(), 0x68.toByte(), 0x65.toByte(), 0x6c.toByte(), 0x6c.toByte(), 0x6f.toByte()), cbor.encode(str))
        assertEquals(str, cbor.decode(cbor.encode(str)))
    }
    
    @Test
    fun testRecordIdempotency() {
        val record = CborTestRecord(
            id = 42,
            name = "Alice",
            active = true,
            tags = listOf("a", "b"),
            metadata = mapOf("k1" to "v1", "k2" to "v2")
        )
        val cbor = ConfixCbor
        val encoded1 = cbor.encode(record)
        val decoded1: CborTestRecord = cbor.decode(encoded1)
        assertEquals(record, decoded1)
        
        val encoded2 = cbor.encode(decoded1)
        assertContentEquals(encoded1, encoded2) // Idempotency byte-for-byte
    }

    @Test
    fun testDeterministicMapOrdering() {
        // Map keys must be sorted per RFC 8949 Section 3.9
        @Serializable
        data class MapWrapper(val map: Map<String, Int>)
        
        val m1 = MapWrapper(mapOf("b" to 2, "a" to 1, "c" to 3))
        val m2 = MapWrapper(mapOf("c" to 3, "a" to 1, "b" to 2))
        
        val cbor = ConfixCbor
        val e1 = cbor.encode(m1)
        val e2 = cbor.encode(m2)
        assertContentEquals(e1, e2, "Map keys must be ordered canonically (sorted bytes)")
    }
}
