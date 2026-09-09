package borg.trikeshed.btrfs

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/** Userspace block-volume layouts; these do not serialize canonical Btrfs chunk trees. */
enum class BtrfsVolumeLayout { SPAN, SINGLE, DUP, RAID0, RAID1, RAID10, RAID5, RAID6 }

/**
 * Immutable block addressing over member capacities, measured in blocks.
 *
 * SPAN concatenates unequal members; SINGLE addresses one member; DUP places two
 * copies in disjoint halves of one member. RAID1 mirrors every supplied member.
 * RAID10 stripes over adjacent replica pairs. Striped layouts use whole stripes
 * of the smallest member; unused tails are not part of the address space.
 *
 * RAID56 rotates data, P, Q together by one member per full stripe, following
 * `map_blocks_raid56_read` in Linux v6.12 fs/btrfs/volumes.c:
 * https://github.com/torvalds/linux/blob/v6.12/fs/btrfs/volumes.c
 * This is a common userspace layout, not a complete Btrfs on-disk filesystem.
 */
class BtrfsRaidGeometry(
    val layout: BtrfsVolumeLayout,
    capacities: Series<Long>,
    val stripeBlocks: Int = 1,
) {
    val memberCount: Int = capacities.size
    private val memberCapacities: LongArray
    val dataCount: Int
    val parityCount: Int
    val capacity: Long
    private val memberBlocks: Long

    init {
        require(memberCount > 0) { "A volume requires members" }
        require(stripeBlocks > 0) { "Stripe size must be positive" }
        memberCapacities = LongArray(memberCount) { index ->
            capacities[index].also { require(it > 0) { "Member capacity must be positive" } }
        }
        when (layout) {
            BtrfsVolumeLayout.SINGLE, BtrfsVolumeLayout.DUP ->
                require(memberCount == 1) { "$layout requires exactly one member" }
            BtrfsVolumeLayout.RAID0, BtrfsVolumeLayout.RAID1 ->
                require(memberCount >= 2) { "$layout requires at least two members" }
            BtrfsVolumeLayout.RAID5 ->
                require(memberCount in 2..256) { "RAID5 requires 2..256 members" }
            BtrfsVolumeLayout.RAID10 ->
                require(memberCount >= 4 && memberCount % 2 == 0) { "RAID10 requires adjacent pairs of at least four members" }
            BtrfsVolumeLayout.RAID6 ->
                require(memberCount >= 3 && memberCount <= 257) { "RAID6 requires 3..257 members" }
            BtrfsVolumeLayout.SPAN -> Unit
        }
        parityCount = when (layout) {
            BtrfsVolumeLayout.RAID5 -> 1
            BtrfsVolumeLayout.RAID6 -> 2
            else -> 0
        }
        dataCount = when (layout) {
            BtrfsVolumeLayout.RAID0 -> memberCount
            BtrfsVolumeLayout.RAID10 -> memberCount / 2
            BtrfsVolumeLayout.RAID5, BtrfsVolumeLayout.RAID6 -> memberCount - parityCount
            else -> 1
        }
        val minimum = memberCapacities.minOrNull()!!
        memberBlocks = when (layout) {
            BtrfsVolumeLayout.SPAN -> memberCapacities.maxOrNull()!!
            BtrfsVolumeLayout.DUP -> minimum / 2
            BtrfsVolumeLayout.RAID0, BtrfsVolumeLayout.RAID10,
            BtrfsVolumeLayout.RAID5, BtrfsVolumeLayout.RAID6 -> minimum / stripeBlocks * stripeBlocks
            else -> minimum
        }
        require(memberBlocks > 0) { "Members cannot hold the layout's minimum allocation" }
        capacity = if (layout == BtrfsVolumeLayout.SPAN) {
            memberCapacities.fold(0L) { sum, blocks ->
                require(sum <= Long.MAX_VALUE - blocks) { "Spanned capacity overflows Long" }
                sum + blocks
            }
        } else {
            require(memberBlocks <= Long.MAX_VALUE / dataCount) { "Striped capacity overflows Long" }
            memberBlocks * dataCount
        }
    }

    /** Physical member and block for each replica of a logical block. */
    fun locations(lba: Long): Series<Join<Int, Long>> {
        requireLba(lba)
        return when (layout) {
            BtrfsVolumeLayout.SPAN -> {
                var offset = lba
                var member = 0
                while (offset >= memberCapacities[member]) {
                    offset -= memberCapacities[member++]
                }
                val address = member j offset
                1 j { index: Int -> require(index == 0); address }
            }
            BtrfsVolumeLayout.SINGLE -> 1 j { index: Int -> require(index == 0); 0 j lba }
            BtrfsVolumeLayout.DUP -> 2 j { index: Int ->
                require(index in 0..1)
                0 j (lba + index * capacity)
            }
            BtrfsVolumeLayout.RAID1 -> memberCount j { index: Int ->
                require(index in 0 until memberCount)
                index j lba
            }
            else -> {
                val row = row(lba)
                val member = dataMember(row, ((lba / stripeBlocks) % dataCount).toInt())
                val block = memberLba(row, blockInStripe(lba))
                val copies = if (layout == BtrfsVolumeLayout.RAID10) 2 else 1
                copies j { index: Int ->
                    require(index in 0 until copies)
                    (member + index) j block
                }
            }
        }
    }

    /** Full stripe row; SPAN/SINGLE/DUP/RAID1 have one logical data lane. */
    fun row(lba: Long): Long {
        requireLba(lba)
        return lba / stripeBlocks / dataCount
    }

    fun blockInStripe(lba: Long): Int {
        requireLba(lba)
        return (lba % stripeBlocks).toInt()
    }

    /** First replica member of a data lane; SPAN must use [locations] instead. */
    fun dataMember(row: Long, dataIndex: Int): Int {
        require(row >= 0) { "Negative stripe row" }
        require(dataIndex in 0 until dataCount) { "Data lane outside layout" }
        require(layout != BtrfsVolumeLayout.SPAN) { "SPAN has variable member boundaries; use locations" }
        return when (layout) {
            BtrfsVolumeLayout.RAID10 -> dataIndex * 2
            BtrfsVolumeLayout.RAID5, BtrfsVolumeLayout.RAID6 ->
                ((row % memberCount + dataIndex) % memberCount).toInt()
            else -> dataIndex
        }
    }

    /** P first, then Q. Empty for non-parity layouts. */
    fun parityMembers(row: Long): Series<Int> {
        require(row >= 0) { "Negative stripe row" }
        return parityCount j { index: Int ->
            require(index in 0 until parityCount) { "Parity lane outside layout" }
            ((row % memberCount + dataCount + index) % memberCount).toInt()
        }
    }

    /** Physical stripe block; SPAN must use [locations] instead. */
    fun memberLba(row: Long, blockInStripe: Int): Long {
        require(layout != BtrfsVolumeLayout.SPAN) { "SPAN has variable member boundaries; use locations" }
        require(row >= 0 && row <= Long.MAX_VALUE / stripeBlocks) { "Stripe row outside address space" }
        require(blockInStripe in 0 until stripeBlocks) { "Block outside stripe" }
        val start = row * stripeBlocks
        require(start < memberBlocks && blockInStripe < memberBlocks - start) { "Member block outside layout" }
        return start + blockInStripe
    }

    /** Whether every logical block remains recoverable with these member erasures. */
    fun tolerates(unavailable: Set<Int>): Boolean {
        require(unavailable.all { it in 0 until memberCount }) { "Unknown unavailable member" }
        return when (layout) {
            BtrfsVolumeLayout.RAID1 -> unavailable.size < memberCount
            BtrfsVolumeLayout.RAID10 -> (0 until memberCount step 2).all { it !in unavailable || it + 1 !in unavailable }
            BtrfsVolumeLayout.RAID5, BtrfsVolumeLayout.RAID6 -> unavailable.size <= parityCount
            else -> unavailable.isEmpty()
        }
    }

    private fun requireLba(lba: Long) {
        require(lba >= 0 && lba < capacity) { "Logical block outside volume" }
    }
}
