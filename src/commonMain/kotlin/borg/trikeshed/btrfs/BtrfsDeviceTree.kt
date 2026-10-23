package borg.trikeshed.btrfs

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

object BtrfsDeviceTree {
    fun decode(bytes: ByteArray): BtrfsDevItem {
        require(bytes.size == 98) { "Invalid canonical Btrfs device item size" }
        return BtrfsDevItem(BtrfsDisk.u64(bytes, 0), BtrfsDisk.u64(bytes, 66),
            BtrfsDisk.u64(bytes, 8), BtrfsDisk.u64(bytes, 16), "",
            bytes.copyOfRange(66, 82).map(Byte::toUByte), bytes.copyOfRange(82, 98).map(Byte::toUByte), BtrfsDisk.u32(bytes, 32))
    }

    /** Device items reside in the chunk tree; device-tree extent items are a distinct record. */
    fun parse(bytes: ByteArray, offset: Int): List<Join<BtrfsKey, BtrfsDevItem>> =
        BtrfsDisk.leaf(bytes, offset).filter { it.first.type == BTRFS_KEY_TYPE_DEV_ITEM }
            .map { (key, value) -> key j decode(value) }
}
