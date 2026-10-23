package borg.trikeshed.btrfs

data class BtrfsDevItem(
    val devid: ULong,
    val uuid: ULong,
    val size: ULong,
    val bytesUsed: ULong,
    val path: String = "",
    val uuidBytes: List<UByte> = List(16) { 0u },
    val fsid: List<UByte> = List(16) { 0u },
    val sectorSize: UInt = 4096u,
)
