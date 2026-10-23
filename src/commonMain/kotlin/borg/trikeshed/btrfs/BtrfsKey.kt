package borg.trikeshed.btrfs

data class BtrfsKey(val objectid: ULong, val type: UByte, val offset: ULong) : Comparable<BtrfsKey> {
    override fun compareTo(other: BtrfsKey): Int = objectid.compareTo(other.objectid).takeIf { it != 0 }
        ?: type.compareTo(other.type).takeIf { it != 0 } ?: offset.compareTo(other.offset)
}

const val BTRFS_KEY_TYPE_CHUNK_ITEM: UByte = 228u
const val BTRFS_KEY_TYPE_DEV_ITEM: UByte = 216u
