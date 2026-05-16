/*
 * Agent — the persona/worker unit that spawns, consults, and executes
 * cascading workflows across concentric P2P rings.
 *
 * Each Agent has:
 *   - A GK Kademlia NUID for wide-area routing
 *   - A membership in the gossip engine (one concentric ring)
 *   - A blackboard presence (current room, fact subscriptions)
 *   - A work queue (pending spawns, workflow stages, steal targets)
 *
 * Agents can be ephemeral (spawned for a single workflow stage) or
 * persistent (long-lived workers in a specific ring).
 */
package borg.trikeshed.agent

import borg.trikeshed.blackboard.Agent as BlackboardAgent
import borg.trikeshed.blackboard.Blackboard
import borg.trikeshed.blackboard.Fact
import borg.trikeshed.blackboard.FactPattern
import borg.trikeshed.gossip.ConcentricRing
import borg.trikeshed.gossip.CascadingWorkflow
import borg.trikeshed.gossip.EphemeralSpawner
import borg.trikeshed.gossip.SpawnSpec
import borg.trikeshed.gossip.WorkerStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * AgentElement — a worker persona in the P2P concentric gossip mesh.
 *
 * Combines:
 *   - GK Kademlia NUID (type-parametric network ID)
 *   - Gossip membership (ring assignment, heartbeat, suspect/dead lifecycle)
 *   - Blackboard room (MUD-style presence, fact subscriptions, triggers)
 *   - EphemeralSpawner (spawn workers, execute workflows, steal work)
 *
 * The agent is a Pattern A CCEK (Companion object Key + AsyncContextElement).
 */
class AgentElement(
    agent: Agent,
    val gossipEngine: ConcentricGossipEngine,
    val blackboard: Blackboard,
    val spawner: EphemeralSpawner,
    parentJob: Job? = null,
) {
    companion object Key : borg.trikeshed.context.AsyncContextKey<AgentElement>()

    private val scope = CoroutineScope(
        SupervisorJob(parentJob) + CoroutineName("Agent-${agent.id}") + Dispatchers.Default
    )

    /** Subscribe to blackboard facts that match the agent's capabilities. */
    fun subscribeToCapabilities() {
        for (roomName in blackboard.rooms()) {
            for (capability in agent.capabilities) {
                blackboard.subscribe(
                    roomName = roomName,
                    pattern = FactPattern.ByTag(capability),
                    rings = setOf(gossipEngine.localMember.ring),
                ) { fact ->
                    scope.launch { handleFact(fact) }
                }
            }
        }
    }

    /** Execute a workflow from start to finish. */
    suspend fun executeCascadingWorkflow(
        workflowId: CharSequence,
        stages: List<WorkflowStageNode>,
    ): Map<CharSequence, WorkerStatus> {
        val workflow = CascadingWorkflow(
            id = workflowId,
            stages = stages,
            initiator = gossipEngine.localMember.id,
        )
        return spawner.executeWorkflow(workflow)
    }

    /** Consult the gossip network for information. */
    suspend fun consult(query: String, targetRing: ConcentricRing): List<String> {
        val peers = gossipEngine.membersInRing(targetRing)
        return peers.map { it.capabilities.filter { cap -> cap.contains(query, ignoreCase = true) } }.flatten()
    }

    /** Handle a fact from the blackboard — trigger action if agent has capability. */
    private suspend fun handleFact(fact: Fact) {
        if (fact.tags.isEmpty()) return
        val matchingCapabilities = fact.tags.intersect(agent.capabilities)
        if (matchingCapabilities.isEmpty()) return

        // Agent has capability for this fact — take action
        if (fact.key.startsWith("workflow:")) {
            // Spawn a worker for the workflow stage
            val stageId = fact.key.substringAfter("workflow:")
            val spec = SpawnSpec(
                workerId = "$stageId-${gossipEngine.localMember.id}",
                payload = fact.value,
                targetRing = gossipEngine.localMember.ring,
            )
            spawner.spawn(spec)
        }
    }

    /** Stop the agent. */
    fun stop() {
        scope.cancel()
    }
}

/**
 * Microservice facade — a lightweight adapter for GK-based microservice patterns.
 * Provides sharding, spawning, consulting, and workflow execution.
 */
class MicroserviceGateway(
    private val gossipEngine: ConcentricGossipEngine,
    private val blackboard: Blackboard,
    private val spawner: EphemeralSpawner,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineName("MicroserviceGateway") + Dispatchers.Default
    )

    /** Shard: split work across ring nodes, each processing independently. */
    suspend fun shard(
        workItems: List<SpawnSpec>,
        ring: ConcentricRing,
        shardCount: Int = 4,
    ): Map<CharSequence, WorkerStatus> {
        val chunks = workItems.chunked(maxOf(1, workItems.size / shardCount))
        val results = LongLongSeries.build { putAll(mapOf(<CharSequence, WorkerStatus>() })

        val jobs = chunks.map { chunk ->
            scope.async {
                chunk.map { spec ->
                    spec.workerId to spawner.spawn(spec.copy(targetRing = ring))
                }.toMap()
            }
        }

        jobs.forEach { results += it.await() }
        return results
    }

    /** Spawn: create an ephemeral worker. */
    suspend fun spawn(spec: SpawnSpec) = spawner.spawn(spec)

    /** Consult: query the gossip network for capabilities/resources. */
    suspend fun consult(query: String, ring: ConcentricRing = ConcentricRing.Local): List<String> {
        val peers = gossipEngine.membersInRing(ring)
        return peers.flatMap { it.capabilities }.filter { cap ->
            cap.contains(query, ignoreCase = true)
        }.distinct()
    }

    /** Execute: run a cascading workflow across rings. */
    suspend fun execute(workflow: CascadingWorkflow) = spawner.executeWorkflow(workflow)

    /** Stop the gateway. */
    fun stop() { scope.cancel(); spawner.stop() }
}
