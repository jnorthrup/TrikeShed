package borg.trikeshed.btrfs

/** The 4096-byte canonical superblock, excluding the containing device offset. */
data class BtrfsSuperblock(
    val bytenr: ULong,
    val flags: ULong,
    val magic: ULong,
    val generation: ULong,
    val root: ULong,
    val chunkRoot: ULong,
    val totalBytes: ULong,
    val bytesUsed: ULong,
    val fsid: List<UByte> = List(16) { 0u },
    val sectorSize: UInt = 4096u,
    val nodeSize: UInt = 16384u,
    val numDevices: ULong = 1u,
    val incompatFlags: ULong = 0u,
    val compatRoFlags: ULong = 0u,
    val rootLevel: Int = 0,
    val chunkRootLevel: Int = 0,
    val deviceId: ULong = 1u,
    val label: String = "",
    val systemChunks: ByteArray = ByteArray(0),
    val metadataUuid: List<UByte> = fsid,
    val logRoot: ULong = 0u,
    val logRootTransid: ULong = 0u,
) {
    companion object {
        fun parse(buf: ByteArray): BtrfsSuperblock {
            BtrfsDisk.range(buf, 0, 4096)
            require(BtrfsDisk.u64(buf, 64) == BTRFS_MAGIC) { "Not a canonical Btrfs superblock" }
            require(BtrfsDisk.u16(buf, 196) == 0) { "Unsupported Btrfs checksum type (CRC32C required)" }
            BtrfsDisk.check(buf.copyOf(4096))
            val sector = BtrfsDisk.u32(buf, 144)
            val node = BtrfsDisk.u32(buf, 148)
            require(sector >= 4096u && sector.countOneBits() == 1 && node >= sector && node <= 65536u && node.countOneBits() == 1) {
                "Unsupported Btrfs sector/node size"
            }
            val size = BtrfsDisk.u32(buf, 160)
            require(size <= 2048u) { "Invalid Btrfs system chunk array" }
            val fsid = buf.copyOfRange(32, 48).map(Byte::toUByte)
            val incompat = BtrfsDisk.u64(buf, 188)
            val labelBytes = buf.copyOfRange(299, 555)
            val end = labelBytes.indexOf(0).let { if (it < 0) labelBytes.size else it }
            return BtrfsSuperblock(
                BtrfsDisk.u64(buf, 48), BtrfsDisk.u64(buf, 56), BTRFS_MAGIC,
                BtrfsDisk.u64(buf, 72), BtrfsDisk.u64(buf, 80), BtrfsDisk.u64(buf, 88),
                BtrfsDisk.u64(buf, 112), BtrfsDisk.u64(buf, 120), fsid, sector, node,
                BtrfsDisk.u64(buf, 136), incompat, BtrfsDisk.u64(buf, 180),
                buf[198].toInt() and 255, buf[199].toInt() and 255, BtrfsDisk.u64(buf, 201),
                labelBytes.decodeToString(0, end, throwOnInvalidSequence = true), buf.copyOfRange(811, 811 + size.toInt()),
                if (incompat and 1024uL != 0uL) buf.copyOfRange(571, 587).map(Byte::toUByte) else fsid,
                BtrfsDisk.u64(buf, 96), BtrfsDisk.u64(buf, 104),
            )
        }
    }
}
