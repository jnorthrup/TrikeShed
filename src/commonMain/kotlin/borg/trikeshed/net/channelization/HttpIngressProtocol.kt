package borg.trikeshed.net.channelization

import borg.trikeshed.net.ProtocolId
import borg.trikeshed.net.spi.TransportBackendKind
import borg.trikeshed.net.spi.TransportCapabilities

class HttpIngressProtocol {
    fun processRequest(
        request: HttpLikeRequest,
        sessionId: ChannelSessionId,
    ): ChannelEnvelope {
        val response = createEchoResponse(request)
        val responseBlock = response.encodeToBlock(sessionId, BlockSequence(0), 1L)
        return ChannelEnvelope(
            block = responseBlock,
            direction = TransferDirection.Ingress,
            protocol = ProtocolId.HTTP,
            timestamp = 0L,
        )
    }

    private fun createEchoResponse(request: HttpLikeRequest): HttpLikeResponse {
        val body =
            buildString {
                appendLine("You requested:")
                appendLine("Method: ${request.method}")
                appendLine("Path: ${request.path}")
                appendLine("Headers:")
                request.headers.forEach { (name, value) -> appendLine("  $name: $value") }
                if (request.body.isNotEmpty()) {
                    appendLine("\nBody:")
                    appendLine(request.body.decodeToString())
                }
            }.encodeToByteArray()

        return HttpLikeResponse(
            status = 200,
            statusText = "OK",
            headers = mapOf("Content-Type" to "text/plain", "Content-Length" to body.size.toString()),
            body = body,
        )
    }
}

class HttpIngressProtocolProvider : ChannelizationProvider {
    override val name: String = "http-ingress"

    override fun supports(request: ChannelizationRequest): Boolean = request.protocol == ProtocolId.HTTP

    override fun estimateCost(
        request: ChannelizationRequest,
        capabilities: TransportCapabilities?,
    ): Int = 10

    override suspend fun plan(
        request: ChannelizationRequest,
        capabilities: TransportCapabilities?,
    ): ChannelizationPlan =
        ChannelizationPlan(
            protocol = request.protocol,
            semantics = ChannelSemantics.BYTE_STREAM,
            path = ChannelizationPath.DIRECT_SERVICE,
            provider = name,
            estimatedCost = 10,
            backendKind = capabilities?.backendKind,
        )
}

class HttpIngressJob(
    override val id: ChannelJobId,
    override val graphId: ChannelGraphId,
    override val owner: WorkerKey,
    override val type: JobType,
    override var state: ChannelJobState,
    override val priority: Int,
    override val sessionId: ChannelSessionId?,
    private val protocol: HttpIngressProtocol,
    private val request: HttpLikeRequest,
) : ChannelJob {
    override suspend fun start(): JobResult {
        state = ChannelJobState.Running
        val responseEnvelope = protocol.processRequest(request, sessionId!!)
        state = ChannelJobState.Completed
        return JobResult.Success(responseEnvelope)
    }

    override suspend fun pause() {
        state = ChannelJobState.Waiting
    }

    override suspend fun resume() {
        state = ChannelJobState.Running
    }

    override suspend fun cancel() {
        state = ChannelJobState.Cancelled
    }

    override fun transitionTo(newState: ChannelJobState) {
        state = newState
    }
}

class HttpIngressActivationRule : ActivationRule {
    override fun matches(graph: ChannelGraph): Boolean =
        graph.facts.any { it is GraphFact.ProtocolRequirement && it.protocol == ProtocolId.HTTP && it.required }

    override fun activate(
        graph: ChannelGraph,
        context: JobActivationContext,
    ): ChannelJob {
        val sampleRequest = HttpLikeRequest(method = "GET", path = "/", headers = mapOf("Host" to "localhost:8080"))
        val protocol = HttpIngressProtocol()
        return HttpIngressJob(
            id = ChannelJobId("http-ingress-${graph.id.raw}"),
            graphId = graph.id,
            owner = graph.owner ?: WorkerKey("default"),
            type = JobType.CUSTOM,
            state = ChannelJobState.Pending,
            priority = 5,
            sessionId = ChannelSessionId("http-session-${graph.id.raw}"),
            protocol = protocol,
            request = sampleRequest,
        )
    }
}
