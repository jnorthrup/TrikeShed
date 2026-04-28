package borg.trikeshed.userspace.btrfs

import borg.trikeshed.lib.*
import kotlin.experimental.and

// =============================================================================
// Btrfs node serialization — LE encoding throughout, CRC32C checksumming
// =============================================================================

/**
 * CRC32C (Castagnoli polynomial 0x82F63B78) over a byte range.
 * Returns 32-bit checksum as UInt.
 */
fun crc32c(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): UInt {
    if (length <= 0) return 0u
    val table = crc32cTable
    var crc = 0xFFFFFFFFu
    val end = offset + length
    for (i in offset until end) {
        val idx = ((crc xor (data[i].toUInt() and 0xFFu)) and 0xFFu).toInt()
        crc = (crc.toULong() shr 8).toUInt() xor table[idx]
    }
    return crc xor 0xFFFFFFFFu
}

private val crc32cTable: UIntArray by lazy {
    UIntArray(256) { i ->
        var c = i.toUInt()
        repeat(8) { c = if ((c and 1u) != 0u) (0x82F63B78u xor (c shr 1)) else (c shr 1) }
        c
    }
}

// =============================================================================
// On-disk magic constants
// =============================================================================

internal const val LEAF_MAGIC    = 0x464F5254u  // "TROF" LE = "BTRF" marker
internal const val INTERNAL_MAGIC = 0x4E465242u  // "BRFN" LE = "BTRN" marker

/** Btrfs node size constant (fixed 4 KiB pages). */
const val BTRFS_NODE_SIZE = 4096

// =============================================================================
// Low-level LE read/write primitives
// =============================================================================

internal fun ByteArray.putU64LE(pos: Int, v: ULong) {
    this[pos + 0] = (v and 0xFFu).toByte()
    this[pos + 1] = ((v shr 8) and 0xFFu).toByte()
    this[pos + 2] = ((v shr 16) and 0xFFu).toByte()
    this[pos + 3] = ((v shr 24) and 0xFFu).toByte()
    this[pos + 4] = ((v shr 32) and 0xFFu).toByte()
    this[pos + 5] = ((v shr 40) and 0xFFu).toByte()
    this[pos + 6] = ((v shr 48) and 0xFFu).toByte()
    this[pos + 7] = ((v shr 56) and 0xFFu).toByte()
}

internal fun ByteArray.getU64LE(pos: Int): ULong {
    var r = 0UL
    r = r or this[pos + 0].toULong()
    r = r or (this[pos + 1].toULong() shl 8)
    r = r or (this[pos + 2].toULong() shl 16)
    r = r or (this[pos + 3].toULong() shl 24)
    r = r or (this[pos + 4].toULong() shl 32)
    r = r or (this[pos + 5].toULong() shl 40)
    r = r or (this[pos + 6].toULong() shl 48)
    r = r or (this[pos + 7].toULong() shl 56)
    return r
}

internal fun ByteArray.putU32LE(pos: Int, v: UInt) {
    this[pos + 0] = (v and 0xFFu).toByte()
    this[pos + 1] = ((v shr 8) and 0xFFu).toByte()
    this[pos + 2] = ((v shr 16) and 0xFFu).toByte()
    this[pos + 3] = ((v shr 24) and 0xFFu).toByte()
}

internal fun ByteArray.getU32LE(pos: Int): UInt {
    var r = 0u
    r = r or this[pos + 0].toUInt()
    r = r or (this[pos + 1].toUInt() shl 8)
    r = r or (this[pos + 2].toUInt() shl 16)
    r = r or (this[pos + 3].toUInt() shl 24)
    return r
}

internal fun ByteArray.putU16LE(pos: Int, v: UShort) {
    val vi = v.toInt()
    this[pos + 0] = (vi and 0xFF).toByte()
    this[pos + 1] = ((vi shr 8) and 0xFF).toByte()
}

internal fun ByteArray.getU16LE(pos: Int): UShort {
    val lo = this[pos + 0].toInt() and 0xFF
    val hi = this[pos + 1].toInt() and 0xFF
    return ((hi shl 8) or lo).toUShort()
}

// =============================================================================
// BtrfsKey
// =============================================================================

/**
 * Btrfs key: (objectId, type, offset) — three-part lexicographic key.
 * Comparable<BtrfsKey> for binary-search over sorted items.
 *
 * Disk layout (20 bytes LE): objectId(8) | type(4) | offset(8)
 */
data class BtrfsKey(
    val objectId: ULong,
    val type: UInt,
    val offset: ULong,
) : Comparable<BtrfsKey> {
    override fun compareTo(other: BtrfsKey): Int {
        val o = objectId.compareTo(other.objectId)
        if (o != 0) return o
        val t = type.compareTo(other.type)
        if (t != 0) return t
        return offset.compareTo(other.offset)
    }

    companion object {
        /** Empty key = all zeros. */
        val EMPTY = BtrfsKey(0UL, 0U, 0UL)
    }
}

