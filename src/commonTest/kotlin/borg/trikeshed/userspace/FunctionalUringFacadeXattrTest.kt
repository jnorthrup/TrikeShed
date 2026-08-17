package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FunctionalUringFacadeXattrTest {

    private class StubBackend : UserspaceChannelBackend {
        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
            submissions.map { SelectionResult(0, it.userData) }
        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = TODO()
    }

    // ── Layer 2: all 8 xattr ops are rejected at enqueue ──

    @Test
    fun fsetxattr_is_rejected() {
        val facade = FunctionalUringFacade(8, StubBackend())
        assertFailsWith<IllegalArgumentException> {
            facade.enqueue(UringSubmission(UringOp.FSETXATTR, fd = 3, addr = 0, len = 0, offset = 0))
        }
    }

    @Test
    fun setxattr_is_rejected() {
        val facade = FunctionalUringFacade(8, StubBackend())
        assertFailsWith<IllegalArgumentException> {
            facade.enqueue(UringSubmission(UringOp.SETXATTR, fd = 3, addr = 0, len = 0, offset = 0))
        }
    }

    @Test
    fun fgetxattr_is_rejected() {
        val facade = FunctionalUringFacade(8, StubBackend())
        assertFailsWith<IllegalArgumentException> {
            facade.enqueue(UringSubmission(UringOp.FGETXATTR, fd = 3, addr = 0, len = 0, offset = 0))
        }
    }

    @Test
    fun getxattr_is_rejected() {
        val facade = FunctionalUringFacade(8, StubBackend())
        assertFailsWith<IllegalArgumentException> {
            facade.enqueue(UringSubmission(UringOp.GETXATTR, fd = 3, addr = 0, len = 0, offset = 0))
        }
    }

    @Test
    fun flistxattr_is_rejected() {
        val facade = FunctionalUringFacade(8, StubBackend())
        assertFailsWith<IllegalArgumentException> {
            facade.enqueue(UringSubmission(UringOp.FLISTXATTR, fd = 3, addr = 0, len = 0, offset = 0))
        }
    }

    @Test
    fun listxattr_is_rejected() {
        val facade = FunctionalUringFacade(8, StubBackend())
        assertFailsWith<IllegalArgumentException> {
            facade.enqueue(UringSubmission(UringOp.LISTXATTR, fd = 3, addr = 0, len = 0, offset = 0))
        }
    }

    @Test
    fun fremovexattr_is_rejected() {
        val facade = FunctionalUringFacade(8, StubBackend())
        assertFailsWith<IllegalArgumentException> {
            facade.enqueue(UringSubmission(UringOp.FREMOVEXATTR, fd = 3, addr = 0, len = 0, offset = 0))
        }
    }

    @Test
    fun removexattr_is_rejected() {
        val facade = FunctionalUringFacade(8, StubBackend())
        assertFailsWith<IllegalArgumentException> {
            facade.enqueue(UringSubmission(UringOp.REMOVEXATTR, fd = 3, addr = 0, len = 0, offset = 0))
        }
    }

    // ── Layer 2: STATX timestamp quantization ──

    @Test
    fun statx_completion_is_quantized_to_synthetic_epoch() {
        val backend = object : UserspaceChannelBackend {
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
                submissions.map { SelectionResult(1700000000, it.userData) } // real timestamp
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = TODO()
        }
        val facade = FunctionalUringFacade(8, backend)
        facade.enqueue(UringSubmission(UringOp.STATX, fd = 3, addr = 0, len = 256, offset = 0, userData = 42L))

        facade.submit()
        val results = facade.wait(minComplete = 1)

        assertEquals(1, results.size)
        // Quantized to syntheticEpoch = 0, not the real timestamp 1700000000
        assertEquals(0, results[0].res)
        assertEquals(42L, results[0].userData)
    }

    @Test
    fun non_statx_ops_are_not_quantized() {
        val backend = object : UserspaceChannelBackend {
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
                submissions.map { SelectionResult(99, it.userData) }
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = TODO()
        }
        val facade = FunctionalUringFacade(8, backend)
        facade.enqueue(UringSubmission(UringOp.READ, fd = 3, addr = 0, len = 1024, offset = 0, userData = 1L))

        facade.submit()
        val results = facade.wait(minComplete = 1)

        assertEquals(99, results[0].res) // not quantized
    }
}
