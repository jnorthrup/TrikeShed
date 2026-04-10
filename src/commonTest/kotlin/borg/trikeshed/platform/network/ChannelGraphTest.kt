package borg.trikeshed.net.channelization

import borg.trikeshed.context.IoCapability
import borg.trikeshed.net.ProtocolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelGraphTest {

    @Test
    fun testChannelGraphId() {
        val id = ChannelGraphId("test-graph-123")
        assertEquals("test-graph-123", id.raw)
    }

    @Test
    fun testWorkerKey() {
        val key = WorkerKey("worker-alpha")
        assertEquals("worker-alpha", key.raw)
    }

    @Test
    fun testChannelJobId() {
        val id = ChannelJobId("job-001")
        assertEquals("job-001", id.raw)
    }

    @Test
    fun testGraphFactTypes() {
        val protocolFact = GraphFact.ProtocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM)
        assertTrue(protocolFact is GraphFact.ProtocolRequirement)
        assertEquals(ProtocolId.HTTP, protocolFact.protocol)

        val capabilityFact = GraphFact.CapabilityFact(IoCapability.NIO)
        assertTrue(capabilityFact is GraphFact.CapabilityFact)
        assertEquals(IoCapability.NIO, capabilityFact.capability)

        val sessionId = ChannelSessionId("session-1")
        val sessionFact = GraphFact.SessionFact(sessionId, ProtocolId.HTTP)
        assertTrue(sessionFact is GraphFact.SessionFact)
        assertEquals(sessionId, sessionFact.sessionId)

        val jobFact = GraphFact.JobFact(ChannelJobId("job-1"), "HANDSHAKE")
        assertTrue(jobFact is GraphFact.JobFact)
        assertEquals("HANDSHAKE", jobFact.jobType)

        val customFact = GraphFact.CustomFact("key", "value")
        assertTrue(customFact is GraphFact.CustomFact)
        assertEquals("key", customFact.key)
        assertEquals("value", customFact.value)
    }

    @Test
    fun testDependencyType() {
        val fact1 = GraphFact.CustomFact("a", 1)
        val fact2 = GraphFact.CustomFact("b", 2)
        val dep = GraphFact.DependencyFact(fact1, fact2, DependencyType.REQUIRES)

        assertTrue(dep is GraphFact.DependencyFact)
        assertEquals(DependencyType.REQUIRES, dep.type)
    }

    @Test
    fun testChannelGraphState() {
        assertTrue(ChannelGraphState.Initializing is ChannelGraphState)
        assertTrue(ChannelGraphState.Active is ChannelGraphState)
        assertTrue(ChannelGraphState.Suspended is ChannelGraphState)
        assertTrue(ChannelGraphState.Terminating is ChannelGraphState)
        assertTrue(ChannelGraphState.Terminated is ChannelGraphState)

        val error = RuntimeException("test")
        val failed = ChannelGraphState.Failed(error)
        assertEquals(error, failed.reason)
    }

    @Test
    fun testChannelJobState() {
        assertTrue(ChannelJobState.Pending is ChannelJobState)
        assertTrue(ChannelJobState.Running is ChannelJobState)
        assertTrue(ChannelJobState.Waiting is ChannelJobState)
        assertTrue(ChannelJobState.Completed is ChannelJobState)
        assertTrue(ChannelJobState.Cancelled is ChannelJobState)

        val error = RuntimeException("job failed")
        val failed = ChannelJobState.Failed(error)
        assertEquals(error, failed.reason)
    }

    @Test
    fun testJobType() {
        assertEquals(JobType.HANDSHAKE, JobType.HANDSHAKE)
        assertEquals(JobType.DATA_TRANSFER, JobType.DATA_TRANSFER)
        assertEquals(JobType.FLOW_CONTROL, JobType.FLOW_CONTROL)
        assertEquals(JobType.KEEP_ALIVE, JobType.KEEP_ALIVE)
        assertEquals(JobType.TEARDOWN, JobType.TEARDOWN)
        assertEquals(JobType.CUSTOM, JobType.CUSTOM)
    }

    @Test
    fun testSimpleChannelJob() {
        val job = SimpleChannelJob(
            id = ChannelJobId("job-test"),
            graphId = ChannelGraphId("graph-test"),
            owner = WorkerKey("worker-1"),
            type = JobType.HANDSHAKE,
            state = ChannelJobState.Pending,
            priority = 10,
            sessionId = ChannelSessionId("session-1")
        )

        assertEquals("job-test", job.id.raw)
        assertEquals("graph-test", job.graphId.raw)
        assertEquals("worker-1", job.owner.raw)
        assertEquals(JobType.HANDSHAKE, job.type)
        assertEquals(10, job.priority)
        assertFalse(job.isActive())

        // Test state transitions
        job.transitionTo(ChannelJobState.Running)
        assertTrue(job.isActive())

        job.transitionTo(ChannelJobState.Cancelled)
        assertFalse(job.isActive())
    }

    @Test
    fun testSimpleChannelJobLifecycle() {
        val job = SimpleChannelJob(
            id = ChannelJobId("job-lifecycle"),
            graphId = ChannelGraphId("graph-lifecycle"),
            owner = WorkerKey("worker-1"),
            type = JobType.DATA_TRANSFER,
            state = ChannelJobState.Pending,
            priority = 5,
            sessionId = null
        )

        assertEquals(ChannelJobState.Pending, job.state)

        // Start job
        job.transitionTo(ChannelJobState.Running)
        assertEquals(ChannelJobState.Running, job.state)
        assertTrue(job.isActive())

        // Pause job
        job.transitionTo(ChannelJobState.Waiting)
        assertEquals(ChannelJobState.Waiting, job.state)
        assertTrue(job.isActive())

        // Resume job
        job.transitionTo(ChannelJobState.Running)
        assertEquals(ChannelJobState.Running, job.state)

        // Cancel job
        job.transitionTo(ChannelJobState.Cancelled)
        assertEquals(ChannelJobState.Cancelled, job.state)
        assertFalse(job.isActive())
    }

    @Test
    fun testSimpleChannelGraphCreation() {
        val graph = SimpleChannelGraph(
            id = ChannelGraphId("graph-1"),
            owner = WorkerKey("owner-1"),
            initialFacts = listOf(
                protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM)
            )
        )

        assertEquals("graph-1", graph.id.raw)
        assertEquals("owner-1", graph.owner?.raw)
        assertEquals(1, graph.facts.size)
        assertEquals(ChannelGraphState.Initializing, graph.state)
        assertTrue(graph.canAcceptJobs())
    }

    @Test
    fun testSimpleChannelGraphFactManagement() {
        val graph = SimpleChannelGraph(
            id = ChannelGraphId("graph-facts"),
            owner = null
        )

        assertEquals(0, graph.facts.size)

        val fact = protocolRequirement(ProtocolId.QUIC, ChannelSemantics.MESSAGE_STREAM)
        graph.addFact(fact)
        assertEquals(1, graph.facts.size)
        assertTrue(graph.facts.contains(fact))

        graph.removeFact(fact)
        assertEquals(0, graph.facts.size)
    }

    @Test
    fun testSimpleChannelGraphQueryFacts() {
        val graph = SimpleChannelGraph(
            id = ChannelGraphId("graph-query"),
            owner = null,
            initialFacts = listOf(
                protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM),
                protocolRequirement(ProtocolId.QUIC, ChannelSemantics.MESSAGE_STREAM, required = false),
                capabilityFact(IoCapability.NIO)
            )
        )

        val protocolFacts = graph.queryFacts { it is GraphFact.ProtocolRequirement }
        assertEquals(2, protocolFacts.size)

        val requiredFacts = graph.queryFacts {
            it is GraphFact.ProtocolRequirement && it.required
        }
        assertEquals(1, requiredFacts.size)

        val capabilityFacts = graph.queryFacts { it is GraphFact.CapabilityFact }
        assertEquals(1, capabilityFacts.size)
    }

    @Test
    fun testSimpleChannelGraphStateTransition() {
        val graph = SimpleChannelGraph(
            id = ChannelGraphId("graph-state"),
            owner = null
        )

        assertEquals(ChannelGraphState.Initializing, graph.state)

        graph.transitionTo(ChannelGraphState.Active)
        assertEquals(ChannelGraphState.Active, graph.state)
        assertTrue(graph.canAcceptJobs())

        graph.transitionTo(ChannelGraphState.Suspended)
        assertEquals(ChannelGraphState.Suspended, graph.state)
        assertFalse(graph.canAcceptJobs())

        graph.transitionTo(ChannelGraphState.Terminated)
        assertEquals(ChannelGraphState.Terminated, graph.state)
        assertFalse(graph.canAcceptJobs())
    }

    @Test
    fun testPatternActivationRule() {
        val rule = PatternActivationRule(
            factPattern = { it is GraphFact.ProtocolRequirement && it.required },
            jobType = JobType.HANDSHAKE,
            priority = 10
        )

        val graph = SimpleChannelGraph(
            id = ChannelGraphId("graph-rule"),
            owner = null,
            initialFacts = listOf(
                protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM, required = true)
            )
        )

        assertTrue(rule.matches(graph))

        val context = JobActivationContext(
            graphId = graph.id,
            sessionId = ChannelSessionId("session-1"),
            triggeringFact = graph.facts[0],
            timestamp = System.currentTimeMillis()
        )

        val job = rule.activate(graph, context)
        assertNotNull(job)
        assertEquals(JobType.HANDSHAKE, job.type)
        assertEquals(10, job.priority)
    }

    @Test
    fun testPatternActivationRuleNoMatch() {
        val rule = PatternActivationRule(
            factPattern = { it is GraphFact.ProtocolRequirement && it.required },
            jobType = JobType.HANDSHAKE
        )

        val graph = SimpleChannelGraph(
            id = ChannelGraphId("graph-nomatch"),
            owner = null,
            initialFacts = listOf(
                protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM, required = false)
            )
        )

        assertFalse(rule.matches(graph))
    }

    @Test
    fun testChannelGraphBuilder() {
        val graph = channelGraph(ChannelGraphId("builder-graph"))
            .owner(WorkerKey("builder-owner"))
            .fact(protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM))
            .fact(capabilityFact(IoCapability.NIO))
            .rule(
                PatternActivationRule(
                    factPattern = { it is GraphFact.ProtocolRequirement },
                    jobType = JobType.HANDSHAKE
                )
            )
            .meta("test", "value")
            .build()

        assertEquals("builder-graph", graph.id.raw)
        assertEquals("builder-owner", graph.owner?.raw)
        assertEquals(2, graph.facts.size)
        assertTrue(graph.canAcceptJobs())
    }

    @Test
    fun testChannelGraphActivation() {
        val rule = PatternActivationRule(
            factPattern = { it is GraphFact.ProtocolRequirement && it.required },
            jobType = JobType.HANDSHAKE,
            priority = 10
        )

        val graph = SimpleChannelGraph(
            id = ChannelGraphId("graph-activate"),
            owner = WorkerKey("owner-1"),
            initialFacts = listOf(
                protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM, required = true)
            ),
            activationRules = listOf(rule)
        )

        assertEquals(0, graph.jobs.size)

        graph.transitionTo(ChannelGraphState.Active)
        val activatedJobs = graph.activateJobss()

        assertEquals(1, activatedJobs.size)
        assertEquals(1, graph.jobs.size)
        assertEquals(JobType.HANDSHAKE, graph.jobs[0].type)
    }

    @Test
    fun testSimpleChannelGraphService() {
        val service = SimpleChannelGraphService()

        val config = ChannelGraphConfig(
            id = ChannelGraphId("service-graph"),
            owner = WorkerKey("service-owner"),
            initialFacts = listOf(protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM))
        )

        // Get or create
        val graph1 = service.getOrCreateGraph(config)
        assertNotNull(graph1)
        assertEquals("service-graph", graph1.id.raw)

        // Get existing
        val graph2 = service.getGraph(ChannelGraphId("service-graph"))
        assertNotNull(graph2)
        assertEquals(graph1, graph2)

        // List graphs
        val graphs = service.listGraphs()
        assertEquals(1, graphs.size)

        // Remove graph
        service.removeGraph(ChannelGraphId("service-graph"))
        assertNull(service.getGraph(ChannelGraphId("service-graph")))
    }

    @Test
    fun testHelperFunctions() {
        val protocolFact = protocolRequirement(ProtocolId.QUIC, ChannelSemantics.MESSAGE_STREAM)
        assertTrue(protocolFact is GraphFact.ProtocolRequirement)
        assertEquals(ProtocolId.QUIC, protocolFact.protocol)

        val capFact = capabilityFact(IoCapability.URING)
        assertTrue(capFact is GraphFact.CapabilityFact)
        assertEquals(IoCapability.URING, capFact.capability)

        val sessFact = sessionFact(ChannelSessionId("s1"), ProtocolId.HTTP)
        assertTrue(sessFact is GraphFact.SessionFact)
        assertEquals("s1", sessFact.sessionId.raw)

        val depFact = dependencyFact(protocolFact, capFact, DependencyType.PRECEDES)
        assertTrue(depFact is GraphFact.DependencyFact)
        assertEquals(DependencyType.PRECEDES, depFact.type)
    }

    @Test
    fun testJobActivationContext() {
        val context = JobActivationContext(
            graphId = ChannelGraphId("ctx-graph"),
            sessionId = ChannelSessionId("ctx-session"),
            triggeringFact = protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM),
            timestamp = 1234567890L
        )

        assertEquals("ctx-graph", context.graphId.raw)
        assertEquals("ctx-session", context.sessionId?.raw)
        assertEquals(1234567890L, context.timestamp)
    }

    @Test
    fun testJobResult() {
        val success = JobResult.Success("result-value")
        assertTrue(success is JobResult.Success)
        assertEquals("result-value", success.value)

        val error = RuntimeException("test error")
        val failure = JobResult.Failure(error)
        assertTrue(failure is JobResult.Failure)
        assertEquals(error, failure.error)

        val pending = JobResult.Pending
        assertTrue(pending is JobResult.Pending)
    }

    @Test
    fun testChannelJobConfig() {
        val config = ChannelJobConfig(
            type = JobType.DATA_TRANSFER,
            priority = 5,
            timeout = 5000L,
            metadata = mapOf("key" to "value")
        )

        assertEquals(JobType.DATA_TRANSFER, config.type)
        assertEquals(5, config.priority)
        assertEquals(5000L, config.timeout)
        assertEquals("value", config.metadata["key"])
    }

    @Test
    fun testChannelGraphConfig() {
        val config = ChannelGraphConfig(
            id = ChannelGraphId("config-graph"),
            owner = WorkerKey("config-owner"),
            initialFacts = listOf(protocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM)),
            maxJobs = 100,
            metadata = mapOf("env" to "test")
        )

        assertEquals("config-graph", config.id.raw)
        assertEquals("config-owner", config.owner?.raw)
        assertEquals(1, config.initialFacts.size)
        assertEquals(100, config.maxJobs)
        assertEquals("test", config.metadata["env"])
    }
}