fun encodeKey(key: BtrfsKey, buf: ByteArray, pos: Int) {
    buf.putU64LE(pos,       key.objectId)
    buf.putU32LE(pos + 8,   key.type)
    buf.putU64LE(pos + 12,  key.offset)
}

fun decodeKey(buf: ByteArray, pos: Int): BtrfsKey =
    BtrfsKey(buf.getU64LE(pos), buf.getU32LE(pos + 8), buf.getU64LE(pos + 12))

// =============================================================================
// BtrfsItem — Join<BtrfsKey, ByteArray>
// =============================================================================

/**
 * Btrfs item inside a leaf node.
 *
 * Smart constructor accepts [BtrfsItemSpec] = Join<BtrfsKey, ByteArray>:
 *   `BtrfsItem(key j byteArrayOf(...))`
 *
 * Disk layout (28 bytes header + data):
 *   key(20) | dataOffset(4) | dataSize(4) | data(variable)
 */
data class BtrfsItem(
    val key: BtrfsKey,
    val dataOffset: UInt,
    val dataSize: UInt,
    val data: ByteArray,
) {
    init { require(data.size.toUInt() == dataSize) { "data.size must match dataSize" } }

    companion object {
        /** Smart constructor from Join<BtrfsKey, ByteArray>. */
        operator fun invoke(spec: Join<BtrfsKey, ByteArray>): BtrfsItem =
            BtrfsItem(spec.a, 0u, spec.b.size.toUInt(), spec.b)

        operator fun invoke(key: BtrfsKey, data: ByteArray): BtrfsItem =
            BtrfsItem(key, 0u, data.size.toUInt(), data)
    }

    override fun equals(other: Any?): Boolean = other is BtrfsItem &&
        key == other.key && dataOffset == other.dataOffset &&
        dataSize == other.dataSize && data.contentEquals(other.data)
    override fun hashCode(): Int {
        var h = key.hashCode()
        h = 31 * h + dataOffset.hashCode()
        h = 31 * h + dataSize.hashCode()
        h = 31 * h + data.contentHashCode()
        return h
    }
}

/** Universal constructor: `key j byteArray` → BtrfsItem. */
fun btrfsItem(key: BtrfsKey, data: ByteArray): BtrfsItem = BtrfsItem(key j data)

// =============================================================================
// BtrfsChildPointer — Join<BtrfsKey, ULong>
// =============================================================================

/**
 * Child pointer in an internal node.
 * Structural encoding: [BtrfsKey] j [ULong] (blockPtr)
 *
 * On disk: key(20) + blockPtr(8) = 28 bytes, LE.
 *
 * Smart constructor accepts Join<BtrfsKey, ULong>:
 *   `BtrfsChildPointer(key j blockPtr)`
 */
data class BtrfsChildPointer(
    val key: BtrfsKey,
    val blockPtr: ULong,
) {
    companion object {
        /** Smart constructor from Join<BtrfsKey, ULong>. */
        operator fun invoke(spec: Join<BtrfsKey, ULong>): BtrfsChildPointer =
            BtrfsChildPointer(spec.a, spec.b)
    }
}

/** Universal constructor: `key j blockPtr` → BtrfsChildPointer. */
fun btrfsChildPointer(key: BtrfsKey, blockPtr: ULong): BtrfsChildPointer =
    BtrfsChildPointer(key j blockPtr)

// =============================================================================
// BtrfsInternal — Join<UInt, Series<BtrfsChildPointer>>
// =============================================================================

/**
 * Internal (non-leaf) node: level + sorted child pointers.
 *
 * Smart constructor accepts Join<UInt, Series<BtrfsChildPointer>>:
 *   `BtrfsInternal(level j children)`
 */
data class BtrfsInternal(
    val level: UInt,
    val children: Series<BtrfsChildPointer>,
) {
    companion object {
        /** Smart constructor from Join<UInt, Series<BtrfsChildPointer>>. */
        operator fun invoke(spec: Join<UInt, Series<BtrfsChildPointer>>): BtrfsInternal =
            BtrfsInternal(spec.a, spec.b)
    }
}

/** Universal constructor: `level j children` → BtrfsInternal. */
fun btrfsInternal(level: UInt, children: Series<BtrfsChildPointer>): BtrfsInternal =
    BtrfsInternal(level j children)

// =============================================================================
// BtrfsLeaf — single-field wrapper expressible as Join<Series<BtrfsItem>, Unit>
// =============================================================================

