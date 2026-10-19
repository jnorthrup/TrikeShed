package borg.trikeshed.kanban

import borg.trikeshed.dag.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BoardFactSequenceTest {
    private fun committed(id: String, sequence: Long, column: BoardCol, owner: String = "",
                          family: String = "", command: JobCommand? = null): BoardCommitted {
        val jobId = JobId.of(id)
        return BoardCommitted(sequence, id, JobSnapshot(jobId, sequence, "", "replayed"),
            ContentId.of("$id/$sequence/${column.wire}".encodeToByteArray()),
            command ?: JobCommand.Acknowledge(jobId, "$id/$sequence", sequence - 1),
            column, null, lastMoveMs = sequence, owner = owner, executionFamily = family)
    }

    @Test fun anOlderQueuedCommitCannotReplaceTheNewerSeedForThatJob() = runTest {
        val rete = ReteNetwork()
        val facts = BoardFactElement(rete)
        val id = FactId("board", "task")
        try {
            facts.onCommitted(committed("task", 20, BoardCol.RUNNING, "lcnc-runner", "lcnc/run/root"))
            val seeded = rete.workingMemory.facts(id).view.single()
            facts.onCommitted(committed("task", 19, BoardCol.READY, "claim:brain"))
            assertEquals(seeded, rete.workingMemory.facts(id).view.single())
            val fields = rete.workingMemory.facts(id).view.single().fields
            assertEquals(20L, fields["lastSequence"])
            assertEquals("running", fields["column"])
            assertEquals("lcnc-runner", fields["owner"])
            assertEquals("lcnc/run/root", fields["executionFamily"])
            facts.onCommitted(committed("another", 1, BoardCol.READY))
            assertEquals(1L, rete.workingMemory.facts(FactId("board", "another")).view.single().fields["lastSequence"],
                "The watermark is per job, not the greatest sequence seen anywhere")
        } finally { facts.retractAll(); rete.close() }
    }

    @Test fun anArchiveTombstonePreventsOlderSeedsAndCommitsFromResurrectingTheJob() = runTest {
        val rete = ReteNetwork()
        val facts = BoardFactElement(rete)
        try {
            facts.onCommitted(committed("known", 10, BoardCol.RUNNING))
            facts.onCommitted(committed("known", 12, BoardCol.ARCHIVED))
            facts.onCommitted(committed("unseen", 22, BoardCol.ARCHIVED))
            for ((jobId, sequence) in listOf("known" to 11L, "unseen" to 21L)) {
                facts.onCommitted(committed(jobId, sequence, BoardCol.READY))
                assertTrue(rete.workingMemory.facts(FactId("board", jobId)).isEmpty())
            }
        } finally { facts.retractAll(); rete.close() }
    }

    @Test fun anOlderRetractionCannotRemoveANewerLiveSeed() = runTest {
        val rete = ReteNetwork()
        val facts = BoardFactElement(rete)
        val id = JobId.of("task")
        try {
            facts.onCommitted(committed(id.value, 30, BoardCol.RUNNING))
            for (command in listOf(JobCommand.Retract(id, "old-retract", 28), JobCommand.Cancel(id, "old-cancel", 28))) {
                facts.onCommitted(committed(id.value, 29, BoardCol.ARCHIVED, command = command))
                assertEquals(30L, rete.workingMemory.facts(FactId("board", id.value)).view.single().fields["lastSequence"])
            }
        } finally { facts.retractAll(); rete.close() }
    }
}
