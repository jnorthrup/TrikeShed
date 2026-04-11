package borg.trikeshed.net

import borg.trikeshed.net.channelization.*
import borg.trikeshed.net.spi.TransportBackendKind
import borg.trikeshed.net.spi.TransportCapabilities
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HttpIngressProtocolTest {
    @Test
    fun `test HTTP ingress protocol supports HTTP protocol`() {
        val provider = HttpIngressProtocolProvider()
        val request = ChannelizationRequest(ProtocolId.HTTP)

        assertTrue(provider.supports(request), "Provider should support HTTP protocol")
    }

    @Test
    fun `test HTTP ingress protocol does not support non-HTTP protocols`() {
        val provider = HttpIngressProtocolProvider()
        val request = ChannelizationRequest(ProtocolId.QUIC)

        assertFalse(provider.supports(request), "Provider should not support non-HTTP protocols")
    }

    @Test
    fun `test HTTP ingress protocol plan creation`() = runTest {
        val provider = HttpIngressProtocolProvider()
        val request = ChannelizationRequest(ProtocolId.HTTP)

        val plan = provider.plan(request)

        assertEquals(ProtocolId.HTTP, plan.protocol, "Plan should have HTTP protocol")
        assertEquals(ChannelSemantics.BYTE_STREAM, plan.semantics, "Plan should have BYTE_STREAM semantics")
        assertEquals(ChannelizationPath.DIRECT_SERVICE, plan.path, "Plan should use DIRECT_SERVICE path")
        assertEquals("http-ingress", plan.provider, "Plan should have http-ingress provider")
        assertEquals(10, plan.estimatedCost, "Plan should have cost of 10")
    }

    @Test
    fun `test HTTP ingress protocol processes request and creates response`() {
        val protocol = HttpIngressProtocol()
        val sessionId = ChannelSessionId("test-session")

        val request =
            HttpLikeRequest(
                method = "GET",
                path = "/test",
                headers = mapOf("Host" to "localhost", "Accept" to "text/plain"),
            )

        val responseEnvelope = protocol.processRequest(request, sessionId)

        assertNotNull(responseEnvelope, "Response envelope should not be null")
        assertEquals(TransferDirection.Ingress, responseEnvelope.direction, "Response should be ingress")
        assertEquals(ProtocolId.HTTP, responseEnvelope.protocol, "Response should be HTTP")

        val responseBlock = responseEnvelope.block
        assertNotNull(responseBlock, "Response block should not be null")
        assertEquals(sessionId, responseBlock.session, "Response block should have correct session ID")

        val response = HttpLikeResponse.decodeFromBlock(responseBlock)
        assertEquals(200, response.status, "Response status should be 200")
        assertEquals("OK", response.statusText, "Response status text should be OK")

        assertTrue(response.headers.containsKey("Content-Type"), "Response should have Content-Type header")
        assertTrue(response.headers.containsKey("Content-Length"), "Response should have Content-Length header")

        assertTrue(response.body.isNotEmpty(), "Response body should not be empty")
        assertTrue(String(response.body).contains("You requested:"), "Response body should contain request info")
        assertTrue(String(response.body).contains("Method: GET"), "Response body should contain method info")
        assertTrue(String(response.body).contains("Path: /test"), "Response body should contain path info")
        assertTrue(String(response.body).contains("Host: localhost"), "Response body should contain host header")
        assertTrue(String(response.body).contains("Accept: text/plain"), "Response body should contain accept header")
    }

    @Test
    fun `test HTTP ingress job lifecycle`() =
        runTest {
            val graphId = ChannelGraphId("test-graph")
            val owner = WorkerKey("test-worker")
            val sessionId = ChannelSessionId("test-session")

            val protocol = HttpIngressProtocol()
            val request =
                HttpLikeRequest(
                    method = "POST",
                    path = "/submit",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "{\"key\": \"value\"}".encodeToByteArray(),
                )

            val job =
                HttpIngressJob(
                    id = ChannelJobId("test-job"),
                    graphId = graphId,
                    owner = owner,
                    type = JobType.CUSTOM,
                    state = ChannelJobState.Pending,
                    priority = 5,
                    sessionId = sessionId,
                    protocol = protocol,
                    request = request,
                )

            assertEquals(ChannelJobState.Pending, job.state, "Job should start in Pending state")

            val result = job.start()
            assertEquals(ChannelJobState.Completed, job.state, "Job should complete in Completed state")

            assertTrue(result is JobResult.Success, "Job should complete successfully")
            val successResult = result as JobResult.Success
            assertNotNull(successResult.value, "Success result should contain value")
            assertTrue(successResult.value is ChannelEnvelope, "Success value should be ChannelEnvelope")

            val envelope = successResult.value as ChannelEnvelope
            assertEquals(sessionId, envelope.block.session, "Envelope should have correct session ID")
            assertEquals(TransferDirection.Ingress, envelope.direction, "Envelope should be ingress")
            assertEquals(ProtocolId.HTTP, envelope.protocol, "Envelope should be HTTP")
        }

    @Test
    fun `test HTTP ingress activation rule matches protocol requirement`() {
        val rule = HttpIngressActivationRule()

        val graph =
            SimpleChannelGraph(
                id = ChannelGraphId("test-graph"),
                owner = WorkerKey("test-worker"),
                initialFacts =
                    listOf(
                        GraphFact.ProtocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM, true),
                    ),
            )

        assertTrue(rule.matches(graph), "Rule should match HTTP protocol requirement")
    }

    @Test
    fun `test HTTP ingress activation rule does not match non-HTTP protocol`() {
        val rule = HttpIngressActivationRule()

        val graph =
            SimpleChannelGraph(
                id = ChannelGraphId("test-graph"),
                owner = WorkerKey("test-worker"),
                initialFacts =
                    listOf(
                        GraphFact.ProtocolRequirement(ProtocolId.QUIC, ChannelSemantics.MESSAGE_STREAM, true),
                    ),
            )

        assertFalse(rule.matches(graph), "Rule should not match non-HTTP protocol")
    }

    @Test
    fun `test HTTP ingress activation rule creates job with sample request`() {
        val rule = HttpIngressActivationRule()
        val graph =
            SimpleChannelGraph(
                id = ChannelGraphId("test-graph"),
                owner = WorkerKey("test-worker"),
            )

        val context =
            JobActivationContext(
                graphId = graph.id,
                sessionId = ChannelSessionId("test-session"),
                triggeringFact = GraphFact.ProtocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM, true),
                timestamp = Clocks.System.now(),
            )

        val job = rule.activate(graph, context)

        assertNotNull(job, "Rule should create a job")
        assertEquals(JobType.CUSTOM, job.type, "Job should be CUSTOM type")
        assertEquals(graph.id, job.graphId, "Job should have correct graph ID")
        assertEquals(graph.owner, job.owner, "Job should have correct owner")
    }

    @Test
    fun `test HTTP ingress protocol integration through assembly graph job block flow`() =
        runTest {
            // This test demonstrates the complete flow: assembly -> graph -> job -> block

            // 1. Create assembly (represented by channelization request)
            val request = ChannelizationRequest(ProtocolId.HTTP)

            // 2. Create graph with protocol requirement
            val graphId = ChannelGraphId("integration-graph")
            val workerKey = WorkerKey("integration-worker")

            val graph =
                SimpleChannelGraph(
                    id = graphId,
                    owner = workerKey,
                    activationRules = listOf(HttpIngressActivationRule()),
                    initialFacts =
                        listOf(
                            GraphFact.ProtocolRequirement(ProtocolId.HTTP, ChannelSemantics.BYTE_STREAM, true),
                        ),
                )

            // 3. Activate jobs based on facts
            val newJobs = graph.activateJobss()
            assertEquals(1, newJobs.size, "Graph should activate 1 job for HTTP protocol")

            // 4. Start the job
            val job = newJobs.first()
            val result = job.start()

            // 5. Verify job completed successfully
            assertEquals(ChannelJobState.Completed, job.state, "Job should complete successfully")
            assertTrue(result is JobResult.Success, "Job should complete successfully")

            // 6. Verify the result contains a response envelope
            val successResult = result as JobResult.Success
            assertNotNull(successResult.value, "Success result should contain value")
            assertTrue(successResult.value is ChannelEnvelope, "Success value should be ChannelEnvelope")

            val envelope = successResult.value as ChannelEnvelope
            assertEquals(ProtocolId.HTTP, envelope.protocol, "Envelope should be HTTP")
            assertEquals(TransferDirection.Ingress, envelope.direction, "Envelope should be ingress")

            // 7. Verify the block contains HTTP response data
            val responseBlock = envelope.block
            val response = HttpLikeResponse.decodeFromBlock(responseBlock)
            assertEquals(200, response.status, "Response status should be 200")
            assertTrue(response.body.isNotEmpty(), "Response body should not be empty")

            // This demonstrates the complete flow: assembly (request) -> graph (facts) -> job (processing) -> block (response)
        }
}
