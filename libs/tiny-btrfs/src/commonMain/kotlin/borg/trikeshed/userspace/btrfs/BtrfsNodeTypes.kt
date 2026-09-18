package borg.trikeshed.userspace.btrfs

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/** Btrfs node size: 4 KiB */
const val BTRFS_NODE_SIZE: Int = 4096

/**

 * Btrfs leaf node — contains key/value items.
 * 
 * This is a stub implementation. Real btrfs format:
 * - header (magic, generation, nritems, item offset)
 * - item offsets array
 * - item data (key + value bytes)
 */
data class BtrfsLeaf(
    val items: Series<BtrfsItem>,
)

/**
 * Btrfs internal node — contains key/block pointer pairs.
 */
data class BtrfsInternal(
    val children: Series<BtrfsChildPointer>,
)

/**
 * A key/data pair stored in a leaf node.
 */
data class BtrfsItem(
    val key: BtrfsKey,
    val data: Series<Byte>,
)

/**
 * Child pointer in an internal node.
 */
data class BtrfsChildPointer(
    val key: BtrfsKey,
    val blockPtr: BtrfsBlockPtr,
)

/**
 * Btrfs key: (objectid, type, offset) tuple.
 */
data class BtrfsKey(
    val objectid: ULong,
    val type: UByte,
    val offset: ULong,
)

/**
 * Block pointer to a child node.
 */
data class BtrfsBlockPtr(
    val blockPtr: ULong,
    val generation: ULong,
)

/**
 * Encode a leaf node into a byte buffer.
 * 
 * TODO: implement proper btrfs leaf encoding with:
 * - 24-byte header
 * - item offsets array
 * - item data
 * - CRC32C checksum
 */
fun encodeLeaf(leaf: BtrfsLeaf, buf: ByteArray, generation: ULong) {
    // Stub: just write the number of items in first 4 bytes
    require(buf.size >= BTRFS_NODE_SIZE) { "Buffer too small" }
    buf[0] = 0x54 // 'T' for TEST
    buf[1] = 0x45 // 'E'
    buf[2] = 0x41 // 'A' 
    buf[3] = 0x46 // 'F'
    // Clear rest
    for (i in 4 until buf.size) buf[i] = 0
}

/**
 * Decode a leaf node from a byte buffer.
 */
fun decodeLeaf(buf: ByteArray): BtrfsLeaf {
    // Stub: return empty leaf
    return BtrfsLeaf(items = 0 j { BtrfsItem(BtrfsKey(0UL, 0u, 0UL), emptyList<Byte>().toSeries()) })
}

/**
 * Encode an internal node into a byte buffer.
 */
fun encodeInternal(internal: BtrfsInternal, buf: ByteArray, generation: ULong) {
    require(buf.size >= BTRFS_NODE_SIZE) { "Buffer too small" }
    buf[0] = 0x42 // 'B' for BTRFS
    buf[1] = 0x54 // 'T'
    buf[2] = 0x52 // 'R'
    buf[3] = 0x46 // 'F'
    for (i in 4 until buf.size) buf[i] = 0
}

/**
 * Decode an internal node from a byte buffer.
 */
fun decodeInternal(buf: ByteArray): BtrfsInternal {
    // Stub: return empty internal node
    return BtrfsInternal(children = 0 j { BtrfsChildPointer(BtrfsKey(0UL, 0u, 0UL), BtrfsBlockPtr(0UL, 0UL)) })
}

private fun <T> List<T>.toSeries(): Series<T> {
    return size j { i -> this[i] }
}
