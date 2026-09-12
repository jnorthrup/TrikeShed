package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UserspaceChannelBackend
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PosixChannelHandleTest {
    private class Backend : UserspaceChannelBackend {
        override val capabilities = UringOp.caps(UringOp.READ, UringOp.WRITE, UringOp.CLOSE)
        override var deferredCapabilities = 0L
        var effect: (UringSubmission) -> Int = { 0 }
        val seen = mutableListOf<UringSubmission>()
        private val pending = mutableListOf<UringSubmission>()
        private val ready = mutableListOf<SelectionResult>()
        var closes = 0
        var failClose = false
        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
            seen.addAll(submissions)
            return submissions.mapNotNull {
                if (deferredCapabilities and it.opcode.mask != 0L) {
                    pending.add(it)
                    null
                } else SelectionResult(effect(it), it.userData)
            }
        }
        override fun cancelPending() {
            ready.addAll(pending.map { SelectionResult(-125, it.userData) })
            pending.clear()
        }
        override fun reapCompletions(minComplete: Int) = ready.toList().also { ready.clear() }
        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> =
            error("synchronous channel test backend")
        override fun close() {
            closes++
            check(!failClose) { "close failed" }
        }
    }

    @Test fun cqe_values_and_repeated_caller_identities_keep_capacity_until_consumed() {
        val backend = Backend().apply { effect = { if (it.fd == 10) -11 else 0 } }
        val handle = PosixChannelHandle(2, backend = backend)
        assertEquals(0, handle.readv(10, ByteBuffer(1), 42))
        assertEquals(0, handle.readv(11, ByteBuffer(1), 42))
        assertEquals(-11, handle.prepClose(12, 42))
        assertEquals(2, handle.submit())
        assertEquals(-11, handle.prepClose(12, 42))
        assertEquals(listOf(ChannelResult(10, -11, 42), ChannelResult(11, 0, 42)), handle.wait(2))
        assertEquals(2, backend.seen.map { it.userData }.toSet().size)
        assertEquals(0, handle.prepClose(12, 42))
        handle.close()
        assertEquals(listOf(ChannelResult(12, 0, 42)), handle.wait(0))
        assertTrue(handle.wait(0).isEmpty())
        assertEquals(-9, handle.prepClose(12, 42))
        handle.close()
        assertEquals(1, backend.closes)
    }

    @Test fun unavailable_socket_opcodes_settle_as_unsupported_without_backend_effects() {
        val backend = Backend()
        val handle = PosixChannelHandle(7, backend = backend)
        assertEquals(0, handle.prepSocket(2, 1, 0, 7))
        assertEquals(0, handle.prepBind(10, ByteBuffer(16), 7))
        assertEquals(0, handle.prepListen(10, 128, 7))
        assertEquals(0, handle.prepConnect(10, ByteBuffer(16), 7))
        assertEquals(0, handle.prepAccept(10, 7))
        assertEquals(0, handle.sendmsg(10, 0, 7))
        assertEquals(0, handle.recvmsg(10, 0, 7))
        assertEquals(7, handle.submit())
        assertEquals(List(7) { -95 }, handle.wait(7).map { it.res })
        assertTrue(backend.seen.isEmpty())
        handle.close()
    }

    @Test fun bound_descriptor_access_uses_the_ring_and_preserves_pending_caller_cqes() {
        val backend = Backend().apply { effect = { it.len } }
        val handle = PosixChannelHandle(2, 15, backend)
        assertEquals(15, handle.id)
        assertEquals(3, handle.read(ByteBuffer(3), 123))
        assertEquals(4, handle.write(ByteBuffer(4), 456))
        assertEquals(listOf(123L, 456L), backend.seen.map { it.offset })
        assertEquals(listOf(15, 15), backend.seen.map { it.fd })
        assertEquals(listOf(UringOp.READ, UringOp.WRITE), backend.seen.map { it.opcode })
        assertEquals(0, handle.readv(16, ByteBuffer(2), 91))
        assertEquals(-11, handle.read(ByteBuffer(1), 0))
        assertEquals(listOf(ChannelResult(16, 2, 91)), handle.wait(1))
        handle.close()
        assertEquals(-9, handle.read(ByteBuffer(1), 0))
        assertTrue(backend.seen.none { it.opcode == UringOp.CLOSE })
    }

    @Test fun close_preserves_deferred_cancellation_and_can_retry_backend_close() {
        val backend = Backend().apply {
            deferredCapabilities = UringOp.READ.mask
            failClose = true
        }
        val handle = PosixChannelHandle(1, backend = backend)
        assertEquals(0, handle.readv(10, ByteBuffer(1), 9))
        assertEquals(1, handle.submit())
        assertTrue(handle.wait(0).isEmpty())
        assertEquals(-11, handle.prepClose(10, 9))
        assertFailsWith<IllegalStateException> { handle.close() }
        assertEquals(-9, handle.prepClose(10, 9))
        assertEquals(listOf(ChannelResult(10, -125, 9)), handle.wait(0))
        backend.failClose = false
        handle.close()
        assertEquals(2, backend.closes)
        assertTrue(handle.wait(0).isEmpty())
    }
}
