package borg.trikeshed.forge.net.kanban

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobSnapshot
import borg.trikeshed.kanban.JobKanbanProjection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1 — ForgeKanbanJobSink RED tests.
 *
 * The sink is the conduit from committed frames to the Kanban projection.
 * It accepts committed events and applies them to the projection in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForgeKanbanJobSinkTest {

    /**
     * Sink applies committed frame to projection.
     */
    @Test
    fun sinkAppliesCommittedFrameToProjection() = runTest {
        val projection = JobKanbanProjection()
        val sink = ForgeKanbanJobSink(projection)

        val snap = JobSnapshot("j-1", 1, "ck-1", "submitted", emptyList())
        val cid = ContentId.of("v1".encodeToByteArray())

        sink.applyCommit(1L, "j-1", snap, cid)

        assertEquals(1, projection.cardCount)
        assertEquals("j-1", projection.card("j-1")!!.jobId)
    }

    /**
     * Sink replays a sequence of commits and produces the same projection.
     */
    @Test
    fun sinkReplayProducesSameProjection() = runTest {
        val projection1 = JobKanbanProjection()
        val sink1 = ForgeKanbanJobSink(projection1)
        val projection2 = JobKanbanProjection()
        val sink2 = ForgeKanbanJobSink(projection2)

        val frames = listOf(
            Triple(1L, "j-1", JobSnapshot("j-1", 1, "ck", "submitted", emptyList())),
            Triple(2L, "j-2", JobSnapshot("j-2", 1, "ck", "submitted", emptyList())),
            Triple(3L, "j-1", JobSnapshot("j-1", 2, "ck", "active", emptyList())),
        )

        frames.forEach { (seq, jobId, snap) ->
            val cid = ContentId.of("$seq".encodeToByteArray())
            sink1.applyCommit(seq, jobId, snap, cid)
            sink2.applyCommit(seq, jobId, snap, cid)
        }

        assertEquals(projection1.cardCount, projection2.cardCount)
        assertEquals(
            projection1.card("j-1")!!.columnId,
            projection2.card("j-1")!!.columnId,
            "replay must produce identical projections"
        )
    }

    /**
     * Sink rejects out-of-order sequence.
     */
    @Test
    fun sinkRejectsOutOfOrderSequence() = runTest {
        val projection = JobKanbanProjection()
        val sink = ForgeKanbanJobSink(projection)

        sink.applyCommit(1L, "j-1",
            JobSnapshot("j-1", 1, "ck", "submitted", emptyList()),
            ContentId.of("a".encodeToByteArray()))

        // Sequence 3 before 2 — must be rejected
        val result = sink.applyCommit(3L, "j-1",
            JobSnapshot("j-1", 2, "ck", "active", emptyList()),
            ContentId.of("c".encodeToByteArray()))

        assertTrue(!result.accepted, "out-of-order sequence must be rejected")
    }
}
