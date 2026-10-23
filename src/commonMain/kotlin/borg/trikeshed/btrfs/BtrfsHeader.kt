package borg.trikeshed.btrfs

sealed class BtrfsHeader {
    object Super : BtrfsHeader()
    object ChunkItem : BtrfsHeader()
    object DevItem : BtrfsHeader()
    data class Unknown(val type: UByte) : BtrfsHeader()
}

/** Canonical little-endian disk primitives. Bounds are checked before decoding untrusted records. */
internal object BtrfsDisk {
    const val HEADER = 101
    fun range(bytes: ByteArray, offset: Int, size: Int) {
        require(offset >= 0 && size >= 0 && offset <= bytes.size - size) { "Truncated Btrfs record" }
    }
    fun u64(b: ByteArray, p: Int): ULong {
        range(b, p, 8)
        return (0..7).fold(0uL) { v, i -> v or ((b[p + i].toULong() and 255u) shl (8 * i)) }
    }
    fun u32(b: ByteArray, p: Int): UInt {
        range(b, p, 4)
        return (0..3).fold(0u) { v, i -> v or ((b[p + i].toUInt() and 255u) shl (8 * i)) }
    }
    fun u16(b: ByteArray, p: Int): Int {
        range(b, p, 2)
        return (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
    }
    fun put(b: ByteArray, p: Int, value: ULong, size: Int = 8) {
        range(b, p, size)
        for (i in 0 until size) b[p + i] = (value shr (8 * i)).toByte()
    }
    private val crcTable = IntArray(256) { n ->
        var crc = n
        repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0x82f63b78.toInt() else 0) }
        crc
    }
    fun crc(bytes: ByteArray, offset: Int = 0, size: Int = bytes.size - offset, seed: Int = -1): Int {
        range(bytes, offset, size)
        var crc = seed
        for (i in offset until offset + size) crc = crcTable[(crc xor bytes[i].toInt()) and 255] xor (crc ushr 8)
        return crc
    }
    fun checksum(bytes: ByteArray) { put(bytes, 0, crc(bytes, 32).inv().toUInt().toULong(), 4) }
    fun check(bytes: ByteArray) {
        range(bytes, 0, 32)
        require(u32(bytes, 0) == crc(bytes, 32).inv().toUInt()) { "Btrfs CRC32C mismatch" }
    }
    fun key(b: ByteArray, p: Int): BtrfsKey = BtrfsKey(u64(b, p), b[p + 8].toUByte(), u64(b, p + 9))
    fun key(b: ByteArray, p: Int, key: BtrfsKey) {
        put(b, p, key.objectid); b[p + 8] = key.type.toByte(); put(b, p + 9, key.offset)
    }
    fun leaf(bytes: ByteArray, offset: Int = 0): List<Pair<BtrfsKey, ByteArray>> {
        range(bytes, offset, HEADER)
        val count = u32(bytes, offset + 96).toLong()
        require(bytes[offset + 100] == 0.toByte()) { "Expected a Btrfs leaf" }
        require(count <= (bytes.size - offset - HEADER) / 25) { "Btrfs leaf item count exceeds block" }
        val records = ArrayList<Pair<BtrfsKey, ByteArray>>(count.toInt())
        var previous: BtrfsKey? = null
        var dataEnd = bytes.size - offset - HEADER
        for (i in 0 until count.toInt()) {
            val p = offset + HEADER + i * 25
            val key = key(bytes, p)
            val start = u32(bytes, p + 17).toLong()
            val size = u32(bytes, p + 21).toLong()
            require(start >= count * 25 && start + size <= dataEnd) { "Overlapping or out-of-bounds Btrfs item" }
            require(previous == null || previous < key) { "Unordered Btrfs leaf keys" }
            records.add(key to bytes.copyOfRange(offset + HEADER + start.toInt(), offset + HEADER + (start + size).toInt()))
            dataEnd = start.toInt()
            previous = key
        }
        return records
    }
}
