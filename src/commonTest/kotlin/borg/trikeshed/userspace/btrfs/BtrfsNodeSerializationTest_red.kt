package borg.trikeshed.userspace.btrfs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TDD RED: BtrfsNodeSerialization tests.
 *
 * Tests the btrfs node serialization/deserialization layer.
 * LE byte order throughout (btrfs is little-endian on disk).
 */
class BtrfsNodeSerializationTest_red {

    // -------------------------------------------------------------------------
    // crc32c test vectors
    // -------------------------------------------------------------------------

    @Test
    fun crc32c_deadbeef_returnsKnownValue() {
        // 0xDEADBEEF as 4-byte LE input → expected output 0xD87BDE7F
        val input = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0xAD.toByte(), 0xDE.toByte())
        val result = crc32c(input, offset = 0, length = 4)
        assertEquals(0xD87BDE7Fu, result)
    }

    @Test
    fun crc32c_emptyBuffer_returnsZero() {
        val empty = byteArrayOf()
        val result = crc32c(empty, offset = 0, length = 0)
        assertEquals(0u, result)
    }

    @Test
    fun crc32c_subBuffer_respectsOffsetAndLength() {
        // Input: [0x00, 0xDE, 0xAD, 0xBE, 0xEF, 0x00]
        // crc32c of the 4-byte slice [0xDE, 0xAD, 0xBE, 0xEF] in LE is 0xD87BDE7F
        val data = byteArrayOf(
            0x00.toByte(),
            0xEF.toByte(), 0xBE.toByte(), 0xAD.toByte(), 0xDE.toByte(),
            0x00.toByte()
        )
        val result = crc32c(data, offset = 1, length = 4)
        assertEquals(0xD87BDE7Fu, result)
    }

    @Test
    fun crc32c_allZeros_returnsConsistentValue() {
        val zeros = ByteArray(16)
        val r1 = crc32c(zeros, 0, 16)
        val r2 = crc32c(zeros, 0, 16)
        assertEquals(r1, r2, "crc32c of zeros must be deterministic")
        assertTrue(r1 != 0u, "crc32c of zeros should not be zero")
    }

    // -------------------------------------------------------------------------
    // BtrfsKey encode/decode roundtrip
    // -------------------------------------------------------------------------

    @Test
    fun keyRoundTrip_singleKey() {
        val key = BtrfsKey(0x1_0000_0000uL, 1u, 0x1000uL)
        val buf = ByteArray(32)
        encodeKey(key, buf, 6)
        val decoded = decodeKey(buf, 6)
        assertEquals(key.objectId, decoded.objectId)
        assertEquals(key.type, decoded.type)
        assertEquals(key.offset, decoded.offset)
    }

    @Test
    fun keyRoundTrip_zeroKey() {
        val key = BtrfsKey(0uL, 0u, 0uL)
        val buf = ByteArray(32)
        encodeKey(key, buf, 0)
        val decoded = decodeKey(buf, 0)
        assertEquals(0uL, decoded.objectId)
        assertEquals(0u, decoded.type)
        assertEquals(0uL, decoded.offset)
    }

    @Test
    fun keyRoundTrip_maxValues() {
        val key = BtrfsKey(ULong.MAX_VALUE, UInt.MAX_VALUE, ULong.MAX_VALUE)
        val buf = ByteArray(32)
        encodeKey(key, buf, 0)
        val decoded = decodeKey(buf, 0)
        assertEquals(ULong.MAX_VALUE, decoded.objectId)
        assertEquals(UInt.MAX_VALUE, decoded.type)
        assertEquals(ULong.MAX_VALUE, decoded.offset)
    }

    @Test
    fun keyEncode_leByteOrder() {
        // BtrfsKey(0x1_0000_0000, 1, 0x1000)
        // objectId at offset+0: 0x00 0x00 0x00 0x00 0x01 0x00 0x00 0x00
        // type at offset+8: 0x01 0x00 0x00 0x00
        // offset at offset+12: 0x00 0x10 0x00 0x00 0x00 0x00 0x00 0x00
        val key = BtrfsKey(0x1_0000_0000uL, 1u, 0x1000uL)
        val buf = ByteArray(32)
        encodeKey(key, buf, 0)
        assertEquals(0x00, buf[0].toInt() and 0xFF)
        assertEquals(0x00, buf[1].toInt() and 0xFF)
        assertEquals(0x00, buf[2].toInt() and 0xFF)
        assertEquals(0x00, buf[3].toInt() and 0xFF)
        assertEquals(0x01, buf[4].toInt() and 0xFF)
        assertEquals(0x01, buf[8].toInt() and 0xFF)  // type = 1
        assertEquals(0x00, buf[12].toInt() and 0xFF)  // offset LSB = 0
        assertEquals(0x10, buf[15].toInt() and 0xFF)  // offset byte 3 = 0x10
    }

    // -------------------------------------------------------------------------
    // Leaf encode→decode roundtrip
    // -------------------------------------------------------------------------

    @Test
    fun leafEncodeDecode_emptyLeaf() {
        val leaf = BtrfsLeaf(emptyList())
        val buf = ByteArray(4096)
        encodeLeaf(leaf, buf)
        val decoded = decodeLeaf(buf)
        assertTrue(decoded.items.isEmpty())
    }

    @Test
    fun leafEncodeDecode_singleItem() {
        val key = BtrfsKey(1uL, 2u, 3uL)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val item = BtrfsItem(key, 0u, data.size.toUInt(), data)
        val leaf = BtrfsLeaf(listOf(item))
        val buf = ByteArray(4096)
        encodeLeaf(leaf, buf)
        val decoded = decodeLeaf(buf)
        assertEquals(1, decoded.items.size)
        assertEquals(key.objectId, decoded.items[0].key.objectId)
        assertEquals(key.type, decoded.items[0].key.type)
        assertEquals(key.offset, decoded.items[0].key.offset)
        assertTrue(decoded.items[0].data.contentEquals(data))
    }

    @Test
    fun leafEncodeDecode_multipleItems() {
        val items = listOf(
            BtrfsItem(BtrfsKey(1uL, 1u, 0uL), 0u, 2u, byteArrayOf(1, 2)),
            BtrfsItem(BtrfsKey(2uL, 1u, 0uL), 0u, 3u, byteArrayOf(3, 4, 5)),
            BtrfsItem(BtrfsKey(3uL, 1u, 0uL), 0u, 1u, byteArrayOf(6))
        )
        val leaf = BtrfsLeaf(items)
        val buf = ByteArray(4096)
        encodeLeaf(leaf, buf)
        val decoded = decodeLeaf(buf)
        assertEquals(3, decoded.items.size)
        assertEquals(1uL, decoded.items[0].key.objectId)
        assertEquals(2uL, decoded.items[1].key.objectId)
        assertEquals(3uL, decoded.items[2].key.objectId)
    }

    @Test
    fun leafEncodeDecode_preservesDataIntegrity() {
        val bigData = ByteArray(256) { (it * 7 and 0xFF).toByte() }
        val item = BtrfsItem(BtrfsKey(0x100uL, 1u, 0uL), 0u, bigData.size.toUInt(), bigData)
        val leaf = BtrfsLeaf(listOf(item))
        val buf = ByteArray(4096)
        encodeLeaf(leaf, buf)
        val decoded = decodeLeaf(buf)
        assertTrue(decoded.items[0].data.contentEquals(bigData))
    }

    @Test
    fun leafEncodeDecode_itemsSortedByKey() {
        // Items in non-sorted order
        val items = listOf(
            BtrfsItem(BtrfsKey(3uL, 1u, 0uL), 0u, 1u, byteArrayOf(3)),
            BtrfsItem(BtrfsKey(1uL, 1u, 0uL), 0u, 1u, byteArrayOf(1)),
            BtrfsItem(BtrfsKey(2uL, 1u, 0uL), 0u, 1u, byteArrayOf(2))
        )
        val leaf = BtrfsLeaf(items)
        val buf = ByteArray(4096)
        encodeLeaf(leaf, buf)
        val decoded = decodeLeaf(buf)
        // After roundtrip, items should be in sorted order by key
        assertEquals(1uL, decoded.items[0].key.objectId)
        assertEquals(2uL, decoded.items[1].key.objectId)
        assertEquals(3uL, decoded.items[2].key.objectId)
    }

    // -------------------------------------------------------------------------
    // Internal node encode→decode roundtrip
    // -------------------------------------------------------------------------

    @Test
    fun internalEncodeDecode_singleChild() {
        val childKey = BtrfsKey(1uL, 1u, 0uL)
        val child = BtrfsChildPointer(childKey, 0x1000uL)
        val internal = BtrfsInternal(level = 1u, children = listOf(child))
        val buf = ByteArray(4096)
        encodeInternal(internal, buf)
        val decoded = decodeInternal(buf)
        assertEquals(1u, decoded.level)
        assertEquals(1, decoded.children.size)
        assertEquals(childKey.objectId, decoded.children[0].key.objectId)
        assertEquals(0x1000uL, decoded.children[0].blockPtr)
    }

    @Test
    fun internalEncodeDecode_multipleChildren() {
        val children = listOf(
            BtrfsChildPointer(BtrfsKey(1uL, 1u, 0uL), 0x1000uL),
            BtrfsChildPointer(BtrfsKey(2uL, 1u, 0uL), 0x2000uL),
            BtrfsChildPointer(BtrfsKey(3uL, 1u, 0uL), 0x3000uL)
        )
        val internal = BtrfsInternal(level = 1u, children = children)
        val buf = ByteArray(4096)
        encodeInternal(internal, buf)
        val decoded = decodeInternal(buf)
        assertEquals(3, decoded.children.size)
        assertEquals(1uL, decoded.children[0].key.objectId)
        assertEquals(2uL, decoded.children[1].key.objectId)
        assertEquals(3uL, decoded.children[2].key.objectId)
    }

    @Test
    fun internalEncodeDecode_levelZeroIsLeaf() {
        val child = BtrfsChildPointer(BtrfsKey(1uL, 1u, 0uL), 0x1000uL)
        val internal = BtrfsInternal(level = 0u, children = listOf(child))
        val buf = ByteArray(4096)
        encodeInternal(internal, buf)
        val decoded = decodeInternal(buf)
        assertEquals(0u, decoded.level)
    }

    // -------------------------------------------------------------------------
    // Magic validation throws
    // -------------------------------------------------------------------------

    @Test
    fun decodeLeaf_throwsOnWrongMagic() {
        val buf = ByteArray(4096)
        buf[0] = 0x00; buf[1] = 0x00; buf[2] = 0x00; buf[3] = 0x00  // invalid magic = 0
        assertFailsWith<IllegalStateException> { decodeLeaf(buf) }
    }

    @Test
    fun decodeInternal_throwsOnWrongMagic() {
        val buf = ByteArray(4096)
        buf[0] = 0x00; buf[1] = 0x00; buf[2] = 0x00; buf[3] = 0x00  // invalid magic = 0
        assertFailsWith<IllegalStateException> { decodeInternal(buf) }
    }

    @Test
    fun decodeLeaf_throwsOnTruncatedBuffer() {
        val buf = ByteArray(10)  // too short for header (24 bytes)
        assertFailsWith<IllegalStateException> { decodeLeaf(buf) }
    }

    @Test
    fun decodeInternal_throwsOnTruncatedBuffer() {
        val buf = ByteArray(10)  // too short for header (24 bytes)
        assertFailsWith<IllegalStateException> { decodeInternal(buf) }
    }

    // -------------------------------------------------------------------------
    // Checksum validation throws on corruption
    // -------------------------------------------------------------------------

    @Test
    fun decodeLeaf_throwsOnChecksumMismatch() {
        val item = BtrfsItem(BtrfsKey(1uL, 1u, 0uL), 0u, 2u, byteArrayOf(1, 2))
        val leaf = BtrfsLeaf(listOf(item))
        val buf = ByteArray(4096)
        encodeLeaf(leaf, buf)
        // Corrupt a byte in the body (after the 24-byte header)
        buf[25] = (buf[25].toInt() xor 0xFF).toByte()
        assertFailsWith<IllegalStateException> { decodeLeaf(buf) }
    }

    @Test
    fun decodeInternal_throwsOnChecksumMismatch() {
        val child = BtrfsChildPointer(BtrfsKey(1uL, 1u, 0uL), 0x1000uL)
        val internal = BtrfsInternal(level = 1u, children = listOf(child))
        val buf = ByteArray(4096)
        encodeInternal(internal, buf)
        // Corrupt a byte in the body
        buf[30] = (buf[30].toInt() xor 0xFF).toByte()
        assertFailsWith<IllegalStateException> { decodeInternal(buf) }
    }

    @Test
    fun encodeLeaf_thenDecodeLeaf_doesNotThrow() {
        val item = BtrfsItem(BtrfsKey(0xFFuL, 1u, 0xFFuL), 0u, 1u, byteArrayOf(0xAB.toByte()))
        val leaf = BtrfsLeaf(listOf(item))
        val buf = ByteArray(4096)
        encodeLeaf(leaf, buf)
        val decoded = decodeLeaf(buf)  // should NOT throw
        assertEquals(1, decoded.items.size)
    }

    // -------------------------------------------------------------------------
    // Sorted invariant
    // -------------------------------------------------------------------------

    @Test
    fun leafItems_areSortedByKeyAfterDecode() {
        val items = listOf(
            BtrfsItem(BtrfsKey(0xAuL, 1u, 5uL), 0u, 1u, byteArrayOf(1)),
            BtrfsItem(BtrfsKey(0xBuL, 2u, 3uL), 0u, 1u, byteArrayOf(2)),
            BtrfsItem(BtrfsKey(0xCuL, 1u, 1uL), 0u, 1u, byteArrayOf(3))
        )
        val leaf = BtrfsLeaf(items)
        val buf = ByteArray(4096)
        encodeLeaf(leaf, buf)
        val decoded = decodeLeaf(buf)
        // Verify sorted by (objectId, type, offset)
        for (i in 1 until decoded.items.size) {
            val prev = decoded.items[i - 1].key
            val curr = decoded.items[i].key
            val prevTuple = Triple(prev.objectId, prev.type, prev.offset)
            val currTuple = Triple(curr.objectId, curr.type, curr.offset)
            assertTrue(prev <= curr, "items must be sorted: $prev > $curr")
        }
    }

    @Test
    fun internalChildren_areSortedByKeyAfterDecode() {
        val children = listOf(
            BtrfsChildPointer(BtrfsKey(0x5uL, 1u, 0uL), 0x500uL),
            BtrfsChildPointer(BtrfsKey(0x3uL, 2u, 0uL), 0x300uL),
            BtrfsChildPointer(BtrfsKey(0x7uL, 1u, 0uL), 0x700uL)
        )
        val internal = BtrfsInternal(level = 1u, children = children)
        val buf = ByteArray(4096)
        encodeInternal(internal, buf)
        val decoded = decodeInternal(buf)
        for (i in 1 until decoded.children.size) {
            val prev = decoded.children[i - 1].key
            val curr = decoded.children[i].key
            val prevTuple = Triple(prev.objectId, prev.type, prev.offset)
            val currTuple = Triple(curr.objectId, curr.type, curr.offset)
            assertTrue(prev <= curr, "children must be sorted: $prev > $curr")
        }
    }

    // -------------------------------------------------------------------------
    // Full roundtrip
    // -------------------------------------------------------------------------

    @Test
    fun fullRoundtrip_leafAndBack() {
        val originalItems = (1L..50L).map { i ->
            BtrfsItem(
                BtrfsKey(i.toULong(), 1u, i.toULong()),
                0u,
                4u,
                byteArrayOf((i and 0xFF).toByte(), ((i shr 8) and 0xFF).toByte(),
                    ((i shr 16) and 0xFF).toByte(), ((i shr 24) and 0xFF).toByte())
            )
        }
        val leaf = BtrfsLeaf(originalItems)
        val buf = ByteArray(4096)
        encodeLeaf(leaf, buf)
        val decoded = decodeLeaf(buf)
        assertEquals(originalItems.size, decoded.items.size)
        for ((orig, dec) in originalItems.zip(decoded.items)) {
            assertEquals(orig.key.objectId, dec.key.objectId)
            assertEquals(orig.key.type, dec.key.type)
            assertEquals(orig.key.offset, dec.key.offset)
            assertTrue(orig.data.contentEquals(dec.data), "data mismatch for key ${orig.key}")
        }
    }
}
