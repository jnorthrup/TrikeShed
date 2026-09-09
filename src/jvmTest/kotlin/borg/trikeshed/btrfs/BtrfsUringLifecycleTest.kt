package borg.trikeshed.btrfs

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UserspaceChannelBackend
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.spi.NioCapabilityReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BtrfsUringLifecycleTest {
    /** Effects happen before the held completion, exposing descriptor and buffer ownership races. */
    private class Backend(
        val heldOpcode: UringOp? = null,
        val truncateResult: Int = 0,
        val closeResult: Int = 0,
        readResults: List<Int> = emptyList(),
        writeResults: List<Int> = emptyList(),
    ) : UserspaceChannelBackend {
        override val capabilities = UringOp.caps(
            UringOp.OPENAT, UringOp.FTRUNCATE, UringOp.READ, UringOp.WRITE, UringOp.FSYNC, UringOp.CLOSE,
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val submissions = mutableListOf<UringSubmission>()
        val image = ByteArray(8192)
        val reads = ArrayDeque(readResults)
        val writes = ArrayDeque(writeResults)
        var descriptorOpen = false
        var closes = 0

        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
            error("Connected volume must use the suspend facade")

        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
            val submission = submissions[0]
            this.submissions += submission
            val result = when (submission.opcode) {
                UringOp.OPENAT -> { descriptorOpen = true; 41 }
                UringOp.FTRUNCATE -> truncateResult
                UringOp.READ, UringOp.WRITE -> {
                    check(descriptorOpen)
                    val results = if (submission.opcode == UringOp.READ) reads else writes
                    val count = if (results.isEmpty()) submission.len else results.removeFirst()
                    if (count in 1..submission.len) {
                        val buffer = requireNotNull(submission.buffer)
                        val offset = submission.offset.toInt()
                        if (submission.opcode == UringOp.READ) {
                            buffer.put(image, offset, count)
                        } else {
                            buffer.get(image, offset, count)
                        }
                    }
                    count
                }
                UringOp.FSYNC -> 0
                UringOp.CLOSE -> {
                    assertEquals(41, submission.fd)
                    check(descriptorOpen) { "Descriptor closed twice" }
                    descriptorOpen = false
                    closeResult
                }
                else -> error("Unexpected operation: ${submission.opcode}")
            }
            if (submission.opcode == heldOpcode) {
                entered.complete(Unit)
                release.await()
            }
            val completion = UringCompletion(submission.userData, result, 0)
            return 1 j { completion }
        }

        override fun close() { descriptorOpen = false; closes++ }
    }

    private val report = NioCapabilityReport("controlled", false, emptyList(), "", 0)

    private fun channel(backend: Backend) = UringChannel(FunctionalUringFacade(8, backend))

    private suspend fun open(channel: UringChannel, resize: Boolean = false) =
        BtrfsUringFileVolume.open(channel, "controlled.img", blockSize = 512, capacity = 16,
            create = false, resize = resize, backendReport = report)

    @Test
    fun cancelled_open_closes_the_descriptor_after_openat_settles() = runTest {
        val backend = Backend(heldOpcode = UringOp.OPENAT)
        val channel = channel(backend)
        try {
            val opening = async { open(channel) }
            backend.entered.await()
            opening.cancel()
            runCurrent()
            assertFalse(opening.isCompleted)
            assertTrue(backend.descriptorOpen)
            backend.release.complete(Unit)
            opening.join()
            assertTrue(opening.isCancelled)
            assertFalse(backend.descriptorOpen)
            assertEquals(listOf(UringOp.OPENAT, UringOp.CLOSE), backend.submissions.map { it.opcode })
            assertEquals(0, backend.closes)
        } finally {
            backend.release.complete(Unit)
            channel.drain()
        }
        assertEquals(1, backend.closes)
    }

    @Test
    fun cancelled_resize_finishes_before_descriptor_cleanup() = runTest {
        val backend = Backend(heldOpcode = UringOp.FTRUNCATE)
        val channel = channel(backend)
        try {
            val opening = async { open(channel, resize = true) }
            backend.entered.await()
            opening.cancel()
            runCurrent()
            assertFalse(opening.isCompleted)
            assertTrue(backend.descriptorOpen)
            backend.release.complete(Unit)
            opening.join()
            assertEquals(listOf(UringOp.OPENAT, UringOp.FTRUNCATE, UringOp.CLOSE),
                backend.submissions.map { it.opcode })
            assertFalse(backend.descriptorOpen)
        } finally {
            backend.release.complete(Unit)
            channel.drain()
        }
    }

    @Test
    fun failed_resize_retains_primary_failure_and_reports_close_failure() = runTest {
        val backend = Backend(truncateResult = -5, closeResult = -9)
        val channel = channel(backend)
        try {
            val failure = assertFailsWith<BtrfsImageIoException> { open(channel, resize = true) }
            assertEquals(-5, failure.completions.last().res)
            val cleanup = failure.suppressedExceptions.single() as BtrfsImageIoException
            assertEquals(-9, cleanup.completions.last().res)
            assertFalse(backend.descriptorOpen)
            assertEquals(0, backend.closes)
        } finally {
            channel.drain()
        }
    }

    @Test
    fun cancelled_partial_write_settles_before_drain_and_rejects_new_admission() = runTest {
        val backend = Backend(heldOpcode = UringOp.WRITE, writeResults = listOf(2, 2))
        val channel = channel(backend)
        val volume = open(channel)
        val source = ByteBuffer.wrap(byteArrayOf(9, 1, 2, 3, 4, 9)).position(1).limit(5)
        try {
            val writing = async { volume.write(0, source) }
            backend.entered.await()
            val queued = async { runCatching { volume.read(0, 1) } }
            runCurrent()
            writing.cancel()
            val draining = async { volume.drain() }
            runCurrent()
            assertFalse(writing.isCompleted)
            assertFalse(draining.isCompleted)
            assertEquals(1, source.position())
            assertFailsWith<IllegalStateException> { volume.write(1, ByteBuffer.wrap(byteArrayOf(5))) }
            assertFailsWith<IllegalStateException> { volume.close() }
            backend.release.complete(Unit)
            writing.join()
            assertTrue(queued.await().exceptionOrNull() is IllegalStateException)
            draining.await()
            assertEquals(5, source.position())
            assertContentEquals(byteArrayOf(1, 2, 3, 4), backend.image.copyOfRange(0, 4))
            assertEquals(listOf(UringOp.OPENAT, UringOp.WRITE, UringOp.WRITE, UringOp.CLOSE),
                backend.submissions.map { it.opcode })
            assertEquals(0, backend.closes)
        } finally {
            backend.release.complete(Unit)
            volume.drain()
            channel.drain()
        }
    }

    @Test
    fun cancelled_drain_joins_active_work_and_closes_once() = runTest {
        val backend = Backend(heldOpcode = UringOp.WRITE)
        val channel = channel(backend)
        val volume = open(channel)
        try {
            val writing = async { volume.write(0, ByteBuffer.wrap(byteArrayOf(1))) }
            backend.entered.await()
            val draining = async { volume.drain() }
            runCurrent()
            draining.cancel()
            runCurrent()
            assertFalse(draining.isCompleted)
            assertTrue(backend.descriptorOpen)
            backend.release.complete(Unit)
            writing.await()
            draining.join()
            volume.drain()
            volume.close()
            assertEquals(1, backend.submissions.count { it.opcode == UringOp.CLOSE })
            assertEquals(0, backend.closes)
        } finally {
            backend.release.complete(Unit)
            volume.drain()
            channel.drain()
        }
    }

    @Test
    fun close_error_is_retained_without_retrying_a_released_descriptor() = runTest {
        val backend = Backend(closeResult = -5)
        val channel = channel(backend)
        val volume = open(channel)
        try {
            val failure = assertFailsWith<BtrfsImageIoException> { volume.drain() }
            assertSame(failure, assertFailsWith<BtrfsImageIoException> { volume.drain() })
            assertSame(failure, assertFailsWith<BtrfsImageIoException> { volume.close() })
            assertFailsWith<IllegalStateException> { volume.read(0, 1) }
            assertFalse(backend.descriptorOpen)
            assertEquals(1, backend.submissions.count { it.opcode == UringOp.CLOSE })
            assertEquals(0, backend.closes)
        } finally {
            channel.drain()
        }
    }

    @Test
    fun partial_io_respects_slice_position_limit_and_read_only_source() = runTest {
        val backend = Backend(readResults = listOf(137, 375), writeResults = listOf(2, 1, 1))
        val channel = channel(backend)
        val volume = open(channel)
        val bytes = ByteArray(12) { it.toByte() }
        val source = ByteBuffer.wrap(bytes, 2, 8).position(2).limit(6).asReadOnlyBuffer()
        try {
            volume.write(1, source)
            assertEquals(6, source.position())
            assertEquals(6, source.limit())
            assertContentEquals(ByteArray(12) { it.toByte() }, bytes)
            val result = volume.read(1, 1)
            assertEquals(0, result.position())
            assertEquals(512, result.remaining())
            val actual = ByteArray(4)
            result.get(actual)
            assertContentEquals(byteArrayOf(4, 5, 6, 7), actual)
            assertEquals(listOf(512L, 514L, 515L),
                backend.submissions.filter { it.opcode == UringOp.WRITE }.map { it.offset })
            assertEquals(listOf(512L, 649L),
                backend.submissions.filter { it.opcode == UringOp.READ }.map { it.offset })
        } finally {
            volume.drain()
            channel.drain()
        }
    }

    @Test
    fun zero_progress_and_oversized_completions_fail_without_advancing_unwritten_bytes() = runTest {
        val backend = Backend(readResults = listOf(0, 513), writeResults = listOf(2, 0, 5))
        val channel = channel(backend)
        val volume = open(channel)
        val source = ByteBuffer.wrap(byteArrayOf(9, 1, 2, 3, 4)).position(1)
        try {
            assertFailsWith<BtrfsImageIoException> { volume.write(0, source) }
            assertEquals(3, source.position())
            assertFailsWith<BtrfsImageIoException> { volume.write(0, source) }
            assertEquals(3, source.position())
            val zero = assertFailsWith<BtrfsImageIoException> { volume.read(0, 1) }
            assertEquals(0, zero.completions.last().res)
            val oversized = assertFailsWith<BtrfsImageIoException> { volume.read(0, 1) }
            assertEquals(513, oversized.completions.last().res)
            assertEquals(3, backend.submissions.count { it.opcode == UringOp.WRITE })
            assertEquals(2, backend.submissions.count { it.opcode == UringOp.READ })
        } finally {
            volume.drain()
            channel.drain()
        }
    }

    @Test
    fun range_checks_precede_submission_and_empty_boundary_io_is_valid() = runTest {
        val backend = Backend()
        val channel = channel(backend)
        val volume = open(channel)
        val source = ByteBuffer.wrap(byteArrayOf(1))
        try {
            assertFailsWith<IllegalArgumentException> { volume.read(-1, 1) }
            assertFailsWith<IllegalArgumentException> { volume.read(0, -1) }
            assertFailsWith<IllegalArgumentException> { volume.read(Long.MAX_VALUE, 0) }
            assertFailsWith<IllegalArgumentException> { volume.read(0, Int.MAX_VALUE) }
            assertFailsWith<IllegalArgumentException> { volume.read(16, 1) }
            assertFailsWith<IllegalArgumentException> { volume.write(16, source) }
            assertEquals(0, source.position())
            assertEquals(0, volume.read(16, 0).remaining())
            volume.write(16, ByteBuffer.allocate(0))
            assertEquals(listOf(UringOp.OPENAT), backend.submissions.map { it.opcode })
        } finally {
            volume.drain()
            channel.drain()
        }
    }

    @Test
    fun read_only_open_never_creates_resizes_or_submits_writes() = runTest {
        val backend = Backend()
        val channel = channel(backend)
        try {
            assertFailsWith<IllegalArgumentException> {
                BtrfsUringFileVolume.open(channel, "controlled.img", capacity = 16,
                    backendReport = report, readOnly = true)
            }
            assertTrue(backend.submissions.isEmpty())
            val volume = BtrfsUringFileVolume.open(channel, "controlled.img", blockSize = 512, capacity = 16,
                create = false, backendReport = report, readOnly = true)
            try {
                assertTrue(volume.readOnly)
                assertEquals(0L, backend.submissions.single().offset)
                assertEquals(512, volume.read(0, 1).remaining())
                assertFailsWith<IllegalStateException> { volume.write(0, ByteBuffer.wrap(byteArrayOf(1))) }
                assertFalse(backend.submissions.any { it.opcode == UringOp.FTRUNCATE || it.opcode == UringOp.WRITE })
            } finally {
                volume.drain()
            }
        } finally {
            channel.drain()
        }
    }
}
