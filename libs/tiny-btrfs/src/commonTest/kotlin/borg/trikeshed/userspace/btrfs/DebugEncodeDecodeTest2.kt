package borg.trikeshed.userspace.btrfs

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Debug: directly test encode/decode round-trip with minimal data.
 */
class DebugEncodeDecodeTest2 {
    @Test
    fun minimalEncodeDecode() = runTest {
        val key = BtrfsKey(1uL, 1u, 0uL)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val item = BtrfsItem(key j data)
        val leaf = BtrfsLeaf(listOf(item).toSeries())

        val buf = ByteArray(BTRFS_NODE_SIZE)

        // Before encoding
        println("Before encode: magic[0..3]=${buf[0]},${buf[1]},${buf[2]},${buf[3]}")

        encodeLeaf(leaf, buf, 0UL)

        // Check header
        val magic = (buf[0].toUInt() and 0xFFu) or ((buf[1].toUInt() and 0xFFu) shl 8) or
                    ((buf[2].toUInt() and 0xFFu) shl 16) or ((buf[3].toUInt() and 0xFFu) shl 24)
        val gen = (buf[4].toULong() and 0xFFUL) or ((buf[5].toULong() and 0xFFUL) shl 8) or
                  ((buf[6].toULong() and 0xFFUL) shl 16) or ((buf[7].toULong() and 0xFFUL) shl 24)
        val numItems = (buf[16].toUInt() and 0xFFu) or ((buf[17].toUInt() and 0xFFu) shl 8) or
                       ((buf[18].toUInt() and 0xFFu) shl 16) or ((buf[19].toUInt() and 0xFFu) shl 24)
        val firstOff = (buf[22].toInt() and 0xFF) or ((buf[23].toInt() and 0xFF) shl 8)

        println("After encode: magic=0x${magic.toString(16)} gen=$gen numItems=$numItems firstOff=$firstOff")

        // Compute CRC of body (24..4095)
        val csStored = (buf[4].toUInt() and 0xFFu) or ((buf[5].toUInt() and 0xFFu) shl 8) or
                       ((buf[6].toUInt() and 0xFFu) shl 16) or ((buf[7].toUInt() and 0xFFu) shl 24)
        println("Stored CRC[4..7] = ${buf[4]},${buf[5]},${buf[6]},${buf[7]} => $csStored")

        val csComputed = crc32c(buf, 24, BTRFS_NODE_SIZE - 24)
        println("Computed CRC = $csComputed (match: ${csStored == csComputed})")

        // Now decode
        val decoded = decodeLeaf(buf)
        println("Decoded items: ${decoded.items.size}")
        assertEquals(1, decoded.items.size)
        println("PASS: decode succeeded")
    }

    @Test
    fun rawBufRoundTrip() = runTest {
        val buf = ByteArray(BTRFS_NODE_SIZE)

        val key = BtrfsKey(1uL, 1u, 0uL)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val item = BtrfsItem(key j data)
        val leaf = BtrfsLeaf(listOf(item).toSeries())

        // Encode
        encodeLeaf(leaf, buf, 0UL)

        // Decode
        val decoded = decodeLeaf(buf)

        // Compare
        assertEquals(1, decoded.items.size)
        assertEquals(key.objectId, decoded.items[0].key.objectId)
        assertTrue(decoded.items[0].data.contentEquals(data))
        println("PASS: direct round-trip works")
    }

    @Test
    fun viaBuffer() = runTest {
        val buf = UserspaceBtrfsBuffer(chunkSize = 4096)
        buf.open()

        val key = BtrfsKey(1uL, 1u, 0uL)
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val item = BtrfsItem(key j data)
        val leaf = BtrfsLeaf(listOf(item).toSeries())

        val nodeId = buf.allocateNode()
        buf.writeLeaf(nodeId, leaf)

        val storedBytes = buf.readNode(nodeId)
        println("storedBytes size = ${storedBytes?.size}")

        if (storedBytes != null) {
            val cs = crc32c(storedBytes, 24, BTRFS_NODE_SIZE - 24)
            println("CRC of stored bytes = $cs")

            val decodedLeaf = decodeLeaf(storedBytes)
            println("Decoded items = ${decodedLeaf.items.size}")
        }

        val decoded = buf.readLeaf(nodeId)
        println("Decoded items: ${decoded.items.size}")
        buf.close()
    }
}
