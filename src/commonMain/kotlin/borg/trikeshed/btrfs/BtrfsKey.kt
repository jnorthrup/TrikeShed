package borg.trikeshed.btrfs

data class BtrfsKey(val objectid: ULong, val type: UByte, val offset: ULong)

const val BTRFS_KEY_TYPE_CHUNK_ITEM: UByte = 228u
const val BTRFS_KEY_TYPE_DEV_ITEM: UByte = 216u