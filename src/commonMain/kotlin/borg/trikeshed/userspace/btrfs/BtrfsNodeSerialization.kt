package borg.trikeshed.userspace.btrfs

import kotlin.experimental.and

// =============================================================================
// Btrfs node serialization — LE encoding throughout
// =============================================================================

/** crc32c using Castagnoli polynomial 0x82F63B78. Returns 32-bit checksum. */
fun crc32c(bytes: ByteArray, offset: Int, length: Int): UInt {
    if (length == 0) return 0u
    val table = crc32cTable()
    var crc = 0xFFFFFFFFu
    val end = offset + length
    for (i in offset until end) {
        val b = bytes[i].toUInt() and 0xFFu
        val idx = ((crc xor b) and 0xFFu).toInt()
        crc = (crc.toULong() shr 8).toUInt() xor table[idx].toUInt()
    }
    return crc xor 0xFFFFFFFFu
}

private fun crc32cTable(): LongArray {
    val t = LongArray(256)
    val poly = 0x82F63B78L
    for (i in 0 until 256) {
        var c = i.toLong()
        for (k in 0 until 8) {
            c = if ((c and 1L) != 0L) (c shr 1) xor poly else c shr 1
        }
        t[i] = c
    }
    return t
}

// =============================================================================
// On-disk structures
// =============================================================================

/** Btrfs key: object_id (8B LE) + type (4B LE) + offset (8B LE) = 20 bytes. */
data class BtrfsKey(val objectId: ULong, val type: UInt, val offset: ULong) : Comparable<BtrfsKey> {
    override fun compareTo(other: BtrfsKey): Int {
        val o = objectId.compareTo(other.objectId)
        if (o != 0) return o
        val t = type.compareTo(other.type)
        if (t != 0) return t
        return offset.compareTo(other.offset)
    }
}

/** Single item inside a leaf node. */
data class BtrfsItem(
    val key: BtrfsKey,
    val offset: UInt,
    val size: UInt,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BtrfsItem) return false
        if (key != other.key) return false
        if (offset != other.offset) return false
        if (size != other.size) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }
    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + offset.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/** Leaf node — ordered list of items. */
data class BtrfsLeaf(val items: List<BtrfsItem>)

/** Pointer to a child inside an internal node. */
data class BtrfsChildPointer(val key: BtrfsKey, val blockPtr: ULong)

/** Internal (non-leaf) node. */
data class BtrfsInternal(val level: UInt, val children: List<BtrfsChildPointer>)

// =============================================================================
// Magic values (LE UInt interpretations of raw bytes)
// =============================================================================

/** Leaf node magic = 0x54465242 ("BTRF" in LE). */
const val LEAF_NODE_MAGIC = 0x54465242u

/** Internal node magic = 0x54465249 ("INTF" in LE). */
const val INTERNAL_NODE_MAGIC = 0x54465249u

/** Leaf node type field value. */
const val LEAF_NODE_TYPE: UInt = 1u

/** Internal node type field value. */
const val INTERNAL_NODE_TYPE: UInt = 2u

/** Node header size = 24 bytes. */
const val BTRFS_NODE_HEADER_SIZE = 24

// =============================================================================
// Low-level LE read/write helpers
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

private fun ByteArray.readUIntLE(offset: Int): UInt {
    var v = 0u
    v = v or ((this[offset + 0].toUInt() and 0xFFu) shl 0)
    v = v or ((this[offset + 1].toUInt() and 0xFFu) shl 8)
    v = v or ((this[offset + 2].toUInt() and 0xFFu) shl 16)
    v = v or ((this[offset + 3].toUInt() and 0xFFu) shl 24)
    return v
}

private fun ByteArray.writeUIntLE(offset: Int, value: UInt) {
    this[offset + 0] = (value and 0xFFu).toByte()
    this[offset + 1] = ((value shr 8) and 0xFFu).toByte()
    this[offset + 2] = ((value shr 16) and 0xFFu).toByte()
    this[offset + 3] = ((value shr 24) and 0xFFu).toByte()
}

