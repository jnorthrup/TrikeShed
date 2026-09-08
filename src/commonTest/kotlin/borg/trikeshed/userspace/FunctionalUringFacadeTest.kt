package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FunctionalUringFacadeTest {
    private class ReorderedBackend : UserspaceChannelBackend {
        override val capabilities: Long = UringOp.caps(UringOp.NOP, UringOp.READ, UringOp.WRITE, UringOp.FTRUNCATE)
        val admitted = mutableListOf<UringSubmission>()
        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
            admitted += submissions
            return submissions.reversed().map { SelectionResult(it.len, it.userData) }
        }
        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> =
            submitBatch(submissions.toList()).map { UringCompletion(it.userData, it.res, 0) }.toSeries()
    }

    @Test
    fun completions_correlate_by_token_in_backend_arrival_order() = runTest {
        val backend = ReorderedBackend()
        val facade = FunctionalUringFacade(4, backend)
        val result = facade.batchEnqueue(arrayOf(
            Submissions.read(7, 0, 2, 0, 101).copy(buffer = ByteBuffer(2)),
            Submissions.write(8, 0, 3, 0, 102).copy(buffer = ByteBuffer(3)),
        ).toSeries()).toList()
        assertEquals(listOf(UringCompletion(102, 3, 0), UringCompletion(101, 2, 0)), result)
    }

    @Test
    fun synchronous_queue_keeps_completion_tokens_when_backend_reorders() {
        val backend = ReorderedBackend()
        val facade = FunctionalUringFacade(4, backend)
        facade.enqueue(Submissions.nop(201))
        facade.enqueue(Submissions.nop(202))
        assertEquals(2, facade.submit())
        assertEquals(listOf(SelectionResult(0, 202), SelectionResult(0, 201)), facade.wait(2))
        assertEquals(emptyList(), facade.peek())
    }

    @Test
    fun unsupported_operations_complete_without_reaching_backend() = runTest {
        val backend = ReorderedBackend()
        val facade = FunctionalUringFacade(4, backend)
        val result = facade.batchEnqueue(arrayOf(
            Submissions.nop(301),
            UringSubmission(UringOp.READ_MULTISHOT, -1, 0, 0, 0, userData = 302),
        ).toSeries()).toList().associateBy { it.userData }
        assertEquals(0, result.getValue(301).res)
        assertEquals(-95, result.getValue(302).res)
        assertEquals(listOf(UringOp.NOP), backend.admitted.map { it.opcode })
    }

    @Test
    fun unsupported_sqe_flags_complete_without_effects() = runTest {
        val backend = ReorderedBackend()
        val facade = FunctionalUringFacade(2, backend)
        val result = facade.batchEnqueue(arrayOf(Submissions.nop(303).copy(flags = 4)).toSeries()).toList()
        assertEquals(listOf(UringCompletion(303, -95, 0)), result)
        assertEquals(emptyList(), backend.admitted)
    }

    @Test
    fun duplicate_tokens_are_rejected_before_execution() = runTest {
        val backend = ReorderedBackend()
        val facade = FunctionalUringFacade(4, backend)
        assertFailsWith<IllegalArgumentException> {
            facade.batchEnqueue(arrayOf(Submissions.nop(401), Submissions.nop(401)).toSeries())
        }
        assertEquals(emptyList(), backend.admitted)
    }

    @Test
    fun backend_missing_or_duplicate_completion_fails_correlation() = runTest {
        val backend = object : UserspaceChannelBackend {
            override val capabilities: Long = UringOp.NOP.mask
            override fun submitBatch(submissions: List<UringSubmission>) =
                submissions.map { SelectionResult(0, submissions.first().userData) }
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>) =
                submitBatch(submissions.toList()).map { UringCompletion(it.userData, it.res, 0) }.toSeries()
        }
        val facade = FunctionalUringFacade(4, backend)
        assertFailsWith<IllegalStateException> {
            facade.batchEnqueue(arrayOf(Submissions.nop(501), Submissions.nop(502)).toSeries())
        }
    }

    @Test
    fun undrained_completion_retains_its_token_and_queue_capacity() {
        val backend = ReorderedBackend()
        val facade = FunctionalUringFacade(2, backend)
        facade.enqueue(Submissions.nop(701))
        assertEquals(1, facade.submit())
        assertFailsWith<IllegalArgumentException> { facade.enqueue(Submissions.nop(701)) }
        facade.enqueue(Submissions.nop(702))
        assertEquals(1, facade.submit())
        assertFailsWith<IllegalArgumentException> { facade.enqueue(Submissions.nop(703)) }
        assertEquals(listOf(SelectionResult(0, 701), SelectionResult(0, 702)), facade.peek())
        facade.enqueue(Submissions.nop(701))
        assertEquals(1, facade.submit())
        assertEquals(listOf(SelectionResult(0, 701)), facade.peek())
    }

    @Test
    fun bounded_preparation_and_truncate_keep_their_actual_operation() {
        val backend = ReorderedBackend()
        val facade = FunctionalUringFacade(1, backend)
        facade.truncate(FileImpl(11), 1024, 601)
        assertFailsWith<IllegalArgumentException> { facade.enqueue(Submissions.nop(602)) }
        assertEquals(1, facade.submit())
        assertEquals(UringOp.FTRUNCATE, backend.admitted.single().opcode)
        assertEquals(1024L, backend.admitted.single().offset)
        assertFailsWith<UnsupportedOperationException> { facade.map(FileImpl(11), "rw", 0, 1, 603) }
    }
}
