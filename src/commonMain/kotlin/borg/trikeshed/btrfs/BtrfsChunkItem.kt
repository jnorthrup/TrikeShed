package borg.trikeshed.btrfs

/** c userspace btrfs block-group type bits (btrfs_block_group_flags). */
const val CHUNK_SINGLE: UByte = 0x0u
const val CHUNK_DUP: UByte = 0x01u
const val CHUNK_RAID0: UByte = 0x8u
const val CHUNK_RAID1: UByte = 0x10u
const val CHUNK_RAID10: UByte = 0x20u
const val CHUNK_RAID5: UByte = 0x40u
const val CHUNK_RAID6: UByte = 0x80u

data class BtrfsChunkItem(
    val stripeLength: ULong,
    val type: UByte,             // 0=RAID0, 1=RAID1, 2=SINGLE, 10=DUP
    val numStripes: UShort,
    val subStripes: UShort,
    val stripes: List<BtrfsStripe>,
)