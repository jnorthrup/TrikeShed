package borg.trikeshed.btrfs

import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.ebpf.UringEbpfContextLayout
import borg.trikeshed.userspace.nio.ebpf.UringEbpfInsn
import borg.trikeshed.userspace.nio.ebpf.UringEbpfPhase
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import kotlinx.coroutines.test.runTest
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BtrfsImageIoTest {

    @Test
    fun seed_image_superblocks_move_through_common_uring_volume() = runTest {
        val path = Files.createTempFile("trikeshed-btrfs-seed-", ".img")
        val total = 128uL * 1024uL * 1024uL
        try {
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(total.toLong()) }
            val receipt = BtrfsImageIo.writeSeedImage(path.toString(), total)

            assertEquals("jvm_nio", receipt.backend.backendName)
            assertFalse(receipt.backend.ioUringAvailable)
            assertTrue(receipt.wroteMirror)
            assertEquals(2, receipt.superblocks.size)
            assertEquals(BtrfsSeedImageLayout.requiredBytes(total), receipt.requiredBytes)
            assertTrue(receipt.io.any {
                it.opcode == UringOp.WRITE &&
                    it.byteOffset == NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY &&
                    it.res == NioBtrfsGraalBlobStore.SUPER_SIZE
            })
            assertTrue(receipt.io.any {
                it.opcode == UringOp.WRITE &&
                    it.byteOffset == NioBtrfsGraalBlobStore.SUPER_OFFSET_MIRRORS &&
                    it.res == NioBtrfsGraalBlobStore.SUPER_SIZE
            })
            assertTrue(receipt.io.any { it.opcode == UringOp.READ && it.res == NioBtrfsGraalBlobStore.SUPER_SIZE })
            assertTrue(receipt.io.any { it.opcode == UringOp.CLOSE && it.res == 0 })

            RandomAccessFile(path.toFile(), "r").use { file ->
                val primary = ByteArray(NioBtrfsGraalBlobStore.SUPER_SIZE)
                file.seek(NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY)
                file.readFully(primary)
                assertEquals(BTRFS_MAGIC, BtrfsSuperblock.parse(primary).magic)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun submit_ebpf_rejection_blocks_seed_image_write_before_backend_io() = runTest {
        val path = Files.createTempFile("trikeshed-btrfs-ebpf-", ".img")
        val total = 1uL * 1024uL * 1024uL
        val rejectWrites = UringEbpfProgram.of(
            name = "reject-seed-writes",
            phase = UringEbpfPhase.SUBMIT,
            instructions = longArrayOf(
                UringEbpfInsn.loadCtx32(UringEbpfInsn.R0, UringEbpfContextLayout.OPCODE),
                UringEbpfInsn.jne64Imm(UringEbpfInsn.R0, UringOp.WRITE.code, offset = 2),
                UringEbpfInsn.mov64Imm(UringEbpfInsn.R0, -13),
                UringEbpfInsn.exit(),
                UringEbpfInsn.mov64Imm(UringEbpfInsn.R0, 0),
                UringEbpfInsn.exit(),
            ),
        )
        try {
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(total.toLong()) }
            val failure = assertFailsWith<BtrfsImageIoException> {
                BtrfsImageIo.writeSeedImage(path.toString(), total, ebpfPrograms = listOf(rejectWrites))
            }
            assertTrue(failure.completions.any {
                it.opcode == UringOp.WRITE &&
                    it.submitted == 0 &&
                    it.res == -13
            })
            RandomAccessFile(path.toFile(), "r").use { file ->
                val primary = ByteArray(NioBtrfsGraalBlobStore.SUPER_SIZE)
                file.seek(NioBtrfsGraalBlobStore.SUPER_OFFSET_PRIMARY)
                file.readFully(primary)
                assertFailsWith<IllegalArgumentException> { BtrfsSuperblock.parse(primary) }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
