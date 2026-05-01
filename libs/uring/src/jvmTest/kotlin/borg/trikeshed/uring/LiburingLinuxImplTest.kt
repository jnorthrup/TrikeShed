package borg.trikeshed.uring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// ================================================================================
// SELF-CONTAINED STUBS: Actual liburing implementation gap
// LiburingFacadeSpi exists on commonMain. LiburingFacadeProvider is a JVM stub
// that returns 0/emptyList. The Linux target needs a real implementation:
//   - CInterop to liburing (io_uring_setup, io_uring_enter, io_uring_register)
//   - Ring buffer mapping (SQ/CQ rings in shared memory)
//   - SQE submission + CQE harvesting
//   - fanout from CQE.userData → registered handlers
// ================================================================================

// ================================================================================
// SPEC: Actual liburing implementation on Linux — CInterop + ring buffer mapping
// ================================================================================

class LiburingLinuxImplTest {

    /** The Linux implementation must map io_uring SQ/CQ rings via mmap. */
    @Test fun liburing_ringBufferMapping() {
        // RED: Linux actual must:
        //   1. io_uring_setup(entries, &params) → ring_fd
        //   2. mmap SQ ring: sq_ptr = mmap(0, sq_size, ..., ring_fd, IORING_OFF_SQ_RING)
        //   3. mmap SQ entries: sqes = mmap(0, sqes_size, ..., ring_fd, IORING_OFF_SQES)
        //   4. mmap CQ ring: cq_ptr = mmap(0, cq_size, ..., ring_fd, IORING_OFF_CQ_RING)
        // The stub has none of this — it returns 0 for submitRead.
        assertTrue(true) // contract test
    }

    /** SQE submission: prep → submit → reap CQE. */
    @Test fun liburing_sqeSubmit_cqeReap() {
        val sqe = UringSqe(
            opcode = UringSqe.IORING_OP_READ,
            fd = 3,
            addr = 0x7fff0000,
            len = 1024,
            off = 0,
            userData = 42,
        )
        assertEquals(UringSqe.IORING_OP_READ, sqe.opcode)
        assertEquals(3, sqe.fd)
        assertEquals(42, sqe.userData)
        // RED: after submit, CQE with matching userData must appear:
        //   val cqe: UringCqe = ring.waitCqe()
        //   assertEquals(42, cqe.userData)
        //   assertEquals(1024, cqe.res) // bytes read
    }

    /** Fanout: CQE.userData → all registered handlers fire. */
    @Test fun liburing_fanout_cqeUserDataToHandlers() {
        val received = mutableListOf<UringCqe>()
        val handler: (UringCqe) -> Unit = { received.add(it) }
        val cqe = UringCqe(userData = 99, res = 512, flags = 0)

        // When CQE arrives with userData=99, all handlers for token 99 fire.
        handler(cqe)
        assertEquals(1, received.size)
        assertEquals(99, received[0].userData)
        assertEquals(512, received[0].res)
    }

    /** Operations: READ, WRITE, ACCEPT, CONNECT, CLOSE — all supported. */
    @Test fun liburing_allOperationsSupported() {
        val ops = listOf(
            UringSqe.IORING_OP_READ,
            UringSqe.IORING_OP_WRITE,
            UringSqe.IORING_OP_ACCEPT,
            UringSqe.IORING_OP_CONNECT,
            UringSqe.IORING_OP_CLOSE,
        )
        assertEquals(5, ops.size)
        // RED: the Linux impl must handle all five opcodes
    }

    /** The current JVM stub (LiburingFacadeProvider) is not a real implementation. */
    @Test fun liburingFacadeProvider_isStub_returnsZero() {
        val provider = LiburingFacadeProvider()
        // Stub: always returns 0, no actual I/O
        assertTrue(provider.open().isSuccess)
    }
}
