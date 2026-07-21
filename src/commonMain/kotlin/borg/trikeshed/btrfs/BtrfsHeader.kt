package borg.trikeshed.btrfs

sealed class BtrfsHeader {
    object Super : BtrfsHeader()
    object ChunkItem : BtrfsHeader()
    object DevItem : BtrfsHeader()
    data class Unknown(val type: UByte) : BtrfsHeader()
}