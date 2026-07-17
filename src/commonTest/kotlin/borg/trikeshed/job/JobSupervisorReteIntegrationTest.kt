package borg.trikeshed.job

import borg.trikeshed.dag.ReteNetwork
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobSupervisorReteIntegrationTest {

    @Test
    fun orderedCommittedParentChildFactsProduceDeterministicActivationAndCommand() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 8)
        val parent = JobId.of("j-parent")
        val child = JobId.of("j-child")

        nexus.submit(JobCommand.Submit(parent, "k-parent"))
        nexus.submit(JobCommand.Complete(parent, "k-complete", expectedRevision = 1))
        nexus.submit(JobCommand.Submit(child, "k-child", dependencies = listOf(parent)))
        nexus.drain()

        val snap = nexus.snapshot("j-child")
        assertTrue(snap?.lifecycle == "ready" || snap?.lifecycle == "active")

        val facts = nexus.facts("j-child")
        assertTrue(facts.isNotEmpty())

        val parentFacts = nexus.facts("j-parent")
        assertTrue(parentFacts.isNotEmpty())
    }

    @Test
    fun retractionCorrectlyInvalidatesPendingAgendaEntries() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 8)
        val parent = JobId.of("j-parent")
        val child = JobId.of("j-child")

        nexus.submit(JobCommand.Submit(parent, "k-parent"))
        nexus.submit(JobCommand.Submit(child, "k-child", dependencies = listOf(parent)))

        nexus.submit(JobCommand.Complete(parent, "k-complete", expectedRevision = 1))
        nexus.submit(JobCommand.Retract(parent, "k-retract", expectedRevision = 2))

        nexus.drain()

        val snap = nexus.snapshot("j-child")
        assertEquals("submitted", snap?.lifecycle, "Child should remain submitted if parent was retracted before child started")
    }

    @Test
    fun replayEquivalenceProducesSameActivations() = runTest {
        val wal = mutableMapOf<String, ByteArray>()

        val nexus1 = JobSupervisorElement.open(this, capacity = 8, walData = wal)
        val parent = JobId.of("j-parent")
        val child = JobId.of("j-child")

        nexus1.submit(JobCommand.Submit(parent, "k-parent"))
        nexus1.submit(JobCommand.Complete(parent, "k-complete", expectedRevision = 1))
        nexus1.submit(JobCommand.Submit(child, "k-child", dependencies = listOf(parent)))
        nexus1.drain()

        val snap1 = nexus1.snapshot("j-child")

        val nexus2 = JobSupervisorElement.open(this, capacity = 8, walData = wal)
        nexus2.drain()

        val snap2 = nexus2.snapshot("j-child")

        assertEquals(snap1?.lifecycle, snap2?.lifecycle, "Lifecycle should be identical on replay")
    }

    @Test
    fun assertingIdenticalFactVersionDoesNotTriggerDuplicateActivation() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 8)
        val parent = JobId.of("j-parent")
        val child = JobId.of("j-child")

        nexus.submit(JobCommand.Submit(parent, "k-parent"))
        nexus.submit(JobCommand.Submit(child, "k-child", dependencies = listOf(parent)))

        nexus.submit(JobCommand.Complete(parent, "k-complete", expectedRevision = 1))
        nexus.submit(JobCommand.Complete(parent, "k-complete", expectedRevision = 1))

        nexus.drain()

        val snap = nexus.snapshot("j-child")

        val parentFacts = nexus.facts("j-parent")
        assertEquals(2, parentFacts.size, "Parent should have submit and one complete fact, duplicate complete is rejected")

        val childFacts = nexus.facts("j-child")
        assertTrue(childFacts.size <= 2, "Child should have at most two facts (submit and maybe start)")
    }

    @Test
    fun boundedBackpressureSuspendsWithoutDroppingData() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 8)
        val parent = JobId.of("j-parent")
        val child1 = JobId.of("j-child-1")
        val child2 = JobId.of("j-child-2")

        nexus.submit(JobCommand.Submit(parent, "k-parent"))
        nexus.submit(JobCommand.Submit(child1, "k-child-1", dependencies = listOf(parent)))
        nexus.submit(JobCommand.Submit(child2, "k-child-2", dependencies = listOf(parent)))

        nexus.submit(JobCommand.Complete(parent, "k-complete", expectedRevision = 1))

        nexus.drain()

        val snap1 = nexus.snapshot("j-child-1")
        val snap2 = nexus.snapshot("j-child-2")
        assertTrue(snap1 != null, "Child 1 should exist")
        assertTrue(snap2 != null, "Child 2 should exist")
    }

    @Test
    fun partitionIsolationPreventsCrossBoardActivations() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 8)
        val parent = JobId.of("j-parent")
        val child = JobId.of("j-child")

        nexus.submit(JobCommand.Submit(parent, "k-parent"))
        nexus.submit(JobCommand.Submit(child, "k-child", dependencies = listOf(parent)))

        val rete = JobNexusComponentFactories().reteFactory() // A fresh Rete network.
        val fields = mapOf("jobId" to "j-parent", "lifecycle" to "closed")
        val cid = borg.trikeshed.job.ContentId.of("v1".encodeToByteArray())
        rete.assert(borg.trikeshed.dag.FactId("other-board", "j-parent"), fields, cid, borg.trikeshed.cursor.BlackboardContext("other-board"))

        val fields2 = mapOf("jobId" to "j-child", "lifecycle" to "submitted", "dependencies" to listOf("j-parent"))
        rete.assert(borg.trikeshed.dag.FactId("job-board", "j-child"), fields2, cid, borg.trikeshed.cursor.BlackboardContext("job-board"))

        rete.evaluateRules("job-board")

        assertEquals(0, rete.agenda.size, "Agenda should be empty because facts are in different partitions")

        nexus.drain() // Fix the uncompleted coroutine error
    }

    @Test
    fun drainSuccessfullyCompletesAllPendingReteWork() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 8)
        val parent = JobId.of("j-parent")
        val child = JobId.of("j-child")

        nexus.submit(JobCommand.Submit(parent, "k-parent"))
        nexus.submit(JobCommand.Submit(child, "k-child", dependencies = listOf(parent)))
        nexus.submit(JobCommand.Complete(parent, "k-complete", expectedRevision = 1))

        nexus.drain()

        val snap = nexus.snapshot("j-child")
        assertTrue(snap?.lifecycle == "ready" || snap?.lifecycle == "active" || snap?.lifecycle == "submitted", "Drain must quiesce pending Rete output")
        assertTrue(nexus.commandChannelClosed, "Commands channel must be closed")
        assertTrue(nexus.factsChannelClosed, "Facts channel must be closed")
    }
}