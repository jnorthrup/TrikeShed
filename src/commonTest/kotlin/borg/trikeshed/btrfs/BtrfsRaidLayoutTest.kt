package borg.trikeshed.btrfs

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BtrfsRaidLayoutTest {
    @Test
    fun span_member_boundaries_and_capacity_snapshot() {
        val capacities = longArrayOf(3, 7, 2)
        val geometry = BtrfsRaidGeometry(BtrfsVolumeLayout.SPAN, capacities.size j { capacities[it] }, 4)
        capacities[0] = 200
        assertEquals(12, geometry.capacity)
        assertLocation(geometry, 0, 0, 0)
        assertLocation(geometry, 2, 0, 2)
        assertLocation(geometry, 3, 1, 0)
        assertLocation(geometry, 9, 1, 6)
        assertLocation(geometry, 10, 2, 0)
        assertLocation(geometry, 11, 2, 1)
        assertFalse(geometry.tolerates(setOf(1)))
        assertFailsWith<IllegalArgumentException> { geometry.locations(12) }
        assertFailsWith<IllegalArgumentException> { geometry.locations(-1) }
    }

    @Test
    fun dup_regions_do_not_overlap_or_claim_device_redundancy() {
        val geometry = BtrfsRaidGeometry(BtrfsVolumeLayout.DUP, s_[11L], 4)
        assertEquals(5, geometry.capacity)
        for (lba in 0L until geometry.capacity) {
            assertLocation(geometry, lba, 0, lba, 0)
            assertLocation(geometry, lba, 0, lba + 5, 1)
        }
        assertTrue(geometry.tolerates(emptySet()))
        assertFalse(geometry.tolerates(setOf(0)))
    }

    @Test
    fun mirror_and_raid10_failure_domains() {
        val mirrors = BtrfsRaidGeometry(BtrfsVolumeLayout.RAID1, s_[9L, 7L, 11L], 4)
        assertEquals(7, mirrors.capacity)
        assertEquals(3, mirrors.locations(6).size)
        for (member in 0..2) assertLocation(mirrors, 6, member, 6, member)
        assertTrue(mirrors.tolerates(setOf(0, 2)))
        assertFalse(mirrors.tolerates(setOf(0, 1, 2)))

        val striped = BtrfsRaidGeometry(BtrfsVolumeLayout.RAID10, s_[9L, 8L, 11L, 10L], 3)
        assertEquals(12, striped.capacity)
        assertLocation(striped, 2, 0, 2, 0)
        assertLocation(striped, 2, 1, 2, 1)
        assertLocation(striped, 3, 2, 0, 0)
        assertLocation(striped, 3, 3, 0, 1)
        assertLocation(striped, 6, 0, 3, 0)
        assertLocation(striped, 11, 3, 5, 1)
        assertTrue(striped.tolerates(setOf(0, 2)))
        assertTrue(striped.tolerates(setOf(1, 3)))
        assertFalse(striped.tolerates(setOf(0, 1)))
        assertFalse(striped.tolerates(setOf(2, 3)))
    }

    @Test
    fun stripe_mapping_uses_whole_stripes_and_has_no_aliases() {
        val geometry = BtrfsRaidGeometry(BtrfsVolumeLayout.RAID0, s_[11L, 9L, 14L], 4)
        assertEquals(24, geometry.capacity)
        assertLocation(geometry, 3, 0, 3)
        assertLocation(geometry, 4, 1, 0)
        assertLocation(geometry, 8, 2, 0)
        assertLocation(geometry, 12, 0, 4)
        assertLocation(geometry, 23, 2, 7)
        val occupied = mutableSetOf<Pair<Int, Long>>()
        for (lba in 0L until geometry.capacity) {
            val address = geometry.locations(lba)[0]
            assertTrue(occupied.add(address.a to address.b))
            assertTrue(address.b in 0L..7L)
        }
        assertFalse(geometry.tolerates(setOf(0)))
    }

    @Test
    fun parity_rotation_matches_upstream_data_p_q_order() {
        val geometry = BtrfsRaidGeometry(BtrfsVolumeLayout.RAID6, s_[16L, 16L, 16L, 16L, 16L], 2)
        val rows = arrayOf(
            intArrayOf(0, 1, 2, 3, 4),
            intArrayOf(1, 2, 3, 4, 0),
            intArrayOf(2, 3, 4, 0, 1),
            intArrayOf(3, 4, 0, 1, 2),
            intArrayOf(4, 0, 1, 2, 3),
            intArrayOf(0, 1, 2, 3, 4),
        )
        for (row in rows.indices) {
            for (lane in 0..2) {
                assertEquals(rows[row][lane], geometry.dataMember(row.toLong(), lane))
                assertLocation(geometry, row * 6L + lane * 2 + 1, rows[row][lane], row * 2L + 1)
            }
            assertEquals(rows[row][3], geometry.parityMembers(row.toLong())[0])
            assertEquals(rows[row][4], geometry.parityMembers(row.toLong())[1])
        }
        assertTrue(geometry.tolerates(setOf(1, 4)))
        assertFalse(geometry.tolerates(setOf(0, 1, 4)))
        val raid5 = BtrfsRaidGeometry(BtrfsVolumeLayout.RAID5, s_[8L, 8L, 8L], 2)
        assertEquals(2, raid5.parityMembers(0)[0])
        assertEquals(0, raid5.parityMembers(1)[0])
        assertLocation(raid5, 4, 1, 2)
    }

    @Test
    fun invalid_shapes_sizes_and_overflow_are_rejected() {
        assertFailsWith<IllegalArgumentException> { BtrfsRaidGeometry(BtrfsVolumeLayout.SPAN, s_[0L]) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidGeometry(BtrfsVolumeLayout.SPAN, s_[Long.MAX_VALUE, 1L]) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidGeometry(BtrfsVolumeLayout.RAID0, s_[Long.MAX_VALUE, Long.MAX_VALUE]) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidGeometry(BtrfsVolumeLayout.SINGLE, s_[1L, 1L]) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidGeometry(BtrfsVolumeLayout.DUP, s_[1L]) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidGeometry(BtrfsVolumeLayout.RAID0, s_[3L, 4L], 4) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidGeometry(BtrfsVolumeLayout.RAID10, s_[4L, 4L, 4L]) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidGeometry(BtrfsVolumeLayout.RAID6, s_[4L, 4L]) }
        assertFailsWith<IllegalArgumentException> { BtrfsRaidGeometry(BtrfsVolumeLayout.SINGLE, s_[4L], 0) }
        val single = BtrfsRaidGeometry(BtrfsVolumeLayout.SINGLE, s_[Long.MAX_VALUE], 2)
        assertLocation(single, Long.MAX_VALUE - 1, 0, Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE - 1, single.memberLba((Long.MAX_VALUE - 1) / 2, 0))
        assertFailsWith<IllegalArgumentException> { single.memberLba(Long.MAX_VALUE, 0) }
        assertFailsWith<IllegalArgumentException> { single.tolerates(setOf(1)) }
    }

    private fun assertLocation(geometry: BtrfsRaidGeometry, lba: Long, member: Int, block: Long, replica: Int = 0) {
        val location = geometry.locations(lba)[replica]
        assertEquals(member, location.a, "member for logical $lba replica $replica")
        assertEquals(block, location.b, "block for logical $lba replica $replica")
    }
}
