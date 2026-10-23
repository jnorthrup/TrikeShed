package borg.trikeshed.btrfs

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

object BtrfsChunkTree {
    fun decode(bytes: ByteArray): BtrfsChunkItem {
        BtrfsDisk.range(bytes, 0, 48)
        val count = BtrfsDisk.u16(bytes, 44)
        require(count > 0 && bytes.size == 48 + count * 32) { "Invalid Btrfs chunk stripe count" }
        return BtrfsChunkItem(
            BtrfsDisk.u64(bytes, 16), BtrfsDisk.u64(bytes, 24), count.toUShort(), BtrfsDisk.u16(bytes, 46).toUShort(),
            List(count) { i ->
                val p = 48 + i * 32
                BtrfsStripe(BtrfsDisk.u64(bytes, p), BtrfsDisk.u64(bytes, p + 8), bytes.copyOfRange(p + 16, p + 32).map(Byte::toUByte))
            },
            BtrfsDisk.u64(bytes, 0), BtrfsDisk.u64(bytes, 8), BtrfsDisk.u32(bytes, 40),
        )
    }

    /** A chunk-tree leaf also contains device items; only chunk items are returned. */
    fun parse(bytes: ByteArray, offset: Int): List<Join<BtrfsKey, BtrfsChunkItem>> =
        BtrfsDisk.leaf(bytes, offset).filter { it.first.type == BTRFS_KEY_TYPE_CHUNK_ITEM }
            .map { (key, value) -> key j decode(value) }
}
