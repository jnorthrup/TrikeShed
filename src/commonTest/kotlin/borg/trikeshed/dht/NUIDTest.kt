@file:OptIn(ExperimentalUnsignedTypes::class)
package borg.trikeshed.dht

import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.id.impl.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NUIDTest {
    @Test
    fun testMinNUIDWidthSelection() {
        assertTrue(NUID.minNUID(7) is ByteNUID)
        assertTrue(NUID.minNUID(8) is UByteNUID)
        assertTrue(NUID.minNUID(15) is ShortNUID)
        assertTrue(NUID.minNUID(16) is UShortNUID)
        assertTrue(NUID.minNUID(31) is IntNUID)
        assertTrue(NUID.minNUID(32) is UIntNUID)
        assertTrue(NUID.minNUID(63) is LongNUID)
        assertTrue(NUID.minNUID(64) is ULongNUID)
        assertTrue(NUID.minNUID(65) is BigIntegerNUID)
    }

    @Test
    fun testAssignOnce() {
        val nuid = NUID.minNUID(8) as UByteNUID
        nuid.assign(42u)
        assertFailsWith<RuntimeException> {
            nuid.assign(43u)
        }
    }

    @Test
    fun testByteSerialization() {
        val nuid = NUID.minNUID(16) as UShortNUID
        nuid.assign(12345u)
        val bytes = nuid.toBytes()
        val nuid2 = NUID.minNUID(16) as UShortNUID
        nuid2.fromBytes(bytes)
        assertEquals(12345u, nuid2.id)
    }

    @Test
    fun testXORDistanceBounds() {
        val nuid = NUID.minNUID(8) as UByteNUID
        assertEquals(0, nuid.netmask.distance(5u, 5u))
        assertEquals(1, nuid.netmask.distance(5u, 4u)) // 0101 XOR 0100 = 0001
        assertTrue(nuid.netmask.distance(0u, 255u) <= 8)
    }
}
