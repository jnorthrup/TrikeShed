package borg.trikeshed.context.nuid

import borg.trikeshed.job.JobEvent
import borg.trikeshed.job.JobId
import borg.trikeshed.job.JobSupervisorElement
import borg.trikeshed.userspace.reactor.KanbanEvent
import borg.trikeshed.userspace.reactor.KanbanFSM
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2 — the midpoint where the two dispatch lanes meet.
 *
 * NUID claim selects *who*: a registered [Workgroup] claims a
 * [Capability.Model] nuid at [Subnet.local]. JobCommand commits *what*: a
 * [KanbanEvent.SignalFacetReduced] becomes a `JobCommand.Submit` that leaves
 * the durability pipeline as a committed `JobEvent`.
 */
class ModelWorkgroupJobBridgeTest {

    private fun facetEvent(
        sourceSignalId: String,
        reducedValue: String = "reduced",
        timestampMs: Long = 1L,
    ) = KanbanEvent.SignalFacetReduced(
        facetKey = "modelmux",
        reducedValue = reducedValue,
        sourceSignalId = sourceSignalId,
        timestampMs = timestampMs,
    )

    @Test
    fun registeredModelWorkgroupClaimsModelNuid() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        val workgroup = fanout.registerModelWorkgroup()

        assertEquals(MODEL_WORKGROUP_NAME, workgroup.name)
        assertEquals(Subnet.local, workgroup.scope)
        assertTrue(workgroup.traits.can(Capability.Model), "trait space must admit Capability.Model")
        assertEquals("modelmux", Capability.Model.category)

        val request = nuid(Capability.Model, Nonce.RandomBytes(), Subnet.local)
        val result = fanout.dispatch(request, payload = "prompt", timeoutMillis = 500L)

        assertEquals(MODEL_WORKGROUP_NAME, result.winner, "the model workgroup must claim a Capability.Model nuid")
        assertEquals(Subnet.local, result.claimedAtSubnet)

        fanout.close()
    }

    @Test
    fun unrelatedCapabilityDoesNotRouteToModelWorkgroup() = runTest {
        val fanout = NuidFanoutElement()
        fanout.open()
        fanout.registerModelWorkgroup()

        val request = nuid(Capability.Cas("read"), Nonce.RandomBytes(), Subnet.local)
        val result = fanout.dispatch(request, payload = null, timeoutMillis = 50L)

        assertNull(result.winner, "trait × subnet must both gate — a cas nuid is not modelmux work")

        fanout.close()
    }

    @Test
    fun signalFacetReducedCommitsAJobEvent() = runTest {
        val jobs = JobSupervisorElement.open(this, capacity = 64)
        val bridge = SignalJobBridgeElement(jobs = jobs)
        bridge.open()

        val event = facetEvent(sourceSignalId = "sig-direct-1")
        val jobId = bridge.bridge(event)
        assertNotNull(jobId, "an OPEN bridge must commit a reduced facet")
        assertEquals(signalFacetJobId(event), jobId)

        val committed = jobs.committed.receive()
        val accepted = assertNotNull(
            committed as? JobEvent.Accepted,
            "the submit must leave the pipeline committed, was $committed",
        )
        assertEquals(jobId, accepted.jobId)
        assertEquals("submitted", jobs.snapshot(jobId)?.lifecycle)

        // The same occurrence replayed off the flow's replay buffer is a no-op.
        assertNull(bridge.bridge(event), "a replayed occurrence must not double-commit")
        assertEquals(1, bridge.submitted)

        jobs.cancel()
        bridge.close()
    }

    /**
     * Regression: the only production emitter builds `sourceSignalId` from the
     * facet mark plus two hard-coded spine marks, so consecutive reductions of
     * one facet share it verbatim. Keyed on `sourceSignalId` alone the bridge
     * would commit one job per facet, ever, and drop everything after it.
     */
    @Test
    fun distinctReductionsSharingASourceSignalIdBothCommit() = runTest {
        val jobs = JobSupervisorElement.open(this, capacity = 64)
        val bridge = SignalJobBridgeElement(jobs = jobs)
        bridge.open()

        val constantSignalId = "lcnc:logic:dispatched:afterget"
        val first = facetEvent(constantSignalId, reducedValue = "first", timestampMs = 10L)
        val second = facetEvent(constantSignalId, reducedValue = "second", timestampMs = 11L)

        val firstId = assertNotNull(bridge.bridge(first))
        val secondId = assertNotNull(bridge.bridge(second), "a second reduction must not be mistaken for a replay")
        assertNotEquals(firstId, secondId, "distinct occurrences must mint distinct jobs")
        assertNotEquals(signalOccurrenceKey(first), signalOccurrenceKey(second))

        val committedIds = setOf(
            (jobs.committed.receive() as JobEvent.Accepted).jobId,
            (jobs.committed.receive() as JobEvent.Accepted).jobId,
        )
        assertEquals(setOf(firstId, secondId), committedIds)
        assertEquals(2, bridge.submitted)

        jobs.cancel()
        bridge.close()
    }

    @Test
    fun wiredBridgeCarriesKanbanEventsIntoTheJobNexus() = runTest {
        val jobs = JobSupervisorElement.open(this, capacity = 64)
        val fanout = NuidFanoutElement()
        // A dedicated flow: KanbanFSM.kanbanEvents is process-wide with a
        // 64-deep replay buffer, so sibling suites would otherwise decide how
        // many committed events this test has to scan past.
        val events = MutableSharedFlow<KanbanEvent>(
            replay = 64,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val wiring = wireSignalFacetsToJobs(scope = this, jobs = jobs, fanout = fanout, events = events)
        val workgroup = wiring.a
        val bridge = wiring.b.a

        // The NUID lane is live: the wiring registered the model workgroup.
        assertEquals(MODEL_WORKGROUP_NAME, workgroup.name)
        assertNotNull(fanout.slotOf(MODEL_WORKGROUP_NAME))

        val event = facetEvent(sourceSignalId = "sig-wired-1", timestampMs = 2L)
        val expectedJobId: JobId = signalFacetJobId(event)
        events.emit(event)

        val committed = jobs.committed.receive()
        val accepted = assertNotNull(
            committed as? JobEvent.Accepted,
            "the collected SignalFacetReduced must commit as a JobEvent, was $committed",
        )
        assertEquals(expectedJobId, accepted.jobId)
        assertEquals("submitted", jobs.snapshot(expectedJobId)?.lifecycle)
        assertEquals(1, bridge.submitted)

        // close() must stop the collector: it was launched on the element's
        // own supervisor, not on the caller's scope.
        bridge.close()
        jobs.cancel()
        fanout.close()
    }

    /** The default event source is the process-wide kanban flow. */
    @Test
    fun bridgeDefaultsToTheKanbanEventFlow() = runTest {
        val jobs = JobSupervisorElement.open(this, capacity = 8)
        val bridge = SignalJobBridgeElement(jobs = jobs)

        assertTrue(
            bridge.eventSource === KanbanFSM.kanbanEvents,
            "an unconfigured bridge must attach to KanbanFSM.kanbanEvents",
        )

        jobs.cancel()
    }
}
