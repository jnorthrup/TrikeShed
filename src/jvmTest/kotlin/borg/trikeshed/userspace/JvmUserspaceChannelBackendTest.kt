package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.nio.file.Files as UserFiles
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmUserspaceChannelBackendTest {
    @Test
    fun opened_file_fd_is_visible_to_common_ring_backend() {
        val path = java.nio.file.Files.createTempFile("trikeshed-uring-", ".bin")
        java.nio.file.Files.write(path, ByteArray(8))
        try {
            val file = UserFiles.open(path.toString(), readOnly = false)
            val channel = borg.trikeshed.userspace.nio.channels.UringChannels.open(entries = 8)
            val writeBuffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))

            channel.write(file, writeBuffer, offset = 2L, userData = 11L)
            assertEquals(1, channel.submit())
            assertEquals(listOf(SelectionResult(4, 11L)), channel.wait(minComplete = 1))

            val readBuffer = ByteBuffer.allocate(4)
            channel.read(file, readBuffer, offset = 2L, userData = 12L)
            assertEquals(1, channel.submit())
            assertEquals(listOf(SelectionResult(4, 12L)), channel.wait(minComplete = 1))
            assertContentEquals(byteArrayOf(1, 2, 3, 4), readBuffer.array())

            file.close()
            assertFalse(file.isOpen())
        } finally {
            java.nio.file.Files.deleteIfExists(path)
        }
    }

    private fun UserspaceChannelBackend.execute(submission: UringSubmission): Int {
        val result = submitBatch(listOf(submission)).single()
        assertEquals(submission.userData, result.userData)
        return result.res
    }

    @Test
    fun exclusiveCreationGrowthAndSlicedIoUseTheSubmittedBytes() {
        val root = Files.createTempDirectory("uring-backend-")
        val path = root.resolve("image")
        val backend = openUserspaceChannelBackend(8)
        try {
            val fd = backend.execute(Submissions.openat(path.toString(), 2 or 64 or 128, 1))
            assertTrue(fd > 0)
            assertEquals(0, backend.execute(UringSubmission(UringOp.FTRUNCATE, fd, 0, 0, 16, userData = 2)))
            assertEquals(16L, Files.size(path))
            val bytes = byteArrayOf(99, 10, 20, 30, 88)
            val source = ByteBuffer.wrap(bytes, 1, 3)
            assertEquals(2, backend.execute(Submissions.write(fd, 0, 2, 5, 3).copy(buffer = source)))
            assertEquals(2, source.position())
            assertEquals(0, backend.execute(Submissions.fsync(fd, 4)))
            val expected = ByteArray(16).also { it[5] = 10; it[6] = 20 }
            assertContentEquals(expected, Files.readAllBytes(path))
            assertEquals(-17, backend.execute(Submissions.openat(path.toString(), 2 or 64 or 128, 5)))
            assertContentEquals(expected, Files.readAllBytes(path))
            val target = byteArrayOf(7, 7, 7, 7, 7)
            val sliced = ByteBuffer.wrap(target, 1, 3)
            assertEquals(2, backend.execute(Submissions.read(fd, 0, 2, 5, 6).copy(buffer = sliced)))
            assertContentEquals(byteArrayOf(7, 10, 20, 7, 7), target)
            assertEquals(-22, backend.execute(Submissions.read(fd, 0, 1, 5, 7).copy(buffer = sliced.asReadOnlyBuffer())))
            assertEquals(0, backend.execute(Submissions.read(fd, 0, 1, 16, 8).copy(buffer = ByteBuffer(1))))
            assertEquals(0, backend.execute(Submissions.close(fd, 9)))
            assertEquals(-9, backend.execute(Submissions.close(fd, 10)))
        } finally {
            backend.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun backendDrainClosesOwnedDescriptorsButNotBorrowedFiles() {
        val path = Files.createTempFile("uring-owner-", ".bin")
        val backend = openUserspaceChannelBackend(8)
        val borrowed = UserFiles.open(path.toString(), readOnly = false)
        try {
            val fd = backend.execute(Submissions.openat(path.toString(), 2, 1))
            assertTrue(fd > 0 && fd != borrowed.id)
            val owned = File.fromFd(fd)
            assertTrue(owned.isOpen())
            assertEquals(0, backend.execute(Submissions.fsync(borrowed.id, 2)))
            backend.close()
            assertFalse(owned.isOpen())
            assertTrue(borrowed.isOpen())
            backend.close()
        } finally {
            borrowed.close()
            backend.close()
            Files.deleteIfExists(path)
        }
    }
}