private fun ByteArray.readUInt16LE(offset: Int): UShort {
    var v = 0u
    v = v or ((this[offset + 0].toUInt() and 0xFFu) shl 0)
    v = v or ((this[offset + 1].toUInt() and 0xFFu) shl 8)
    return v.toUShort()
}

private fun ByteArray.writeUInt16LE(offset: Int, value: UShort) {
    this[offset + 0] = (value and 0xFFu).toByte()
    this[offset + 1] = ((value.toUInt() shr 8) and 0xFFu).toByte()
}

// =============================================================================
// BtrfsKey encode/decode
// =============================================================================

/** Encode a BtrfsKey at bytes[offset..offset+19] (LE). */
fun encodeKey(key: BtrfsKey, bytes: ByteArray, offset: Int) {
    bytes.writeULongLE(offset, key.objectId)
    bytes.writeUIntLE(offset + 8, key.type)
    bytes.writeULongLE(offset + 12, key.offset)
}

/** Decode a BtrfsKey from bytes[offset..offset+19] (LE). */
fun decodeKey(bytes: ByteArray, offset: Int): BtrfsKey {
    val objectId = bytes.readULongLE(offset)
    val type = bytes.readUIntLE(offset + 8)
    val offsetVal = bytes.readULongLE(offset + 12)
    return BtrfsKey(objectId, type, offsetVal)
}

// =============================================================================
// Node header
// =============================================================================

private data class NodeHeader(
    val magic: UInt,
    val headerLength: UShort,
    val checksum: UInt,
    val nodeType: UInt,
    val level: UShort,
    val reserved: ByteArray
)

// =============================================================================
// Leaf node encode/decode
// =============================================================================

/** Encode a BtrfsLeaf into bytes (full buffer, no external offset). */
fun encodeLeaf(leaf: BtrfsLeaf, bytes: ByteArray) {
    val count = leaf.items.size
    // Header (24 bytes) at offset 0
    bytes.writeUIntLE(0, LEAF_NODE_MAGIC)
    bytes.writeUInt16LE(2, BTRFS_NODE_HEADER_SIZE.toUShort())
    // Checksum placeholder (filled after body)
    val bodyOffset = BTRFS_NODE_HEADER_SIZE
    // Node type = 1 (leaf), level = 0
    bytes.writeUInt16LE(8, LEAF_NODE_TYPE.toUShort())
    bytes.writeUInt16LE(10, 0u)  // level = 0 for leaf

    // Number of items at offset 14
    bytes.writeUIntLE(14, count.toUInt())

    // Compute item area: each item has 12-byte header + data
    val itemHeaderSize = count * 12
    val totalDataSize = leaf.items.sumOf { it.data.size }
    val itemAreaStart = bytes.size - itemHeaderSize - totalDataSize

    // Write item headers and data
    var dataWriteOffset = itemAreaStart + itemHeaderSize
    val itemOffsets = IntArray(count)
    for (i in 0 until count) {
        val item = leaf.items[i]
        val keyOffset = itemAreaStart + i * 12
        itemOffsets[i] = keyOffset
        bytes.writeUIntLE(14 + i * 12, keyOffset.toUInt())       // key offset (relative to buffer)
        bytes.writeUIntLE(14 + i * 12 + 4, item.size)   // item size
        bytes.writeUIntLE(14 + i * 12 + 8, item.offset) // data offset

        // Write item key at its keyOffset
        encodeKey(item.key, bytes, keyOffset)
        // Write item data
        item.data.copyInto(bytes, dataWriteOffset, 0, item.data.size)
        dataWriteOffset += item.data.size
    }

    // Compute and write checksum (covers everything after header = body)
    val crc = crc32c(bytes, bodyOffset, bytes.size - bodyOffset)
    bytes.writeUIntLE(4, crc)
}

