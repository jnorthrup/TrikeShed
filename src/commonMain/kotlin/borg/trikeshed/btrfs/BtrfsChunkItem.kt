package borg.trikeshed.btrfs

/** c userspace btrfs block-group type bits (btrfs_block_group_flags). */
const val CHUNK_SINGLE: ULong = 0x0u
const val CHUNK_DUP: ULong = 0x20u
const val CHUNK_RAID0: ULong = 0x8u
const val CHUNK_RAID1: ULong = 0x10u
const val CHUNK_RAID10: ULong = 0x40u
const val CHUNK_RAID5: ULong = 0x80u
const val CHUNK_RAID6: ULong = 0x100u

data class BtrfsChunkItem(
    val stripeLength: ULong,
    val type: ULong,
    val numStripes: UShort,
    val subStripes: UShort,
    val stripes: List<BtrfsStripe>,
    val length: ULong = stripeLength,
    val owner: ULong = 2uL,
    val sectorSize: UInt = 4096u,
)
