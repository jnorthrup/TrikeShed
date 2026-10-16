package borg.trikeshed.userspace

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.test.*

class EmulatedRingSnapshotTest {
    @Test
    fun registeredBufferMembershipSurvivesCallerListMutation() {
        val original = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        val caller = mutableListOf(original)
        val backend = object : UserspaceChannelBackend {
            override val capabilities = UringOp.WRITE.mask
            var admitted: UringSubmission? = null
            override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
                admitted = submissions.single()
                return listOf(SelectionResult(0, submissions.single().userData))
            }
            override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = error("unused")
        }
        val ring = EmulatedRing(backend)
        try {
            ring.open(2, 0).getOrThrow()
            assertEquals(1, ring.registerBuffers(caller).getOrThrow())
            caller[0] = ByteBuffer(4)
            caller.clear()
            ring.prepWriteFixed(7, 0, 4, 0, 1).getOrThrow()
            assertEquals(1, ring.submit().getOrThrow())
            assertSame(original, assertNotNull(backend.admitted).buffer)
            ring.unregisterBuffers().getOrThrow()
            assertTrue(ring.prepWriteFixed(7, 0, 4, 0, 2).isFailure)
        } finally { ring.close().getOrThrow() }
    }
}