/**
 * Leaf node — ordered list of items.
 *
 * Algebraically: Join<Series<BtrfsItem>, Unit> since it carries only items.
 * For range queries: Sequence<Join<BtrfsKey, ByteArray>>.
 *
 * Usage: `BtrfsLeaf(items)` or destructure: `val (items) = leaf`
 */
data class BtrfsLeaf(val items: Series<BtrfsItem>) {
    companion object {
        val empty = BtrfsLeaf(emptySeries())
    }
}

// =============================================================================
// Node header encode/decode
// =============================================================================

/**
 * Node header — first 24 bytes of every node (leaf or internal).
 *
 * Layout:
 *   0..3   : magic      (u32 LE; 0x464F5254 for leaf, 0x4E465242 for internal)
 *   4..7   : checksum   (u32 LE; CRC32C over bytes 24..BTRFS_NODE_SIZE-1)
 *   8..15  : generation (u64 LE)
 *   16..19 : numItems   (u32 LE; leaf: item count, internal: child count)
 *   20..21 : fsUuid     (8 bytes; zeros here)
 *   22..23 : firstItemOffset (u16 LE)
 */
data class BtrfsNodeHeader(
    val magic: UInt,
    val checksum: UInt,
    val generation: ULong,
    val numItems: UInt,
    val firstItemOffset: UShort,
) {
    val isLeaf: Boolean get() = magic == LEAF_MAGIC
    val isInternal: Boolean get() = magic == INTERNAL_MAGIC
}

/** Decode node header from buf (buf must have at least 24 bytes). */
fun decodeNodeHeader(buf: ByteArray): BtrfsNodeHeader {
    if (buf.size < 24) error("Buffer too small for node header: ${buf.size} < 24")
    val magic = buf.getU32LE(0)
    if (magic != LEAF_MAGIC && magic != INTERNAL_MAGIC) {
        error("Invalid magic: 0x${magic.toString(16)}, expected 0x${LEAF_MAGIC.toString(16)} or 0x${INTERNAL_MAGIC.toString(16)}")
    }
    return BtrfsNodeHeader(
        magic = magic,
        checksum = buf.getU32LE(4),
        generation = buf.getU64LE(8),
        numItems = buf.getU32LE(16),
        firstItemOffset = buf.getU16LE(22),
    )
}

/** Encode node header into buf (buf must have at least 24 bytes). */
fun encodeNodeHeader(header: BtrfsNodeHeader, buf: ByteArray) {
    buf.putU32LE(0, header.magic)
    buf.putU32LE(4, header.checksum)
    buf.putU64LE(8, header.generation)
    buf.putU32LE(16, header.numItems)
    for (i in 0..7) buf[20 + i] = 0  // fsUuid = zeros
    buf.putU16LE(22, header.firstItemOffset)
}

// =============================================================================
// Leaf encode/decode
// =============================================================================

/**
 * Encode `leaf` into `buf` (must be BTRFS_NODE_SIZE bytes).
 * Items are sorted by key before writing; offsets are updated accordingly.
 */
fun encodeLeaf(leaf: BtrfsLeaf, buf: ByteArray, generation: ULong = 0UL) {
    check(buf.size >= BTRFS_NODE_SIZE) { "Buffer too small: ${buf.size} < $BTRFS_NODE_SIZE" }

    val sorted = Array(leaf.items.size) { leaf.items[it] }.apply { sortBy { it.key } }
    val numItems = sorted.size

    buf.putU32LE(0, LEAF_MAGIC)
    buf.putU64LE(8, generation)

    // Calculate total item space: key(20) + dataOffset(4) + dataSize(4) + data(variable)
    val dataSpace = sorted.sumOf { 20 + 4 + 4 + it.data.size }
    // Align start to 8-byte boundary from the end of the buffer
    val alignedStart = (BTRFS_NODE_SIZE - dataSpace) and 0x7.inv()

    buf.putU32LE(16, numItems.toUInt())
    for (i in 0..7) buf[20 + i] = 0
    buf.putU16LE(22, alignedStart.toUShort())

    var offset = alignedStart
    for (item in sorted) {
        offset = (offset + 7) and 0x7.inv()  // 8-align before each item
        encodeKey(item.key, buf, offset); offset += 20
        buf.putU32LE(offset, item.dataOffset); offset += 4
        buf.putU32LE(offset, item.dataSize);   offset += 4
        item.data.copyInto(buf, offset);       offset += item.data.size
    }

    // Checksum over bytes 24..BTRFS_NODE_SIZE-1
    val cs = crc32c(buf, 24, BTRFS_NODE_SIZE - 24)
    buf.putU32LE(4, cs)
}

