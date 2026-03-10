package borg.trikeshed.net.channelization

import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.context.IoCapability
import borg.trikeshed.net.ProtocolId
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmInline

/**
 * Opaque identifier for a channel graph.
 */
@JvmInline
value class ChannelGraphId(val raw: String)

/**
 * Opaque identifier for a worker/owner.
 */
@JvmInline
value class WorkerKey(val raw: String)

/**
 * Graph state representing the lifecycle of a channel graph.
 */
sealed interface ChannelGraphState {
    /** Graph being constructed. */
    object Initializing : ChannelGraphState

    /** Graph active and accepting jobs. */
    object Active : ChannelGraphState

    /** Graph paused, no new jobs activated. */
    object Suspended : ChannelGraphState

    /** Graph being torn down. */
    object Terminating : ChannelGraphState

    /** Graph terminated. */
    object Terminated : ChannelGraphState

    /** Graph failed with error. */
    data class Failed(val reason: Throwable) : ChannelGraphState
}

/**
 * A fact in the channel graph.
 *
 * Facts are immutable statements about the graph state, protocol requirements,
 * or runtime conditions. Jobs are activated based on fact patterns.
 */
sealed class GraphFact {
    /** Protocol requirement fact. */
    data class ProtocolRequirement(
        val protocol: ProtocolId,
        val semantics: ChannelSemantics,
        val required: Boolean = true
    ) : GraphFact()

    /** Capability fact indicating available I/O capability. */
    data class CapabilityFact(
        val capability: IoCapability,
        val available: Boolean = true
    ) : GraphFact()

    /** Session fact linking a session to the graph. */
    data class SessionFact(
        val sessionId: ChannelSessionId,
        val protocol: ProtocolId,
        val active: Boolean = true
    ) : GraphFact()

    /** Job fact indicating a job exists in the graph. */
    data class JobFact(
        val jobId: ChannelJobId,
        val jobType: String,
        val active: Boolean = true
    ) : GraphFact()

    /** Dependency fact linking two facts. */
    data class DependencyFact(
        val from: GraphFact,
        val to: GraphFact,
        val type: DependencyType
    ) : GraphFact()

    /** Custom fact for extensibility. */
    data class CustomFact(
        val key: String,
        val value: Any?
    ) : GraphFact()
}

/**
 * Type of dependency between graph facts.
 */
enum class DependencyType {
    /** Fact A requires Fact B. */
    REQUIRES,

    /** Fact A precedes Fact B in execution order. */
    PRECEDES,

    /** Fact A is optional given Fact B. */
    OPTIONAL_GIVEN,

    /** Fact A conflicts with Fact B. */
    CONFLICTS
}

/**
 * Channel graph configuration.
 */
