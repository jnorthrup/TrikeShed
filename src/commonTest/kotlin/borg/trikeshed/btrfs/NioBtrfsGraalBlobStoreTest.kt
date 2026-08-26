package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NioBtrfsGraalBlobStoreTest {

    // ── writeMountableImage → BtrfsSuperblock.parse round trip ──────────────

    @Test
    fun mountableImageSuperblockRoundTrips() {
        val fileOps = InMemoryFileOperations()
        val img = "/mem/vol/btrfs.img"
        val total = 128uL * 1024uL * 1024uL // 128 MiB — big enough for the 64MiB mirror
        assertTrue(NioBtrfsGraalBlobStore.writeMountableImage(img, total, fileOps))

        val bytes = fileOps.readAllBytes(img)
        assertEquals((NioBtrfsGraalBlobStore.SUPER_OFFSET_MIRRORS + NioBtrfsGraalBlobStore.SUPER_SIZE).toInt(), bytes.size)

        val sb = BtrfsSuperblock.parse(bytes.copyOfRange(
            NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY.toInt(),
            NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY.toInt() + NioBtrfsGraalBlobStore.SUPER_SIZE,
        ))
        assertEquals(BTRFS_MAGIC, sb.magic)
        assertEquals(NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY.toULong(), sb.bytenr)
        assertEquals(total, sb.totalBytes)
        assertEquals(1uL, sb.generation)

        // mirror at 64MiB carries its own bytenr and the same magic/totalBytes
        val mirror = BtrfsSuperblock.parse(bytes.copyOfRange(
            NioBtrfsGraalBlobStore.SUPER_OFFSET_MIRRORS.toInt(),
            NioBtrfsGraalBlobStore.SUPER_OFFSET_MIRRORS.toInt() + NioBtrfsGraalBlobStore.SUPER_SIZE,
        ))
        assertEquals(BTRFS_MAGIC, mirror.magic)
        assertEquals(NioBtrfsGraalBlobStore.SUPER_OFFSET_MIRRORS.toULong(), mirror.bytenr)
        assertEquals(total, mirror.totalBytes)
    }

    @Test
    fun smallImageSkipsMirror() {
        val fileOps = InMemoryFileOperations()
        val img = "/mem/vol/small.img"
        val total = 1uL * 1024uL * 1024uL // 1 MiB — no room for the 64MiB mirror
        assertTrue(NioBtrfsGraalBlobStore.writeMountableImage(img, total, fileOps))
        val bytes = fileOps.readAllBytes(img)
        assertEquals((NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY + NioBtrfsGraalBlobStore.SUPER_SIZE).toInt(), bytes.size)
        val sb = BtrfsSuperblock.parse(bytes.copyOfRange(
            NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY.toInt(),
            NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY.toInt() + NioBtrfsGraalBlobStore.SUPER_SIZE,
        ))
        assertEquals(total, sb.totalBytes)
    }

    @Test
    fun imageTooSmallForSuperblockFails() {
        val fileOps = InMemoryFileOperations()
        assertFailsWith<IllegalArgumentException> {
            NioBtrfsGraalBlobStore.writeMountableImage("/mem/vol/tiny.img", 4096uL, fileOps)
        }
    }

    // ── allocateChunk / stripeForRaid — c parity type bits ──────────────────

    @Test
    fun allocateChunkRaidParity() {
        val s = NioBtrfsGraalBlobStore

        val single = s.allocateChunk(0uL, 8uL * 1024uL * 1024uL, CHUNK_SINGLE, s.stripeForRaid(CHUNK_SINGLE, listOf(1uL)))
        assertEquals(1u.toUShort(), single.numStripes)
        assertEquals(CHUNK_SINGLE, single.type)

        val dup = s.allocateChunk(0uL, 8uL * 1024uL * 1024uL, CHUNK_DUP, s.stripeForRaid(CHUNK_DUP, listOf(1uL)))
        assertEquals(2u.toUShort(), dup.numStripes)
        assertEquals(dup.stripes[0].devid, dup.stripes[1].devid)

        val raid1 = s.allocateChunk(0uL, 8uL * 1024uL * 1024uL, CHUNK_RAID1, s.stripeForRaid(CHUNK_RAID1, listOf(1uL, 2uL)))
        assertEquals(2u.toUShort(), raid1.numStripes)

        val raid0 = s.allocateChunk(0uL, 64uL * 1024uL, CHUNK_RAID0, s.stripeForRaid(CHUNK_RAID0, listOf(1uL, 2uL, 3uL)))
        assertEquals(3u.toUShort(), raid0.numStripes)

        val raid10 = s.allocateChunk(0uL, 64uL * 1024uL, CHUNK_RAID10, s.stripeForRaid(CHUNK_RAID10, listOf(1uL, 2uL, 3uL, 4uL)))
        assertEquals(4u.toUShort(), raid10.numStripes)
        assertEquals(2u.toUShort(), raid10.subStripes)

        val raid5 = s.allocateChunk(0uL, 64uL * 1024uL, CHUNK_RAID5, s.stripeForRaid(CHUNK_RAID5, listOf(1uL, 2uL, 3uL)))
        assertEquals(3u.toUShort(), raid5.numStripes)

        val raid6 = s.allocateChunk(0uL, 64uL * 1024uL, CHUNK_RAID6, s.stripeForRaid(CHUNK_RAID6, listOf(1uL, 2uL, 3uL, 4uL)))
        assertEquals(4u.toUShort(), raid6.numStripes)
    }

    @Test
    fun allocateChunkRejectsBadShapes() {
        val s = NioBtrfsGraalBlobStore
        assertFailsWith<IllegalArgumentException> { s.allocateChunk(0uL, 4096uL, CHUNK_RAID1, listOf(BtrfsStripe(1uL, 0uL))) }
        assertFailsWith<IllegalArgumentException> { s.allocateChunk(0uL, 4096uL, CHUNK_RAID10, s.stripeForRaid(CHUNK_RAID10, listOf(1uL, 2uL))) }
        assertFailsWith<IllegalArgumentException> { s.allocateChunk(0uL, 4096uL, CHUNK_RAID6, s.stripeForRaid(CHUNK_RAID6, listOf(1uL, 2uL))) }
        assertFailsWith<IllegalArgumentException> { s.allocateChunk(0uL, 4096uL, 0x07u, listOf(BtrfsStripe(1uL, 0uL))) }
        assertFailsWith<IllegalArgumentException> { s.stripeForRaid(CHUNK_RAID0, emptyList()) }
    }

    // ── growBlob — CoW append over UserspaceBtrfs ───────────────────────────

    @Test
    fun growBlobAppendsCoW() {
        val fileOps = InMemoryFileOperations()
        val root = "/mem/vol"
        val btrfs = UserspaceBtrfs(root, fileOps)
        assertTrue(btrfs.createSubvolume("models"))
        assertTrue(btrfs.writeFile("models", "weights.gguf", "GGUF-HDR".encodeToByteArray()))

        assertTrue(NioBtrfsGraalBlobStore.growBlob(root, "models", "weights.gguf", "+TENSOR0".encodeToByteArray(), fileOps))
        assertTrue(NioBtrfsGraalBlobStore.growBlob(root, "models", "weights.gguf", "+TENSOR1".encodeToByteArray(), fileOps))

        // fresh mount sees the grown blob
        val remount = UserspaceBtrfs(root, fileOps)
        assertEquals("GGUF-HDR+TENSOR0+TENSOR1", remount.fetchFile("models", "weights.gguf")?.decodeToString())

        // grow into a missing subvolume fails closed
        assertFalse(NioBtrfsGraalBlobStore.growBlob(root, "nope", "x", ByteArray(1), fileOps))
    }
}
