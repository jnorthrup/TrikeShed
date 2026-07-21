package borg.trikeshed.btrfs

data class BtrfsChunkItem(
    val stripeLength: ULong,
    val type: UByte,             // 0=RAID0, 1=RAID1, 2=SINGLE, 10=DUP
    val numStripes: UShort,
    val subStripes: UShort,
    val stripes: List<BtrfsStripe>,
)