/** Decode a BtrfsLeaf from bytes. Throws IllegalStateException on bad magic/checksum. */
fun decodeLeaf(bytes: ByteArray): BtrfsLeaf {
    if (bytes.size < BTRFS_NODE_HEADER_SIZE) {
        throw IllegalStateException("Buffer too short for leaf header")
    }
    val magic = bytes.readUIntLE(0)
    if (magic != LEAF_NODE_MAGIC) {
        throw IllegalStateException("Invalid leaf magic: ${magic.toString(16)}, expected ${LEAF_NODE_MAGIC.toString(16)}")
    }
    val storedCrc = bytes.readUIntLE(4)
    val computedCrc = crc32c(bytes, BTRFS_NODE_HEADER_SIZE, bytes.size - BTRFS_NODE_HEADER_SIZE)
    if (storedCrc != computedCrc) {
        throw IllegalStateException("Leaf checksum mismatch: stored=${storedCrc.toString(16)}, computed=${computedCrc.toString(16)}")
    }
    val count = bytes.readUIntLE(14).toInt()
    val items = mutableListOf<BtrfsItem>()
    for (i in 0 until count) {
        val slotOffset = 14 + i * 12
        val keyOffset = bytes.readUIntLE(slotOffset).toInt()
        val size = bytes.readUIntLE(slotOffset + 4)
        val dataOffset = bytes.readUIntLE(slotOffset + 8)
        val key = decodeKey(bytes, keyOffset)
        val data = bytes.copyOfRange(dataOffset.toInt(), dataOffset.toInt() + size.toInt())
        items.add(BtrfsItem(key, dataOffset, size, data))
    }
    return BtrfsLeaf(items)
}

// =============================================================================
// Internal node encode/decode
// =============================================================================

/** Encode a BtrfsInternal into bytes. */
fun encodeInternal(internal: BtrfsInternal, bytes: ByteArray) {
    val count = internal.children.size
    // Header (24 bytes) at offset 0
    bytes.writeUIntLE(0, INTERNAL_NODE_MAGIC)
    bytes.writeUInt16LE(2, BTRFS_NODE_HEADER_SIZE.toUShort())
    // Checksum placeholder
    val bodyOffset = BTRFS_NODE_HEADER_SIZE
    bytes.writeUInt16LE(8, INTERNAL_NODE_TYPE.toUShort())
    bytes.writeUInt16LE(10, internal.level.toUShort())

    // Number of children at offset 14
    bytes.writeUIntLE(14, count.toUInt())

    // Child pointers start after slot array
    val slotSize = count * 24
    val childStart = bytes.size - slotSize
    for (i in 0 until count) {
        val ptr = childStart + i * 24
        encodeKey(internal.children[i].key, bytes, ptr)
        bytes.writeULongLE(ptr + 20, internal.children[i].blockPtr)
    }

    // Compute and write checksum
    val crc = crc32c(bytes, bodyOffset, bytes.size - bodyOffset)
    bytes.writeUIntLE(4, crc)
}

/** Decode a BtrfsInternal from bytes. Throws IllegalStateException on bad magic/checksum. */
fun decodeInternal(bytes: ByteArray): BtrfsInternal {
    if (bytes.size < BTRFS_NODE_HEADER_SIZE) {
        throw IllegalStateException("Buffer too short for internal header")
    }
    val magic = bytes.readUIntLE(0)
    if (magic != INTERNAL_NODE_MAGIC) {
        throw IllegalStateException("Invalid internal magic: ${magic.toString(16)}, expected ${INTERNAL_NODE_MAGIC.toString(16)}")
    }
    val storedCrc = bytes.readUIntLE(4)
    val computedCrc = crc32c(bytes, BTRFS_NODE_HEADER_SIZE, bytes.size - BTRFS_NODE_HEADER_SIZE)
    if (storedCrc != computedCrc) {
        throw IllegalStateException("Internal checksum mismatch")
    }
    val level = bytes.readUIntLE(10).toUShort()
    val count = bytes.readUIntLE(14).toInt()
    val children = mutableListOf<BtrfsChildPointer>()
    val slotSize = count * 24
    val childStart = bytes.size - slotSize
    for (i in 0 until count) {
        val ptr = childStart + i * 24
        val key = decodeKey(bytes, ptr)
        val blockPtr = bytes.readULongLE(ptr + 20)
        children.add(BtrfsChildPointer(key, blockPtr))
    }
    return BtrfsInternal(level.toUInt(), children)
}
