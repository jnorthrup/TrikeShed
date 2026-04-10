package borg.trikeshed.net.channelization

import borg.trikeshed.context.IoCapability
import borg.trikeshed.net.ProtocolId
import borg.trikeshed.net.spi.TransportBackendKind
import borg.trikeshed.net.spi.TransportCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ChannelGraphIntegrationTest {

    @Test
    fun testBuildActivationRules() {
        val plan = ChannelizationPlan(
            protocol = ProtocolId.HTTP,
            semantics = ChannelSemantics.BYTE_STREAM,
            path = ChannelizationPath.TRANSPORT_BACKEND,
            provider = "test-provider",
            estimatedCost = 10,
            backendKind = TransportBackendKind.SELECTOR
        )

        val rules = buildActivationRules(plan)

        assertEquals(3, rules.size)
        assertEquals(JobType.HANDSHAKE, rules[0].let { (it as PatternActivationRule).jobType })
        assertEquals(JobType.DATA_TRANSFER, rules[1].let { (it as PatternActivationRule).jobType })
        assertEquals(JobType.FLOW_CONTROL, rules[2].let { (it as PatternActivationRule).jobType })
    }

    @Test
    fun testBuildActivationRulesWithoutBackend() {
        val plan = ChannelizationPlan(
            protocol = ProtocolId.QUIC,
            semantics = ChannelSemantics.MESSAGE_STREAM,
            path = ChannelizationPath.DIRECT_SERVICE,
            provider = "quic-service",
            estimatedCost = 0
        )

        val rules = buildActivationRules(plan)

        assertEquals(2, rules.size)
    }

    @Test
    fun testCreateGraphForPlan() {
        val plan = ChannelizationPlan(
            protocol = ProtocolId.HTTP,
            semantics = ChannelSemantics.BYTE_STREAM,
            path = ChannelizationPath.TRANSPORT_BACKEND,
            provider = "http-backend",
            estimatedCost = 20,
            backendKind = TransportBackendKind.SELECTOR
        )

        val request = ChannelizationRequest(ProtocolId.HTTP, IoCapability.NIO)
        val graph = createGraphForPlan(plan, request)

        assertNotNull(graph)
        assertTrue(graph.id.raw.startsWith("graph-HTTP"))
        assertEquals("worker-http-backend", graph.owner?.raw)
        assertTrue(graph.facts.isNotEmpty())

        // Verify facts were added
        val protocolFact = graph.queryFacts { it is GraphFact.ProtocolRequirement }.firstOrNull()
        assertNotNull(protocolFact)

        val capabilityFact = graph.queryFacts { it is GraphFact.CapabilityFact }.firstOrNull()
        assertNotNull(capabilityFact)

        val pathFact = graph.queryFacts { it is GraphFact.CustomFact && it.key == "path" }.firstOrNull()
        assertNotNull(pathFact)
    }

    @Test
    fun testCreateGraphForPlanWithService() {
        val graphService = SimpleChannelGraphService()
        val plan = ChannelizationPlan(
            protocol = ProtocolId.QUIC,
            semantics = ChannelSemantics.MESSAGE_STREAM,
            path = ChannelizationPath.DIRECT_SERVICE,
            provider = "quic-service",
            estimatedCost = 0
        )

        val request = ChannelizationRequest(ProtocolId.QUIC)
        val graph = createGraphForPlan(plan, request, graphService)

        assertNotNull(graph)
        assertNotNull(graphService.getGraph(graph.id))
    }

    @Test
    fun testGraphActivationWithJobs() {
        val plan = ChannelizationPlan(
            protocol = ProtocolId.HTTP,
            semantics = ChannelSemantics.BYTE_STREAM,
            path = ChannelizationPath.TRANSPORT_BACKEND,
            provider = "http-backend",
            estimatedCost = 20
        )

        val request = ChannelizationRequest(ProtocolId.HTTP)
        val graph = createGraphForPlan(plan, request)

        // Transition to active state
        graph.transitionTo(ChannelGraphState.Active)

        // Activate jobs
        val activatedJobs = graph.activateJobss()

        assertTrue(activatedJobs.isNotEmpty())
        assertTrue(graph.jobs.isNotEmpty())

        // Verify job types
        val jobTypes = graph.jobs.map { it.type }.toSet()
        assertTrue(JobType.HANDSHAKE in jobTypes)
        assertTrue(JobType.DATA_TRANSFER in jobTypes)
    }

    @Test
    fun testGetActiveJobs() {
        val graph = SimpleChannelGraph(
            id = ChannelGraphId("test-active-jobs"),
            owner = WorkerKey("owner"),
            initialFacts = listOf(
                protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM)
            ),
            activationRules = listOf(
                PatternActivationRule(
                    factPattern = { it is GraphFact.ProtocolRequirement },
                    jobType = JobType.HANDSHAKE
                )
            )
        )

        graph.transitionTo(ChannelGraphState.Active)
        graph.activateJobss()

        // Start the jobs to make them active
        graph.jobs.filter { it.state == ChannelJobState.Pending }
            .forEach { it.transitionTo(ChannelJobState.Running) }

        val activeJobs = graph.getActiveJobs()
        assertTrue(activeJobs.isNotEmpty())

        val handshakeJob = graph.getActiveJob(JobType.HANDSHAKE)
        assertNotNull(handshakeJob)
    }

    @Test
    fun testGraphMetadata() {
        val plan = ChannelizationPlan(
            protocol = ProtocolId.QUIC,
            semantics = ChannelSemantics.MESSAGE_STREAM,
            path = ChannelizationPath.DIRECT_SERVICE,
            provider = "quic-service",
            estimatedCost = 0
        )

        val request = ChannelizationRequest(ProtocolId.QUIC)
        val graph = createGraphForPlan(plan, request)

        if (graph is SimpleChannelGraph) {
            assertEquals("QUIC", graph.metadata["protocol"])
            assertEquals("MESSAGE_STREAM", graph.metadata["semantics"])
            assertEquals("DIRECT_SERVICE", graph.metadata["path"])
        }
    }

    @Test
    fun testGraphWithSessionFact() {
        val graph = SimpleChannelGraph(
            id = ChannelGraphId("graph-session"),
            owner = null,
            initialFacts = listOf(
                protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM)
            ),
            activationRules = listOf(
                PatternActivationRule(
                    factPattern = { it is GraphFact.SessionFact && it.active },
                    jobType = JobType.DATA_TRANSFER
                )
            )
        )

        // Initially no session fact, so no data transfer job should activate
        graph.transitionTo(ChannelGraphState.Active)
        var jobs = graph.activateJobss()
        assertEquals(0, jobs.size)

        // Add session fact
        graph.addFact(sessionFact(ChannelSessionId("session-1"), ProtocolId.HTTP))
        jobs = graph.activateJobss()
        assertEquals(1, jobs.size)
        assertEquals(JobType.DATA_TRANSFER, jobs[0].type)
    }

    @Test
    fun testGraphJobPriority() {
        val plan = ChannelizationPlan(
            protocol = ProtocolId.HTTP,
            semantics = ChannelSemantics.BYTE_STREAM,
            path = ChannelizationPath.TRANSPORT_BACKEND,
            provider = "http-backend",
            estimatedCost = 20
        )

        val rules = buildActivationRules(plan)

        // Verify priorities are set correctly
        val handshakeRule = rules.find { (it as PatternActivationRule).jobType == JobType.HANDSHAKE } as PatternActivationRule
        val dataTransferRule = rules.find { (it as PatternActivationRule).jobType == JobType.DATA_TRANSFER } as PatternActivationRule

        assertEquals(10, handshakeRule.priority)
        assertEquals(5, dataTransferRule.priority)
    }

    @Test
    fun testGraphStateAffectsJobActivation() {
        val graph = SimpleChannelGraph(
            id = ChannelGraphId("graph-state-test"),
            owner = null,
            initialFacts = listOf(
                protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM)
            ),
            activationRules = listOf(
                PatternActivationRule(
                    factPattern = { it is GraphFact.ProtocolRequirement },
                    jobType = JobType.HANDSHAKE
                )
            )
        )

        // Can activate jobs in Initializing state
        var jobs = graph.activateJobss()
        assertEquals(1, jobs.size)

        // Clear jobs for next test
        graph.transitionTo(ChannelGraphState.Suspended)

        // Jobs can still be queried but graph reports it can't accept new ones
        assertFalse(graph.canAcceptJobs())

        graph.transitionTo(ChannelGraphState.Terminated)
        assertFalse(graph.canAcceptJobs())
    }

    @Test
    fun testGraphServiceAssignment() {
        val service = SimpleChannelGraphService()
        val config = ChannelGraphConfig(
            id = ChannelGraphId("assign-graph"),
            owner = null
        )

        val graph = service.getOrCreateGraph(config)
        service.assignWorker(graph.id, WorkerKey("assigned-worker"))

        // In the simple implementation, assignment doesn't change the graph
        // but the method should not throw
        assertNotNull(service.getGraph(graph.id))
    }

    @Test
    fun testGraphQueryFactsByType() {
        val graph = SimpleChannelGraph(
            id = ChannelGraphId("query-graph"),
            owner = null,
            initialFacts = listOf(
                protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM),
                protocolRequirement(ProtocolId.QUIC, ChannelSemantics.MESSAGE_STREAM),
                capabilityFact(IoCapability.NIO),
                capabilityFact(IoCapability.URING),
                sessionFact(ChannelSessionId("s1"), ProtocolId.HTTP)
            )
        )

        val protocolFacts = graph.queryFacts { it is GraphFact.ProtocolRequirement }
        assertEquals(2, protocolFacts.size)

        val capabilityFacts = graph.queryFacts { it is GraphFact.CapabilityFact }
        assertEquals(2, capabilityFacts.size)

        val sessionFacts = graph.queryFacts { it is GraphFact.SessionFact }
        assertEquals(1, sessionFacts.size)

        val nioFacts = graph.queryFacts {
            it is GraphFact.CapabilityFact && it.capability == IoCapability.NIO
        }
        assertEquals(1, nioFacts.size)
    }
}
