package borg.trikeshed.net.channelization

import borg.trikeshed.ccek.coroutineService
import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.ccek.transport.QuicChannelService
import borg.trikeshed.context.IoCapability
import borg.trikeshed.context.ioCapability
import borg.trikeshed.net.ProtocolId
import borg.trikeshed.net.spi.TransportBackendKind
import borg.trikeshed.net.spi.TransportBackendService
import borg.trikeshed.net.spi.TransportCapabilities
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

enum class ChannelSemantics {
    BYTE_STREAM,
    MESSAGE_STREAM,
}

enum class ChannelizationPath {
    DIRECT_SERVICE,
    TRANSPORT_BACKEND,
}

data class ChannelizationRequest(
    val protocol: ProtocolId,
    val preferredIo: IoCapability? = null,
)

data class ChannelizationPlan(
    val protocol: ProtocolId,
    val semantics: ChannelSemantics,
    val path: ChannelizationPath,
    val provider: String,
    val estimatedCost: Int,
    val backendKind: TransportBackendKind? = null,
)

/**
 * Thin outer coating for installed transport assemblies.
 *
 * Higher layers ask for the lightest viable channelized shape and avoid binding
 * themselves to NIO, io_uring, or any other backend-specific mechanism.
 */
interface ChannelizationProvider {
    val name: String

    fun supports(request: ChannelizationRequest): Boolean

    fun estimateCost(
        request: ChannelizationRequest,
        capabilities: TransportCapabilities? = null,
    ): Int = 100

    suspend fun plan(
        request: ChannelizationRequest,
        capabilities: TransportCapabilities? = null,
    ): ChannelizationPlan
}

data class ChannelizationService(
    val providers: List<ChannelizationProvider>,
) : KeyedService {
    companion object Key : CoroutineContext.Key<ChannelizationService>
    override val key: CoroutineContext.Key<*> get() = Key
}

fun ProtocolId.defaultChannelSemantics(): ChannelSemantics = when (this) {
    ProtocolId.QUIC -> ChannelSemantics.MESSAGE_STREAM
    else -> ChannelSemantics.BYTE_STREAM
}

suspend fun selectChannelization(
    request: ChannelizationRequest,
): ChannelizationPlan {
    val context = currentCoroutineContext()
    val normalizedRequest = request.copy(
        preferredIo = request.preferredIo ?: context.ioCapability,
    )
    val transportCapabilities = coroutineService(TransportBackendService.Key)?.backend?.capabilities()
    val plans = buildList {
        addAll(explicitProviderPlans(normalizedRequest, transportCapabilities))
        implicitServicePlan(normalizedRequest, transportCapabilities)?.let(::add)
        transportCapabilities?.let { add(defaultTransportPlan(normalizedRequest, it)) }
    }
    return plans.minByOrNull(ChannelizationPlan::estimatedCost)
        ?: throw IllegalStateException("No channelization path for ${normalizedRequest.protocol}")
}

private suspend fun explicitProviderPlans(
    request: ChannelizationRequest,
    transportCapabilities: TransportCapabilities?,
): List<ChannelizationPlan> {
    val service = coroutineService(ChannelizationService.Key) ?: return emptyList()
    return buildList {
        for (provider in service.providers) {
            if (!provider.supports(request)) {
                continue
            }
            val plan = provider.plan(request, transportCapabilities)
            add(
                plan.copy(
                    provider = provider.name,
                    estimatedCost = provider.estimateCost(request, transportCapabilities),
                ),
            )
        }
    }
}

private suspend fun implicitServicePlan(
    request: ChannelizationRequest,
    transportCapabilities: TransportCapabilities?,
): ChannelizationPlan? = when (request.protocol) {
    ProtocolId.QUIC -> coroutineService(QuicChannelService.Key)?.let {
        ChannelizationPlan(
            protocol = request.protocol,
            semantics = ChannelSemantics.MESSAGE_STREAM,
            path = ChannelizationPath.DIRECT_SERVICE,
            provider = "quic-service",
            estimatedCost = 0,
            backendKind = transportCapabilities?.backendKind,
        )
    }

    else -> null
}

private fun defaultTransportPlan(
    request: ChannelizationRequest,
    transportCapabilities: TransportCapabilities,
): ChannelizationPlan = ChannelizationPlan(
    protocol = request.protocol,
    semantics = request.protocol.defaultChannelSemantics(),
    path = ChannelizationPath.TRANSPORT_BACKEND,
    provider = "transport-backend",
    estimatedCost = transportCost(request.preferredIo, transportCapabilities),
    backendKind = transportCapabilities.backendKind,
)