data class ChannelGraphConfig(
    val id: ChannelGraphId,
    val owner: WorkerKey? = null,
    val initialFacts: List<GraphFact> = emptyList(),
    val maxJobs: Int = 1024,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Channel graph: a relation or dependency shape between protocol/runtime facts.
 *
 * The graph serves as an attraction point for channelized communication,
 * organizing facts about protocols, sessions, capabilities, and jobs.
 * Jobs are activated based on fact patterns in the graph.
 */
interface ChannelGraph {
    val id: ChannelGraphId
    val owner: WorkerKey?
    val state: ChannelGraphState
    val facts: List<GraphFact>
    val jobs: List<ChannelJob>
    val metadata: Map<String, String>

    /** Add a fact to the graph. */
    fun addFact(fact: GraphFact)

    /** Remove a fact from the graph. */
    fun removeFact(fact: GraphFact)

    /** Query facts matching a predicate. */
    fun queryFacts(predicate: (GraphFact) -> Boolean): List<GraphFact>

    /** Activate jobs based on current facts. */
    fun activateJobss(): List<ChannelJob>

    /** Transition graph to a new state. */
    fun transitionTo(newState: ChannelGraphState)

    /** Check if graph can accept new jobs. */
    fun canAcceptJobs(): Boolean = state == ChannelGraphState.Active
}

/**
 * Job state representing the lifecycle of a channel job.
 */
sealed interface ChannelJobState {
    /** Job created, not yet started. */
    object Pending : ChannelJobState

    /** Job currently executing. */
    object Running : ChannelJobState

    /** Job waiting for a condition. */
    object Waiting : ChannelJobState

    /** Job completed successfully. */
    object Completed : ChannelJobState

    /** Job cancelled. */
    object Cancelled : ChannelJobState

    /** Job failed with error. */
    data class Failed(val reason: Throwable) : ChannelJobState
}

/**
 * Job type enumeration.
 */
enum class JobType {
    /** Handshake job for protocol negotiation. */
    HANDSHAKE,

    /** Data transfer job. */
    DATA_TRANSFER,

    /** Flow control job. */
    FLOW_CONTROL,

    /** Keep-alive job. */
    KEEP_ALIVE,

    /** Teardown job. */
    TEARDOWN,

    /** Custom job type. */
    CUSTOM
}

/**
 * Opaque identifier for a channel job.
 */
@JvmInline
value class ChannelJobId(val raw: String)

/**
 * Job activation context.
 */
data class JobActivationContext(
    val graphId: ChannelGraphId,
    val sessionId: ChannelSessionId?,
    val triggeringFact: GraphFact,
    val timestamp: Long
)

/**
 * Job execution result.
 */
sealed class JobResult {
    /** Job completed successfully with optional result. */
    data class Success(val value: Any? = null) : JobResult()

    /** Job failed with error. */
    data class Failure(val error: Throwable) : JobResult()

    /** Job pending asynchronous completion. */
    object Pending : JobResult()
}

/**
 * Channel job configuration.
 */
data class ChannelJobConfig(
    val type: JobType,
    val priority: Int = 0,
    val timeout: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * ChannelJob: a scheduled keyed unit of work under coroutine/worker ownership.
 *
 * Jobs are the executable units activated by the channel graph based on facts.
 * Each job has an owner (worker key) and executes within a coroutine context.
 */
interface ChannelJob {
    val id: ChannelJobId
    val graphId: ChannelGraphId
    val owner: WorkerKey
    val type: JobType
    val state: ChannelJobState
    val priority: Int
    val sessionId: ChannelSessionId?

    /** Start job execution. */
    suspend fun start(): JobResult

    /** Pause job execution. */
    suspend fun pause()

    /** Resume job execution. */
    suspend fun resume()

    /** Cancel job execution. */
    suspend fun cancel()

    /** Transition job to a new state. */
    fun transitionTo(newState: ChannelJobState)

    /** Check if job is active. */
    fun isActive(): Boolean = state == ChannelJobState.Running || state == ChannelJobState.Waiting
}

/**
 * Graph-to-job activation rule.
 *
 * Rules define when and how jobs should be activated based on graph facts.
 */
interface ActivationRule {
    /** Check if the rule matches the current graph state. */
    fun matches(graph: ChannelGraph): Boolean

    /** Create a job when the rule matches. */
    fun activate(graph: ChannelGraph, context: JobActivationContext): ChannelJob
}

/**
 * Simple pattern-based activation rule.
 */
data class PatternActivationRule(
    val factPattern: (GraphFact) -> Boolean,
    val jobType: JobType,
    val jobConfig: ChannelJobConfig = ChannelJobConfig(JobType.CUSTOM),
    val priority: Int = 0
) : ActivationRule {
    override fun matches(graph: ChannelGraph): Boolean =
        graph.facts.any(factPattern)

    override fun activate(graph: ChannelGraph, context: JobActivationContext): ChannelJob =
        SimpleChannelJob(
            id = ChannelJobId("job-${graph.id.raw}-${jobType.name.lowercase()}"),
            graphId = graph.id,
            owner = graph.owner ?: WorkerKey("default"),
            type = jobType,
            state = ChannelJobState.Pending,
            priority = priority,
            sessionId = context.sessionId,
            config = jobConfig
        )
}

/**
 * Simple implementation of ChannelJob.
 */
data class SimpleChannelJob(
    override val id: ChannelJobId,
    override val graphId: ChannelGraphId,
    override val owner: WorkerKey,
    override val type: JobType,
    override var state: ChannelJobState,
    override val priority: Int,
    override val sessionId: ChannelSessionId?,
    val config: ChannelJobConfig = ChannelJobConfig(type)
) : ChannelJob {
    override suspend fun start(): JobResult {
        state = ChannelJobState.Running
        return JobResult.Success()
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

/**
 * Simple implementation of ChannelGraph.
 */
class SimpleChannelGraph(
    override val id: ChannelGraphId,
    override val owner: WorkerKey?,
    initialFacts: List<GraphFact> = emptyList(),
    val activationRules: List<ActivationRule> = emptyList(),
    override val metadata: Map<String, String> = emptyMap()
) : ChannelGraph {
    private val _facts = initialFacts.toMutableList()
    private val _jobs = mutableListOf<ChannelJob>()
    override var state: ChannelGraphState = ChannelGraphState.Initializing
        private set

    override val facts: List<GraphFact> get() = _facts.toList()
    override val jobs: List<ChannelJob> get() = _jobs.toList()

    override fun addFact(fact: GraphFact) {
        _facts.add(fact)
    }

    override fun removeFact(fact: GraphFact) {
        _facts.remove(fact)
    }

    override fun queryFacts(predicate: (GraphFact) -> Boolean): List<GraphFact> =
        _facts.filter(predicate)

    override fun activateJobss(): List<ChannelJob> {
        val context = JobActivationContext(
            graphId = id,
            sessionId = null,
            triggeringFact = GraphFact.CustomFact("activation", 0L),
            timestamp = 0L
        )

        val newJobs = activationRules
            .filter { it.matches(this) }
            .map { it.activate(this, context) }
            .filter { job -> _jobs.none { it.id == job.id } }

        _jobs.addAll(newJobs)
        return newJobs
    }

    override fun transitionTo(newState: ChannelGraphState) {
        state = newState
    }
}

/**
 * Graph builder for constructing channel graphs.
 */
class ChannelGraphBuilder(
    private val id: ChannelGraphId,
    private var owner: WorkerKey? = null,
    private val facts: MutableList<GraphFact> = mutableListOf(),
    private val rules: MutableList<ActivationRule> = mutableListOf(),
    private val metadata: MutableMap<String, String> = mutableMapOf()
) {
    fun owner(key: WorkerKey): ChannelGraphBuilder = apply { owner = key }

    fun fact(fact: GraphFact): ChannelGraphBuilder = apply { facts.add(fact) }

    fun facts(vararg facts: GraphFact): ChannelGraphBuilder = apply { this.facts.addAll(facts) }

    fun rule(rule: ActivationRule): ChannelGraphBuilder = apply { rules.add(rule) }

    fun meta(key: String, value: String): ChannelGraphBuilder = apply { metadata[key] = value }

    fun build(): ChannelGraph =
        SimpleChannelGraph(id, owner, facts.toList(), rules.toList(), metadata.toMap())
}

/**
 * Create a channel graph builder.
 */
fun channelGraph(id: ChannelGraphId): ChannelGraphBuilder = ChannelGraphBuilder(id)

/**
 * Graph job service for managing graphs and jobs.
 */
interface ChannelGraphService : KeyedService {
    /** Get or create a graph by ID. */
    fun getOrCreateGraph(config: ChannelGraphConfig): ChannelGraph

    /** Get a graph by ID. */
    fun getGraph(id: ChannelGraphId): ChannelGraph?

    /** Remove a graph. */
    fun removeGraph(id: ChannelGraphId)

    /** List all active graphs. */
    fun listGraphs(): List<ChannelGraph>

    /** Assign a worker to a graph. */
    fun assignWorker(graphId: ChannelGraphId, worker: WorkerKey)

    /** Get jobs for a graph. */
    fun getJobs(graphId: ChannelGraphId): List<ChannelJob>

    /** Get job by ID. */
    fun getJob(jobId: ChannelJobId): ChannelJob?
}

/**
 * Simple in-memory graph job service.
 */
class SimpleChannelGraphService : ChannelGraphService {
    private val graphs = mutableMapOf<ChannelGraphId, ChannelGraph>()
    private val jobs = mutableMapOf<ChannelJobId, ChannelJob>()

    companion object Key : CoroutineContext.Key<SimpleChannelGraphService>
    override val key: CoroutineContext.Key<*> get() = Key

    override fun getOrCreateGraph(config: ChannelGraphConfig): ChannelGraph =
        graphs.getOrPut(config.id) {
            SimpleChannelGraph(
                config.id,
                config.owner,
                config.initialFacts,
                metadata = config.metadata
            )
        }

    override fun getGraph(id: ChannelGraphId): ChannelGraph? = graphs[id]

    override fun removeGraph(id: ChannelGraphId) {
        graphs.remove(id)
    }

    override fun listGraphs(): List<ChannelGraph> = graphs.values.toList()

    override fun assignWorker(graphId: ChannelGraphId, worker: WorkerKey) {
        graphs[graphId]?.let { graph ->
            // In a real implementation, this would update the graph's owner
        }
    }

    override fun getJobs(graphId: ChannelGraphId): List<ChannelJob> =
        graphs[graphId]?.jobs ?: emptyList()

    override fun getJob(jobId: ChannelJobId): ChannelJob? = jobs[jobId]
}

/**
 * Helper to create a protocol requirement fact.
 */
fun protocolRequirement(
    protocol: ProtocolId,
    semantics: ChannelSemantics,
    required: Boolean = true
): GraphFact.ProtocolRequirement =
    GraphFact.ProtocolRequirement(protocol, semantics, required)

/**
 * Helper to create a capability fact.
 */
fun capabilityFact(capability: IoCapability, available: Boolean = true): GraphFact.CapabilityFact =
    GraphFact.CapabilityFact(capability, available)

/**
 * Helper to create a session fact.
 */
fun sessionFact(sessionId: ChannelSessionId, protocol: ProtocolId): GraphFact.SessionFact =
    GraphFact.SessionFact(sessionId, protocol)

/**
 * Helper to create a dependency fact.
 */
fun dependencyFact(
    from: GraphFact,
    to: GraphFact,
    type: DependencyType
): GraphFact.DependencyFact =
    GraphFact.DependencyFact(from, to, type)
