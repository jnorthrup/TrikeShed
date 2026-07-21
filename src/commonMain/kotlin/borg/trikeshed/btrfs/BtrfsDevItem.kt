package borg.trikeshed.btrfs

data class BtrfsDevItem(
    val devid: ULong,
    val uuid: ULong,
    val size: ULong,
    val bytesUsed: ULong,
    val path: String
)