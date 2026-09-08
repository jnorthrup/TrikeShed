package borg.trikeshed.btrfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BtrfsImplementationStatusTest {

    @Test
    fun profile_bits_resolve_to_supported_profile_names() {
        assertEquals(BtrfsBlockGroupProfile.SINGLE, BtrfsBlockGroupProfile.fromBits(CHUNK_SINGLE))
        assertEquals(BtrfsBlockGroupProfile.DUP, BtrfsBlockGroupProfile.fromBits(CHUNK_DUP))
        assertEquals(BtrfsBlockGroupProfile.RAID0, BtrfsBlockGroupProfile.fromBits(CHUNK_RAID0))
        assertEquals(BtrfsBlockGroupProfile.RAID1, BtrfsBlockGroupProfile.fromBits(CHUNK_RAID1))
        assertEquals(BtrfsBlockGroupProfile.RAID10, BtrfsBlockGroupProfile.fromBits(CHUNK_RAID10))
        assertEquals(BtrfsBlockGroupProfile.RAID5, BtrfsBlockGroupProfile.fromBits(CHUNK_RAID5))
        assertEquals(BtrfsBlockGroupProfile.RAID6, BtrfsBlockGroupProfile.fromBits(CHUNK_RAID6))
    }

    @Test
    fun profile_validation_rejects_bad_stripe_shapes() {
        assertFailsWith<IllegalArgumentException> {
            BtrfsBlockGroupProfile.RAID0.validate(listOf(BtrfsStripe(1uL, 0uL)))
        }
        assertFailsWith<IllegalArgumentException> {
            BtrfsBlockGroupProfile.RAID10.validate(
                listOf(BtrfsStripe(1uL, 0uL), BtrfsStripe(2uL, 0uL), BtrfsStripe(3uL, 0uL)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BtrfsBlockGroupProfile.DUP.validate(listOf(BtrfsStripe(1uL, 0uL), BtrfsStripe(2uL, 0uL)))
        }
    }

    @Test
    fun status_matrix_keeps_btrfs_progs_oracle_visible() {
        val oracleGated = BtrfsImplementationStatus.offlineOracleRequired().map { it.capability }
        assertTrue(BtrfsCapability.MKFS_IMAGE in oracleGated)
        assertTrue(BtrfsCapability.CHECK_READ_ONLY in oracleGated)
        assertEquals(BtrfsSupportStatus.SKELETON, BtrfsImplementationStatus.statusOf(BtrfsCapability.MKFS_IMAGE).status)
        assertEquals(BtrfsSupportStatus.IMPLEMENTED, BtrfsImplementationStatus.statusOf(BtrfsCapability.URING_FILE_VOLUME).status)
    }
}
