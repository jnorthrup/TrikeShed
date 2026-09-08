package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ebpf.UringEbpfContextLayout
import borg.trikeshed.userspace.nio.ebpf.UringEbpfInsn
import borg.trikeshed.userspace.nio.ebpf.UringEbpfPhase
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UringEbpfFacadeTest {

    private class RecordingBackend : UserspaceChannelBackend {
        val submissions = mutableListOf<UringSubmission>()

        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
            this.submissions += submissions
            return submissions.map { SelectionResult(80 + it.opcode.code, it.userData) }
        }

        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
            val list = submissions.toList()
            this.submissions += list
            return list.map { UringCompletion(it.userData, 90 + it.opcode.code, 0) }.toSeries()
        }
    }

    @Test
    fun submit_hook_rejects_before_backend() {
        val rejectWrites = UringEbpfProgram.of(
            name = "reject-write",
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
        val backend = RecordingBackend()
        val facade = FunctionalUringFacade(8, backend, ebpfPrograms = listOf(rejectWrites))

        facade.enqueue(UringSubmission(UringOp.WRITE, fd = 3, addr = 0, len = 4, offset = 0, userData = 41))
        facade.enqueue(UringSubmission(UringOp.READ, fd = 3, addr = 0, len = 4, offset = 0, userData = 42))

        assertEquals(1, facade.submit())
        assertEquals(listOf(UringOp.READ), backend.submissions.map { it.opcode })
        assertEquals(
            listOf(
                SelectionResult(-13, 41),
                SelectionResult(80 + UringOp.READ.code, 42),
            ),
            facade.wait(minComplete = 2),
        )
    }

    @Test
    fun completion_hook_runs_after_backend_result() {
        val addOne = UringEbpfProgram.of(
            name = "add-one",
            phase = UringEbpfPhase.COMPLETE,
            instructions = longArrayOf(
                UringEbpfInsn.loadCtx64(UringEbpfInsn.R0, UringEbpfContextLayout.COMPLETION_RES),
                UringEbpfInsn.add64Imm(UringEbpfInsn.R0, 1),
                UringEbpfInsn.exit(),
            ),
        )
        val backend = RecordingBackend()
        val facade = FunctionalUringFacade(8, backend, ebpfPrograms = listOf(addOne))

        facade.enqueue(UringSubmission(UringOp.NOP, fd = -1, addr = 0, len = 0, offset = 0, userData = 7))
        facade.submit()

        assertEquals(listOf(SelectionResult(81, 7)), facade.wait(minComplete = 1))
    }

    @Test
    fun batch_enqueue_preserves_order_with_rejected_and_admitted_entries() = runTest {
        val rejectWrites = UringEbpfProgram.of(
            name = "reject-write-async",
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
        val backend = RecordingBackend()
        val facade = FunctionalUringFacade(8, backend, ebpfPrograms = listOf(rejectWrites))
        val result = facade.batchEnqueue(
            listOf(
                UringSubmission(UringOp.WRITE, fd = 3, addr = 0, len = 4, offset = 0, userData = 51),
                UringSubmission(UringOp.READ, fd = 3, addr = 0, len = 4, offset = 0, userData = 52),
            ).toSeries(),
        ).toList()

        assertEquals(listOf(UringOp.READ), backend.submissions.map { it.opcode })
        assertEquals(
            listOf(
                UringCompletion(51, -13, 0),
                UringCompletion(52, 90 + UringOp.READ.code, 0),
            ),
            result,
        )
    }

    @Test
    fun verifier_rejects_unknown_context_offsets() {
        assertFailsWith<IllegalArgumentException> {
            UringEbpfProgram.of(
                name = "bad-context",
                phase = UringEbpfPhase.SUBMIT,
                instructions = longArrayOf(
                    UringEbpfInsn.loadCtx64(UringEbpfInsn.R0, offset = 4),
                    UringEbpfInsn.exit(),
                ),
            )
        }
    }
}
