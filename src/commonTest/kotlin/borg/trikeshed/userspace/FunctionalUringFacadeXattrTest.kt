package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FunctionalUringFacadeXattrTest {

    private class StubBackend : UserspaceChannelBackend {
        override val capabilities: Long = UringOp.entries.fold(0L) { mask, op -> mask or op.mask }
        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
            submissions.map { SelectionResult(0, it.userData) }
        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = submitBatch(submissions.toList()).map { UringCompletion(it.userData, it.res, 0) }.toSeries()
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

    // CQE res is syscall status, never a timestamp payload.

    @Test
    fun statx_failure_is_not_replaced_with_success() {
        val backend = object : UserspaceChannelBackend {
            override val capabilities: Long = UringOp.caps(UringOp.STATX, UringOp.READ)
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
                submissions.map { SelectionResult(-9, it.userData) }
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = submitBatch(submissions.toList()).map { UringCompletion(it.userData, it.res, 0) }.toSeries()
        }
        val facade = FunctionalUringFacade(8, backend)
        facade.enqueue(UringSubmission(UringOp.STATX, fd = 3, addr = 0, len = 256, offset = 0, userData = 42L))

        facade.submit()
        val results = facade.wait(minComplete = 1)

        assertEquals(1, results.size)
        assertEquals(-9, results[0].res)
        assertEquals(42L, results[0].userData)
    }

    @Test
    fun non_statx_ops_are_not_quantized() {
        val backend = object : UserspaceChannelBackend {
            override val capabilities: Long = UringOp.caps(UringOp.STATX, UringOp.READ)
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
                submissions.map { SelectionResult(99, it.userData) }
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = submitBatch(submissions.toList()).map { UringCompletion(it.userData, it.res, 0) }.toSeries()
        }
        val facade = FunctionalUringFacade(8, backend)
        facade.enqueue(UringSubmission(UringOp.READ, fd = 3, addr = 0, len = 1024, offset = 0, userData = 1L))

        facade.submit()
        val results = facade.wait(minComplete = 1)

        assertEquals(99, results[0].res) // not quantized
    }
}
