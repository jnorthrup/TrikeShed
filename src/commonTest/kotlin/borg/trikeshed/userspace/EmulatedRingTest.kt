package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EmulatedRingTest {
    private class Memory(private val bytes: ByteArray = ByteArray(16), override val length: Long = bytes.size.toLong()) : MemoryMappingAccess {
        override val address = 0x1_0000_1000L
        var closed = false
        override fun get(offset: Long) = bytes[offset.toInt()]
        override fun set(offset: Long, value: Byte) { bytes[offset.toInt()] = value }
        override fun read(offset: Long, dst: ByteArray, start: Int, length: Int) { bytes.copyInto(dst, start, offset.toInt(), offset.toInt() + length) }
        override fun write(offset: Long, src: ByteArray, start: Int, length: Int) { src.copyInto(bytes, offset.toInt(), start, start + length) }
        override fun sync(offset: Long, length: Long, flags: Int) {}
        override fun close() { closed = true }
    }

    private class Backend : UserspaceChannelBackend {
        var registration: Result<Unit> = Result.failure(UnsupportedOperationException())
        var unregistration: Result<Unit> = Result.success(Unit)
        var seen = emptyList<UringSubmission>()
        var effect: (UringSubmission) -> Int = { it.len }
        var closed = false
        override fun registerBuffers(buffers: Series<MemoryMapping>) = registration
        override fun unregisterBuffers() = unregistration
        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
            seen = submissions
            return submissions.map { SelectionResult(res = effect(it), userData = it.userData) }
        }
        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = error("synchronous test backend")
        override fun close() { closed = true }
    }

    @Test fun madviseUsesFullAddressAndAdviceField() {
        val backend = Backend()
        val ring = EmulatedRing(backend)
        ring.open(1, 0).getOrThrow()
        ring.prepMadvise(0x1_0000_1000L, 4096, 3, 10).getOrThrow()
        assertEquals(1, ring.submit().getOrThrow())
        val submitted = backend.seen.single()
        assertEquals(UringOp.MADVISE, submitted.opcode)
        assertEquals(-1, submitted.fd)
        assertEquals(0x1_0000_1000L, submitted.addr)
        assertEquals(0L, submitted.offset)
        assertEquals(0, submitted.flags)
        assertEquals(3, submitted.operationFlags)
        ring.close().getOrThrow()
    }

    @Test fun registeredMemorySurvivesPendingWorkAndFailedUnregistration() {
        val backend = Backend().also { it.registration = Result.success(Unit) }
        val raw = Memory()
        val memory = MemoryMapping(raw, 3)
        val ring = EmulatedRing(backend)
        ring.open(2, 0).getOrThrow()
        ring.registerBuffers(arrayOf(memory).toSeries()).getOrThrow()
        assertFailsWith<IllegalStateException> { memory.close() }
        assertTrue(ring.registerBuffers(arrayOf(memory).toSeries()).isFailure)
        ring.prepReadFixed(42, 0, 4, 12, 1).getOrThrow()
        assertTrue(ring.unregisterBuffers().isFailure)
        ring.submit().getOrThrow()
        val request = backend.seen.single()
        assertEquals(UringOp.READ_FIXED, request.opcode)
        assertEquals(memory.address, request.addr)
        assertEquals(0, request.bufferIndex)
        assertSame(memory, request.memory)
        backend.unregistration = Result.failure(IllegalStateException("kernel refused unregister"))
        assertTrue(ring.unregisterBuffers().isFailure)
        assertFailsWith<IllegalStateException> { memory.close() }
        backend.unregistration = Result.success(Unit)
        ring.unregisterBuffers().getOrThrow()
        memory.close()
        assertTrue(raw.closed)
        ring.close().getOrThrow()
    }

    @Test fun commonFallbackCopiesOnlyCompletedReadBytes() {
        val backend = Backend().also {
            it.effect = { sub ->
                assertEquals(UringOp.READ, sub.opcode)
                assertEquals(-1, sub.bufferIndex)
                requireNotNull(sub.buffer).put(0, 11).put(1, 22)
                2
            }
        }
        val memory = MemoryMapping(Memory(ByteArray(8) { 7 }), 3)
        val ring = EmulatedRing(backend)
        ring.open(1, 0).getOrThrow()
        ring.registerBuffers(arrayOf(memory).toSeries()).getOrThrow()
        ring.prepReadFixed(5, 0, 8, 0, 1).getOrThrow()
        ring.submit().getOrThrow()
        assertEquals(11.toByte(), memory[0])
        assertEquals(22.toByte(), memory[1])
        assertEquals(7.toByte(), memory[2])
        assertEquals(2, ring.peekCqe().getOrThrow()?.res)
        ring.unregisterBuffers().getOrThrow()
        memory.close()
        ring.close().getOrThrow()
    }

    @Test fun commonFallbackCopiesWriteBytesBeforeDispatch() {
        val backend = Backend().also {
            it.effect = { sub ->
                assertEquals(UringOp.WRITE, sub.opcode)
                assertEquals(19.toByte(), requireNotNull(sub.buffer).get(0))
                sub.len
            }
        }
        val memory = MemoryMapping(Memory(ByteArray(8) { 19 }), 3)
        val ring = EmulatedRing(backend)
        ring.open(1, 0).getOrThrow()
        ring.registerBuffers(arrayOf(memory).toSeries()).getOrThrow()
        ring.prepWriteFixed(5, 0, 8, 0, 1).getOrThrow()
        ring.close().getOrThrow()
        memory.close()
        assertTrue(backend.closed)
    }

    @Test fun registrationErrorsReleaseOwnersWithoutSelectingEmulation() {
        val backend = Backend().also { it.registration = Result.failure(IllegalStateException("ENOMEM")) }
        val memory = MemoryMapping(Memory(), 3)
        val ring = EmulatedRing(backend)
        ring.open(1, 0).getOrThrow()
        assertTrue(ring.registerBuffers(arrayOf(memory).toSeries()).isFailure)
        assertTrue(ring.prepReadFixed(5, 0, 1, 0, 1).isFailure)
        memory.close()
        ring.close().getOrThrow()
    }

    @Test fun rejectsUnrepresentableRegistrationAndTransferRanges() {
        val ring = EmulatedRing(Backend())
        ring.open(1, 0).getOrThrow()
        val huge = MemoryMapping(Memory(length = Int.MAX_VALUE.toLong() + 1), 3)
        assertTrue(ring.registerBuffers(arrayOf(huge).toSeries()).isFailure)
        huge.close()
        val memory = MemoryMapping(Memory(ByteArray(2)), 3)
        ring.registerBuffers(arrayOf(memory).toSeries()).getOrThrow()
        assertTrue(ring.prepReadFixed(5, 0, 3, 0, 1).isFailure)
        assertTrue(ring.prepReadFixed(5, -1, 1, 0, 1).isFailure)
        ring.close().getOrThrow()
        memory.close()
    }

    @Test fun boundedCompletionTransferAndFanoutHappenOnce() {
        val ring = EmulatedRing(Backend())
        assertTrue(ring.prepClose(3, 1).isFailure)
        ring.open(1, 0).getOrThrow()
        assertTrue(ring.open(1, 0).isFailure)
        var observed = 0
        ring.registerFanoutHandler(1) { observed++; error("observer failure") }
        ring.prepClose(3, 1).getOrThrow()
        assertTrue(ring.prepClose(4, 2).isFailure)
        ring.submit().getOrThrow()
        assertTrue(ring.prepClose(4, 2).isFailure)
        assertEquals(0, ring.peekCqe().getOrThrow()?.res)
        ring.cqAdvance(1)
        assertNull(ring.waitCqe().getOrThrow())
        assertEquals(1, observed)
        ring.drain().getOrThrow()
        assertTrue(ring.prepClose(4, 2).isFailure)
        ring.close().getOrThrow()
    }

    @Test fun failedSubmissionStillTearsDownAndReleasesRegistration() {
        val backend = Backend().also { it.effect = { error("transport failed after settling") } }
        val memory = MemoryMapping(Memory(), 3)
        val ring = EmulatedRing(backend)
        ring.open(1, 0).getOrThrow()
        ring.registerBuffers(arrayOf(memory).toSeries()).getOrThrow()
        ring.prepReadFixed(5, 0, 1, 0, 1).getOrThrow()
        assertTrue(ring.submit().isFailure)
        assertTrue(ring.registerBuffers(arrayOf(memory).toSeries()).isFailure)
        assertTrue(ring.close().isFailure)
        assertTrue(backend.closed)
        assertTrue(ring.isClosed)
        memory.close()
    }

    @Test fun sessionRejectsReopenAndUnsupportedSetupBeforeOpeningBackend() {
        var openings = 0
        val backend = Backend()
        val session = LiburingSession { openings++; backend }
        assertTrue(session.open(1, 1).isFailure)
        assertEquals(0, openings)
        session.open(1, 0).getOrThrow()
        assertTrue(session.open(2, 0).isFailure)
        assertEquals(1, openings)
        session.prepClose(3, 1).getOrThrow()
        session.close().getOrThrow()
        assertTrue(backend.closed)
        assertEquals(1, backend.seen.size)
    }
}