private fun transportCost(
    preferredIo: IoCapability?,
    transportCapabilities: TransportCapabilities,
): Int {
    val nativeBias = when (transportCapabilities.backendKind) {
        TransportBackendKind.LINUX_NATIVE -> 10
        TransportBackendKind.SELECTOR -> 20
    }
    val preferenceBias = when (preferredIo) {
        null -> 5
        IoCapability.URING -> if (transportCapabilities.backendKind == TransportBackendKind.LINUX_NATIVE) 0 else 25
        IoCapability.NIO -> if (transportCapabilities.backendKind == TransportBackendKind.SELECTOR) 0 else 15
        IoCapability.EPOLL,
        IoCapability.POSIX_FD,
        IoCapability.KQUEUE -> 5
    }
    return nativeBias + preferenceBias
}

// ============================================================================
// Graph/Job Integration
// ============================================================================

/**
 * Select a channelization path and activate a graph/job structure.
 *
 * This extends [selectChannelization] to not only choose a backend path
 * but also activate the minimal graph/job activation path.
 */
suspend fun selectAndActivateGraph(
    request: ChannelizationRequest,
    graphService: ChannelGraphService? = null,
): Pair<ChannelizationPlan, ChannelGraph> {
    val plan = selectChannelization(request)
    val service = graphService ?: coroutineService(SimpleChannelGraphService.Key)
    val graph = createGraphForPlan(plan, request, service)
    return plan to graph
}

/**
 * Create a channel graph for a channelization plan.
 */
fun createGraphForPlan(
    plan: ChannelizationPlan,
    request: ChannelizationRequest,
    graphService: ChannelGraphService? = null,
): ChannelGraph {
    val graphId = ChannelGraphId("graph-${plan.protocol.name}")
    val workerKey = WorkerKey("worker-${plan.provider}")

    val initialFacts = buildList {
        add(protocolRequirement(plan.protocol, plan.semantics))
        request.preferredIo?.let { add(capabilityFact(it)) }
        add(GraphFact.CustomFact("path", plan.path.name))
        add(GraphFact.CustomFact("provider", plan.provider))
        plan.backendKind?.let { add(GraphFact.CustomFact("backend", it.name)) }
        add(GraphFact.SessionFact(ChannelSessionId("session-${plan.protocol.name}"), plan.protocol, active = true))
    }

    val rules = buildActivationRules(plan)

    return if (graphService != null) {
        graphService.getOrCreateGraph(
            ChannelGraphConfig(
                id = graphId,
                owner = workerKey,
                initialFacts = initialFacts,
                metadata = mapOf(
                    "protocol" to plan.protocol.name,
                    "semantics" to plan.semantics.name,
                    "path" to plan.path.name
                )
            )
        )
    } else {
        channelGraph(graphId)
            .owner(workerKey)
            .facts(*initialFacts.toTypedArray())
            .apply { rules.forEach { rule(it) } }
            .meta("protocol", plan.protocol.name)
            .meta("semantics", plan.semantics.name)
            .meta("path", plan.path.name)
            .build()
    }
}

/**
 * Build activation rules for a channelization plan.
 */
fun buildActivationRules(plan: ChannelizationPlan): List<ActivationRule> =
    buildList {
        // Rule: Protocol requirement triggers handshake job
        add(
            PatternActivationRule(
                factPattern = { it is GraphFact.ProtocolRequirement && it.required },
                jobType = JobType.HANDSHAKE,
                jobConfig = ChannelJobConfig(
                    type = JobType.HANDSHAKE,
                    priority = 10,
                    metadata = mapOf("protocol" to plan.protocol.name)
                ),
                priority = 10
            )
        )

        // Rule: Active session triggers data transfer job
        add(
            PatternActivationRule(
                factPattern = { it is GraphFact.SessionFact && it.active },
                jobType = JobType.DATA_TRANSFER,
                jobConfig = ChannelJobConfig(
                    type = JobType.DATA_TRANSFER,
                    priority = 5,
                    metadata = mapOf("protocol" to plan.protocol.name)
                ),
                priority = 5
            )
        )

        // Rule: Backend kind triggers flow control job for certain backends
        if (plan.backendKind != null) {
            add(
                PatternActivationRule(
                    factPattern = { it is GraphFact.CustomFact && it.key == "backend" },
                    jobType = JobType.FLOW_CONTROL,
                    jobConfig = ChannelJobConfig(
                        type = JobType.FLOW_CONTROL,
                        priority = 3,
                        metadata = mapOf("backend" to plan.backendKind.name)
                    ),
                    priority = 3
                )
            )
        }
    }

/**
 * Activate jobs for a graph based on its facts.
 */
suspend fun activateGraphJobs(graph: ChannelGraph): List<ChannelJob> {
    val newJobs = graph.activateJobss()
    newJobs.filter { it.state == ChannelJobState.Pending }.forEach { it.start() }
    return newJobs
}

/**
 * Get the active job for a graph by type.
 */
fun ChannelGraph.getActiveJob(type: JobType): ChannelJob? =
    jobs.find { it.type == type && it.isActive() }

/**
 * Get all active jobs for a graph.
 */
fun ChannelGraph.getActiveJobs(): List<ChannelJob> =
    jobs.filter { it.isActive() }
