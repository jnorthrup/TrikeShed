package borg.trikeshed.btrfs

data class BtrfsSuperblock(
    val bytenr: ULong,
    val flags: ULong,
    val magic: ULong,
    val generation: ULong,
    val root: ULong,
    val chunkRoot: ULong,
    val totalBytes: ULong,
    val bytesUsed: ULong,
) {
    companion object {
        private fun readULongLE(buf: ByteArray, offset: Int): ULong {
            var result = 0uL
            for (i in 0..7) {
                result = result or ((buf[offset + i].toULong() and 0xFFuL) shl (i * 8))
            }
            return result
        }

        fun parse(buf: ByteArray): BtrfsSuperblock {
            require(buf.size >= 4096) { "superblock buffer must be >= 4096 bytes (got ${buf.size})" }
            val magic = readULongLE(buf, 16)
            require(magic == BTRFS_MAGIC) {
                "bad magic 0x${magic.toString(16)}; expected 0x${BTRFS_MAGIC.toString(16)}"
            }
            return BtrfsSuperblock(
                bytenr       = readULongLE(buf, 0),
                flags        = readULongLE(buf, 8),
                magic        = magic,
                generation   = readULongLE(buf, 24),
                root         = readULongLE(buf, 32),
                chunkRoot    = readULongLE(buf, 40),
                totalBytes   = readULongLE(buf, 48),
                bytesUsed    = readULongLE(buf, 56),
            )
        }
    }
}