/**
 * Decode a leaf from `buf`.
 * @throws IllegalStateException on bad magic or checksum mismatch
 */
fun decodeLeaf(buf: ByteArray): BtrfsLeaf {
    if (buf.size < 24) throw IllegalStateException("Buffer too small for leaf: ${buf.size} < 24")
    if (buf.getU32LE(0) != LEAF_MAGIC) {
        throw IllegalStateException("Invalid leaf magic: 0x${buf.getU32LE(0).toString(16)}")
    }

    val storedCs = buf.getU32LE(4)
    val computedCs = if (buf.size >= BTRFS_NODE_SIZE) {
        crc32c(buf, 24, BTRFS_NODE_SIZE - 24)
    } else {
        crc32c(buf, 24, buf.size - 24)
    }
    if (storedCs != computedCs) {
        throw IllegalStateException("Leaf checksum mismatch: stored=$storedCs computed=$computedCs")
    }

    val numItems = buf.getU32LE(16).toInt()
    if (numItems == 0) return BtrfsLeaf.empty

    val firstOffset = buf.getU16LE(22).toInt()
    val items = ArrayList<BtrfsItem>(numItems)
    var offset = firstOffset
    repeat(numItems) {
        offset = (offset + 7) and 0x7.inv()
        val key = decodeKey(buf, offset); offset += 20
        val dataOff = buf.getU32LE(offset); offset += 4
        val dataSz  = buf.getU32LE(offset).toInt(); offset += 4
        val dataEnd = minOf(offset + dataSz, buf.size)
        val data = buf.copyOfRange(offset, dataEnd); offset = dataEnd
        items.add(BtrfsItem(key, dataOff, dataSz.toUInt(), data))
    }
    return BtrfsLeaf(items.toSeries())
}

// =============================================================================
// Internal node encode/decode
// =============================================================================

// Child pointer = key(20) + blockPtr(8) = 28 bytes each

/**
 * Encode `node` (internal) into `buf` (must be BTRFS_NODE_SIZE bytes).
 * Children are sorted by key before writing.
 */
fun encodeInternal(node: BtrfsInternal, buf: ByteArray, generation: ULong = 0UL) {
    check(buf.size >= BTRFS_NODE_SIZE) { "Buffer too small: ${buf.size} < $BTRFS_NODE_SIZE" }

    val sorted = Array(node.children.size) { node.children[it] }.apply { sortBy { it.key } }
    val numItems = sorted.size

    buf.putU32LE(0, INTERNAL_MAGIC)
    buf.putU64LE(8, generation)
    buf.putU32LE(16, numItems.toUInt())
    for (i in 0..7) buf[20 + i] = 0

    // Child pointer = 28 bytes each; pack from end of buffer
    val childSpace = numItems * 28
    val startOffset = (BTRFS_NODE_SIZE - childSpace) and 0x7.inv()
    buf.putU16LE(22, startOffset.toUShort())

    var offset = startOffset
    for (child in sorted) {
        encodeKey(child.key, buf, offset); offset += 20
        buf.putU64LE(offset, child.blockPtr); offset += 8
    }

    val cs = crc32c(buf, 24, BTRFS_NODE_SIZE - 24)
    buf.putU32LE(4, cs)
}

/**
 * Decode an internal node from `buf`.
 * @throws IllegalStateException on bad magic or checksum mismatch
 */
fun decodeInternal(buf: ByteArray): BtrfsInternal {
    if (buf.size < 24) throw IllegalStateException("Buffer too small for internal: ${buf.size} < 24")
    if (buf.getU32LE(0) != INTERNAL_MAGIC) {
        throw IllegalStateException("Invalid internal magic: 0x${buf.getU32LE(0).toString(16)}")
    }

    val storedCs = buf.getU32LE(4)
    val computedCs = if (buf.size >= BTRFS_NODE_SIZE) {
        crc32c(buf, 24, BTRFS_NODE_SIZE - 24)
    } else {
        crc32c(buf, 24, buf.size - 24)
    }
    if (storedCs != computedCs) {
        throw IllegalStateException("Internal checksum mismatch: stored=$storedCs computed=$computedCs")
    }

    val numItems = buf.getU32LE(16).toInt()
    val firstOffset = buf.getU16LE(22).toInt()

    if (numItems == 0) return BtrfsInternal(0U, emptySeries())

    val children = ArrayList<BtrfsChildPointer>(numItems)
    var offset = firstOffset
    repeat(numItems) {
        val key = decodeKey(buf, offset); offset += 20
        val blockPtr = buf.getU64LE(offset); offset += 8
        children.add(BtrfsChildPointer(key j blockPtr))
    }
    return BtrfsInternal(children.size.toUInt().coerceAtLeast(0U), children.toSeries())
}
