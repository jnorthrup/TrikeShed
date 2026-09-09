@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.PosixUringIO
import borg.trikeshed.common.createTempDirectory
import borg.trikeshed.lib.get
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import kotlinx.coroutines.test.runTest
import platform.posix.ENAMETOOLONG
import platform.posix.O_RDWR
import platform.posix.PATH_MAX
import platform.posix.close
import platform.posix.open
import platform.posix.remove
import platform.posix.rmdir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PosixUserspaceChannelBackendTest {
    @Test
    fun scoped_io_honors_path_and_transfer_views_lengths_and_positions() = runTest {
        val directory = createTempDirectory("uring-posix")
        val path = "$directory/image"
        val facade = FunctionalUringFacade.create(this, 8, openUserspaceChannelBackend(8))
        suspend fun submit(submission: UringSubmission): Int =
            facade.batchEnqueue(listOf(submission).toSeries())[0].res
        try {
            val pathBytes = path.encodeToByteArray()
            val pathBuffer = ByteBuffer(byteArrayOf(9, 9) + pathBytes + byteArrayOf(9), 1, pathBytes.size + 2).position(1)
            val fd = submit(Submissions.openat(path, 2 or 64 or 128, 1).copy(buffer = pathBuffer))
            assertTrue(fd >= 0)
            assertEquals(1, pathBuffer.position())

            val source = ByteBuffer(byteArrayOf(9, 1, 2, 3, 4, 9), 1, 4).position(1)
            assertEquals(2, submit(Submissions.write(fd, 0, 2, 0, 2).copy(buffer = source)))
            assertEquals(3, source.position())
            assertEquals(4, source.limit())
            val targetBytes = ByteArray(8) { 9 }
            val target = ByteBuffer(targetBytes, 2, 5).position(1)
            assertEquals(2, submit(Submissions.read(fd, 0, 3, 0, 3).copy(buffer = target)))
            assertEquals(3, target.position())
            assertContentEquals(byteArrayOf(9, 9, 9, 2, 3, 9, 9, 9), targetBytes)
            assertEquals(0, submit(Submissions.read(fd, 0, 1, 2, 4).copy(buffer = target)))
            assertEquals(0, submit(Submissions.read(fd, 0, 0, 0, 5).copy(buffer = target)))
            assertEquals(3, target.position())
            assertEquals(0, submit(Submissions.fsync(fd, 6)))
            assertEquals(0, submit(UringSubmission(UringOp.FTRUNCATE, fd, 0, 0, 1, userData = 7)))
            assertEquals(1L, PosixUringIO.fileSize(fd))

            facade.drain()
            assertEquals(-1L, PosixUringIO.fileSize(fd))
        } finally {
            try { facade.drain() } finally { remove(path); rmdir(directory) }
        }
    }

    @Test
    fun malformed_submissions_keep_one_correlated_completion_and_do_not_stop_the_batch() {
        val directory = createTempDirectory("uring-posix-invalid")
        val path = "$directory/image"
        val backend = openUserspaceChannelBackend(8)
        try {
            val opening = Submissions.openat(path, 2 or 64)
            val reading = Submissions.read(Int.MAX_VALUE, 0, 1, 0, 0)
            val submissions = listOf(
                reading,
                reading.copy(opcode = UringOp.WRITE),
                reading.copy(len = -1, buffer = ByteBuffer.allocate(1)),
                reading.copy(len = 2, buffer = ByteBuffer.allocate(1)),
                reading.copy(buffer = ByteBuffer.allocate(1).asReadOnlyBuffer()),
                opening.copy(buffer = null),
                opening.copy(len = 0),
                opening.copy(len = opening.len + 1),
                opening.copy(fd = 0),
                opening.copy(addr = 1),
                opening.copy(flags = 1),
                opening.copy(offset = 1L shl 40),
                opening.copy(offset = 3),
                opening.copy(offset = 64),
                opening.copy(offset = (2 or 128).toLong()),
                opening.copy(offset = (2 or 1024).toLong()),
                opening.copy(len = 1, buffer = ByteBuffer(byteArrayOf(0))),
                opening.copy(len = 1, buffer = ByteBuffer(byteArrayOf(-1))),
                opening.copy(len = PATH_MAX, buffer = ByteBuffer(ByteArray(PATH_MAX) { 65 })),
                Submissions.nop(0),
                opening,
            ).mapIndexed { index, submission -> submission.copy(userData = index.toLong() + 1) }
            val results = backend.submitBatch(submissions)
            assertEquals(submissions.map { it.userData }, results.map { it.userData })
            assertTrue(results.dropLast(1).all { it.res < 0 })
            assertEquals(-ENAMETOOLONG, results[18].res)
            assertTrue(results.last().res >= 0)
        } finally {
            try { backend.close() } finally { remove(path); rmdir(directory) }
        }
    }

    @Test
    fun drain_leaves_borrowed_descriptors_open_and_does_not_retry_explicit_close() = runTest {
        val directory = createTempDirectory("uring-posix-owned")
        val path = "$directory/image"
        val facade = FunctionalUringFacade.create(this, 8, openUserspaceChannelBackend(8))
        var borrowed = -1
        try {
            val owned = facade.batchEnqueue(listOf(Submissions.openat(path, 2 or 64, 1)).toSeries())[0].res
            assertTrue(owned >= 0)
            borrowed = open(path, O_RDWR)
            assertTrue(borrowed >= 0)
            val completed = facade.batchEnqueue(listOf(Submissions.fsync(borrowed, 2), Submissions.close(owned, 3)).toSeries())
            assertEquals(0, completed[0].res)
            assertEquals(0, completed[1].res)
            facade.drain()
            facade.drain()
            assertEquals(-1L, PosixUringIO.fileSize(owned))
            assertEquals(0L, PosixUringIO.fileSize(borrowed))
        } finally {
            try { facade.drain() } finally {
                if (borrowed >= 0) close(borrowed)
                remove(path)
                rmdir(directory)
            }
        }
    }

    @Test
    fun common_file_channel_create_exclusive_truncate_and_read_only_open_use_posix_flags() {
        val directory = createTempDirectory("uring-posix-channel")
        val path = "$directory/image"
        try {
            val channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
            try {
                assertEquals(3, channel.write(ByteBuffer(byteArrayOf(1, 2, 3))))
                val target = ByteBuffer.allocate(3)
                assertEquals(3, channel.read(target, 0))
                assertContentEquals(byteArrayOf(1, 2, 3), target.array())
                channel.force(true)
            } finally { channel.close() }
            assertFailsWith<IOException> {
                FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
            }

            val truncated = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                assertEquals(0L, truncated.size())
                assertEquals(1, truncated.write(ByteBuffer(byteArrayOf(4))))
            } finally { truncated.close() }
            val readOnly = FileChannel.open(path, StandardOpenOption.READ)
            try {
                val target = ByteBuffer.allocate(1)
                assertEquals(1, readOnly.read(target))
                assertContentEquals(byteArrayOf(4), target.array())
                assertEquals(-1, readOnly.read(ByteBuffer.allocate(1)))
            } finally { readOnly.close() }
        } finally {
            remove(path)
            rmdir(directory)
        }
    }
}
