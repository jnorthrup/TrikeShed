package borg.trikeshed.userspace.btrfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.tinybtrfs.DiskAdapter
import kotlin.coroutines.CoroutineContext

/**
 * Btrfs block device adapter wrapping a [UserspaceMemoryBuffer].
 *
 * Implements [DiskAdapter] and adds:
 * - Superblock written on open()
 * - CRC32C checksum validation on every readNode()
 * - Btrfs node magic validation
 * - ElementState lifecycle management
 *
 * The superblock stores:
 * - Magic: "\_BHRF" = 0x42445254465D4B_uL (16 bytes, LE)
 * - Root tree nodeId pointer at offset 16 (8 bytes, LE)
 * - Device size at offset 24 (8 bytes, LE)
 */
class BtrfsBlockDevice(
    private val buffer: UserspaceMemoryBuffer
) : AsyncContextElement(), DiskAdapter {

    override val key = Key
    companion object Key : CoroutineContext.Key<BtrfsBlockDevice>

    /** Superblock magic: "\_BHRF" in LE = 0x42445254465D4B_uL. */
    val SUPERBLOCK_MAGIC = 0x42445254465D4B_uL

    /** Superblock node ID used internally. */
    private val SUPERBLOCK_ID = "superblock"

    // -------------------------------------------------------------------------
    // DiskAdapter delegates to underlying buffer
    // -------------------------------------------------------------------------

    override fun readNode(nodeId: String): ByteArray? {
        check(state == ElementState.OPEN) { "Device not open (state=$state)" }
        val raw = buffer.readNode(nodeId) ?: return null
        // Validate magic (first 4 bytes as LE UInt)
        val magic = decodeNodeMagic(raw)
        if (magic != LEAF_NODE_MAGIC && magic != INTERNAL_NODE_MAGIC) {
            throw IllegalStateException("Invalid node magic: ${magic.toString(16)}")
        }
        // Validate checksum (decodeLeaf/decodeInternal throws on mismatch)
        if (magic == LEAF_NODE_MAGIC) {
            decodeLeaf(raw)
        } else {
            decodeInternal(raw)
        }
        return raw
    }

    override fun writeNode(nodeId: String, bytes: ByteArray) {
        check(state == ElementState.OPEN) { "Device not open (state=$state)" }
        buffer.writeNode(nodeId, bytes)
    }

    override fun allocateNode(): String {
        check(state == ElementState.OPEN) { "Device not open (state=$state)" }
        return buffer.allocateNode()
    }

    override fun freeNode(nodeId: String) {
        check(state == ElementState.OPEN) { "Device not open (state=$state)" }
        buffer.freeNode(nodeId)
    }

    // -------------------------------------------------------------------------
    // Superblock access
    // -------------------------------------------------------------------------

    /** Read the 16-byte superblock magic (LE). */
    fun readSuperblockMagic(): ULong {
        val sbBytes = buffer.readNode(SUPERBLOCK_ID) ?: return 0uL
        if (sbBytes.size < 16) return 0uL
        return sbBytes.readULongLE(0)
    }

    /** Read the root tree nodeId from the superblock (offset 16). Returns null if not set. */
    fun rootTreeNodeId(): String? {
        val sbBytes = buffer.readNode(SUPERBLOCK_ID) ?: return null
        if (sbBytes.size < 24) return null
        val ptr = sbBytes.readULongLE(16)
        if (ptr == 0UL) return null
        return "n-$ptr"
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            buffer.open()
            state = ElementState.OPEN
            writeSuperblock()
        }
    }

    private fun writeSuperblock() {
        val sbBytes = ByteArray(4096)
        // Magic at offset 0 (16 bytes LE)
        sbBytes.writeULongLE(0, SUPERBLOCK_MAGIC)
        // Root tree nodeId at offset 16 (8 bytes LE) — initially 0
        sbBytes.writeULongLE(16, 0UL)
        // Device size at offset 24 (8 bytes LE) — 4 chunks of chunkSize
        val deviceSize = (buffer.chunkSize.toLong() * 4)
        sbBytes.writeULongLE(24, deviceSize.toULong())
        buffer.writeNode(SUPERBLOCK_ID, sbBytes)
    }

    override suspend fun close() {
        if (!state.isAtLeast(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            buffer.close()
            state = ElementState.CLOSED
        }
    }
}

// =============================================================================
// Internal LE helpers
// =============================================================================

private fun ByteArray.readULongLE(offset: Int): ULong {
    var v = 0UL
    v = v or (this[offset + 0].toULong() and 0xFFUL)
    v = v or ((this[offset + 1].toULong() and 0xFFUL) shl 8)
    v = v or ((this[offset + 2].toULong() and 0xFFUL) shl 16)
    v = v or ((this[offset + 3].toULong() and 0xFFUL) shl 24)
    v = v or ((this[offset + 4].toULong() and 0xFFUL) shl 32)
    v = v or ((this[offset + 5].toULong() and 0xFFUL) shl 40)
    v = v or ((this[offset + 6].toULong() and 0xFFUL) shl 48)
    v = v or ((this[offset + 7].toULong() and 0xFFUL) shl 56)
    return v
}

private fun ByteArray.writeULongLE(offset: Int, value: ULong) {
    this[offset + 0] = (value and 0xFFUL).toByte()
    this[offset + 1] = ((value shr 8) and 0xFFUL).toByte()
    this[offset + 2] = ((value shr 16) and 0xFFUL).toByte()
    this[offset + 3] = ((value shr 24) and 0xFFUL).toByte()
    this[offset + 4] = ((value shr 32) and 0xFFUL).toByte()
    this[offset + 5] = ((value shr 40) and 0xFFUL).toByte()
    this[offset + 6] = ((value shr 48) and 0xFFUL).toByte()
    this[offset + 7] = ((value shr 56) and 0xFFUL).toByte()
}

/** Read first 4 bytes as LE UInt to get node magic. */
private fun decodeNodeMagic(bytes: ByteArray): UInt {
    if (bytes.size < 4) return 0u
    var v = 0u
    v = v or ((bytes[0].toUInt() and 0xFFu) shl 0)
    v = v or ((bytes[1].toUInt() and 0xFFu) shl 8)
    v = v or ((bytes[2].toUInt() and 0xFFu) shl 16)
    v = v or ((bytes[3].toUInt() and 0xFFu) shl 24)
    return v
}
