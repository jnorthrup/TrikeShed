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
        val matrix = BtrfsImplementationStatus.matrix
        assertEquals(BtrfsCapability.entries.toSet(), matrix.map { it.capability }.toSet())
        assertEquals(BtrfsCapability.entries.size, matrix.size)
        for (capability in listOf(BtrfsCapability.MKFS_IMAGE, BtrfsCapability.CHECK_READ_ONLY)) {
            val status = BtrfsImplementationStatus.statusOf(capability)
            assertEquals(BtrfsSupportStatus.IMPLEMENTED, status.status)
            assertEquals(BtrfsOracleKind.BTRFS_PROGS_OFFLINE, status.oracle)
            assertTrue(status.evidence.contains("BtrfsFilesystemOracleTest"))
        }
        assertEquals(BtrfsSupportStatus.MISSING, BtrfsImplementationStatus.statusOf(BtrfsCapability.BALANCE).status)
        assertEquals(BtrfsSupportStatus.IMPLEMENTED, BtrfsImplementationStatus.statusOf(BtrfsCapability.USERSPACE_REBALANCE).status)
        assertEquals(BtrfsSupportStatus.UNVERIFIED, BtrfsImplementationStatus.statusOf(BtrfsCapability.MACOS_FUSE_MOUNT).status)
    }
}
