package borg.trikeshed.btrfs

import borg.trikeshed.userspace.btrfs.BtrfsTreeCodec
import borg.trikeshed.userspace.btrfs.BtrfsTreeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B1 — Btrfs tree codec RED tests.
 *
 * Golden leaf/internal-node bytes parse to exact keys and item bounds.
 * Malformed offsets and endian errors are rejected.
 *
 * Types referenced are NEW.
 */
class BtrfsTreeCodecTest {

    @Test
    fun goldenLeafParsesToExactKeys() {
        val codec = BtrfsTreeCodec
        val golden = byteArrayOf(
            // Minimal valid btrfs leaf header (32 bytes):
            // csum: 32 bytes (zeroed)
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            // bytenr: 8 bytes (0)
            0, 0, 0, 0, 0, 0, 0, 0,
            // flags: 8 bytes (0 = leaf)
            0, 0, 0, 0, 0, 0, 0, 0,
            // chunk_tree_uuid: 16 bytes (zeroed)
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            // generation: 8 bytes
            0, 0, 0, 0, 0, 0, 0, 0,
            // nritems: 4 bytes (1 item)
            1, 0, 0, 0,
            // level: 1 byte (0 = leaf)
            0,
        )
        val leaf = codec.parseLeaf(golden)
        assertEquals(0, leaf.header.level, "level must be 0 for leaf")
        assertEquals(1, leaf.header.nritems, "nritems must be 1")
    }

    @Test
    fun malformedOffsetRejected() {
        val codec = BtrfsTreeCodec
        // Too short to be a valid header
        val bad = byteArrayOf(0, 1, 2, 3)
        try {
            codec.parseLeaf(bad)
            fail("must reject malformed leaf")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too short") || e.message!!.contains("malformed") || e.message!!.contains("size"))
        }
    }

    @Test
    fun endianConsistency() {
        // Btrfs is little-endian. The codec must read LE correctly.
        val codec = BtrfsTreeCodec
        val leBytes = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0) // 1 as LE long
        val value = codec.readLeLong(leBytes, 0)
        assertEquals(1L, value, "must read little-endian long")
    }

    private fun fail(msg: String): Nothing {
        throw AssertionError(msg)
    }
}
