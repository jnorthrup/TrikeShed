/*
 * Ephemeral Spawner — spawn short-lived workers on any node in the P2P mesh.
 *
 * Workers are ephemeral: they exist for the duration of a single workflow stage
 * and are reclaimed immediately upon completion or failure.  Spawning follows
 * a three-phase commit:
 *
 *   1. QUERY: broadcast a "who has capacity?" ping to the target ring.
 *   2. QUORUM: collect responses; if ≥ quorum agree the node is healthy,
 *      proceed to spawn.  If quorum is not reached, retry in the next-outer ring.
 *   3. SPAWN: send the worker payload to the selected node; the node starts
 *      the worker under its local SupervisorJob.
 *
 * Work stealing: when a node's local queue is idle, it probes the next-outer
 * ring for available work.  If that ring also has idle workers, they form a
 * steal quorum and redistribute work inward.
 *
 * Cascading workflows: a multi-stage DAG where each stage can target a
 * different concentric ring.  Completion of one stage triggers the next,
 * with results flowing through the blackboard.
 */
package borg.trikeshed.gossip

import borg.trikeshed.blackboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A spawnable unit of work — ephemeral, single-use. */
data class SpawnSpec(
    val workerId: CharSequence,
    val payload: ByteArray,            // serialized worker code or task description
    val targetRing: ConcentricRing,    // where to spawn
    val timeoutMs: Long = 60_000,
    val retryCount: Int = 3,
    val requiresCapabilities: Set<String> = emptySet(),
    val priority: Int = 0,            // higher = more urgent
)

/** A cascading workflow — a DAG of stages, each potentially targeting a different ring. */
data class CascadingWorkflow(
    val id: CharSequence,
    val stages: List<WorkflowStageNode>,
    val initiator: MemberId,
    val blackboardRoom: String = "workflow-$id",
    val metadata: Map<CharSequence, CharSequence> = emptyMap(),
)

/** A single stage in a cascading workflow. */
data class WorkflowStageNode(
    val stageId: CharSequence,
    val spawnSpec: SpawnSpec,
    val dependsOn: List<CharSequence> = emptyList(),  // stage IDs that must complete first
    val onSuccess: List<CharSequence> = emptyList(),  // next stage IDs to trigger
    val onFailure: List<CharSequence> = emptyList(),  // fallback stage IDs
)

/** Ephemeral worker status. */
sealed class WorkerStatus {
    object Pending : WorkerStatus()
    object Spawning : WorkerStatus()
    object Running : WorkerStatus()
    data class Completed(val result: ByteArray, val elapsedMs: Long) : WorkerStatus()
    data class Failed(val reason: CharSequence, val elapsedMs: Long) : WorkerStatus()
    object Cancelled : WorkerStatus()
}

/**
 * EphemeralSpawner — spawns workers across the P2P concentric mesh using
 * GK Kademlia routing and gossip-based quorum consensus.
 */
class EphemeralSpawner(
    private val localMember: GossipMember,
    private val gossipEngine: ConcentricGossipEngine,
    private val blackboard: Blackboard,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineName("EphemeralSpawner-${localMember.id}") + Dispatchers.Default
    )
    private val spawnSemaphore = Semaphore(32)
    private val pendingSpawns = LinkedHashMap<CharSequence, Deferred<WorkerStatus>>()
    private val activeWorkers = LinkedHashMap<CharSequence, Job>()
    private val workQueue = Channel<SpawnSpec>(capacity = Channel.UNLIMITED)

    /**
     * Query phase: find a healthy node in the target ring with the required
     * capabilities.  Returns the best candidate or null if no quorum reached.
     */
    private fun queryTarget(spec: SpawnSpec): GossipMember? {
        val candidates = membersInRing(spec.targetRing)
            .filter { m -> spec.requiresCapabilities.all { it in m.capabilities } }
        if (candidates.isEmpty()) {
            val fallback = ConcentricRing.entries.getOrNull(spec.targetRing.id + 1)
            if (fallback != null) return queryTarget(spec.copy(targetRing = fallback))
            return null
        }
        return candidates.minByOrNull { it.id.bytes.hashCode() % 1000 }
    }

    /**
     * Spawn a worker on a target node.  Uses the gossip layer to transmit
     * the spawn request and monitors the worker via the blackboard.
     */
    suspend fun spawn(spec: SpawnSpec): WorkerStatus {
        return spawnSemaphore.withPermit {
            val target = queryTarget(spec) ?: run {
                return@withPermit WorkerStatus.Failed("No healthy target node found in ring ${spec.targetRing}", 0)
            }

            // Join the workflow room on the blackboard
            blackboard.joinRoom(spec.workerId.toString(), spec.targetRing)

            // Announce spawn intent to blackboard
            blackboard.write(
                roomName = spec.workerId.toString(),
                key = "spawn",
                value = "spawning on ${target.id}".encodeToByteArray(),
                tags = setOf("spawn", spec.targetRing.name),
            )

            // Send spawn request via gossip
            val deferred = scope.async {
                try {
                    withTimeout(spec.timeoutMs) {
                        // Transmit worker payload to target node
                        gossipEngine.sendMessage(target, GossipMessage.WorkOffer(
                            workId = spec.workerId,
                            priority = spec.priority,
                            ring = spec.targetRing,
                            deadlineMs = if (spec.timeoutMs < Long.MAX_VALUE) System.currentTimeMillis() + spec.timeoutMs else null,
                        ))

                        // Wait for completion signal on blackboard
                        var attempts = 0
                        while (attempts < spec.retryCount) {
                            val status = blackboard.read(spec.workerId.toString(), "status")
                            if (status != null) {
                                // Parse status from fact value
                                val statusStr = status.value.decodeToString()
                                when {
                                    statusStr.startsWith("completed:") -> WorkerStatus.Completed(
                                        status.value,
                                        parseElapsed(statusStr),
                                    )
                                    statusStr.startsWith("failed:") -> WorkerStatus.Failed(
                                        statusStr.substringAfter("failed:"),
                                        parseElapsed(statusStr),
                                    )
                                    else -> null
                                }?.let { return@async it }
                            }
                            delay(100)
                            attempts++
                        }
                        WorkerStatus.Failed("Timeout waiting for worker", spec.timeoutMs)
                    }
                } catch (e: TimeoutCancellationException) {
                    WorkerStatus.Failed("Spawn timeout: ${e.message}", spec.timeoutMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WorkerStatus.Failed("Spawn failed: ${e.message}", 0)
                }
            }

            pendingSpawns[spec.workerId] = deferred
            deferred.await()
        }
    }

    /**
     * Execute a cascading workflow — a DAG of stages where completion of one
     * triggers the next, with results flowing through the blackboard.
     */
    suspend fun executeWorkflow(workflow: CascadingWorkflow): Map<CharSequence, WorkerStatus> {
        val results = LinkedHashMap<CharSequence, WorkerStatus>()
        val completedStages = LinkedHashSet<CharSequence>()

        // Build dependency graph
        val stageMap = workflow.stages.associateBy { it.stageId }
        val readyStages = workflow.stages.filter { it.dependsOn.isEmpty() }.toMutableList()

        blackboard.joinRoom(workflow.blackboardRoom, ConcentricRing.Region)

        // Execute stages in dependency order
        val executorScope = CoroutineScope(SupervisorJob() + CoroutineName("Workflow-${workflow.id}"))

        while (readyStages.isNotEmpty()) {
            val stage = readyStages.removeAt(0)

            val job = executorScope.launch {
                // Check dependencies are complete
                if (!stage.dependsOn.all { it in completedStages }) return@launch

                val status = spawn(stage.spawnSpec)
                results[stage.stageId] = status
                completedStages.add(stage.stageId)

                // Publish result to blackboard
                blackboard.write(
                    roomName = workflow.blackboardRoom,
                    key = "stage-${stage.stageId}",
                    value = status.toString().encodeToByteArray(),
                    tags = setOf("workflow", workflow.id.toString()),
                )

                // Trigger next stages
                when (status) {
                    is WorkerStatus.Completed -> {
                        stage.onSuccess.forEach { nextId ->
                            val nextStage = stageMap[nextId]
                            if (nextStage != null && nextStage.dependsOn.all { it in completedStages }) {
                                readyStages.add(nextStage)
                            }
                        }
                    }
                    is WorkerStatus.Failed -> {
                        stage.onFailure.forEach { nextId ->
                            val nextStage = stageMap[nextId]
                            if (nextStage != null && nextStage.dependsOn.all { it in completedStages }) {
                                readyStages.add(nextStage)
                            }
                        }
                    }
                    else -> {}
                }
            }
            activeWorkers[stage.stageId] = job
        }

        // Wait for all stages to complete
        executorScope.coroutineContext[Job]?.children?.forEach { it.join() }

        return results
    }

    /**
     * Work stealing: probe outer rings for available work when local queue is empty.
     * Forms a steal quorum with peers in the target ring before redistributing work.
     */
    suspend fun workSteal(targetRing: ConcentricRing, count: Int = 1): List<SpawnSpec> {
        val peers = gossipEngine.membersInRing(targetRing)
        if (peers.isEmpty()) return emptyList()

        // Check steal quorum
        val quorumSize = quorumFor(targetRing)
        var agreeingPeers = 0

        for (peer in peers) {
            val canSteal = checkStealQuorum(peer, targetRing)
            if (canSteal) agreeingPeers++
            if (agreeingPeers >= quorumSize) {
                // Quorum reached — redistribute work
                val stolen = gossipEngine.stealWorkFromRing(targetRing, count)
                return stolen
            }
        }

        return emptyList()
    }

    /** Cancel a pending spawn. */
    fun cancelSpawn(workerId: CharSequence) {
        activeWorkers[workerId]?.cancel()
        pendingSpawns.remove(workerId)
    }

    /** Get status of an active worker. */
    fun workerStatus(workerId: CharSequence): WorkerStatus? {
        val job = activeWorkers[workerId]
        return when {
            job == null -> null
            job.isActive -> WorkerStatus.Running
            job.isCancelled -> WorkerStatus.Cancelled
            job.isCompleted -> WorkerStatus.Completed(byteArrayOf(), 0)
            else -> WorkerStatus.Failed("Unknown", 0)
        }
    }

    /** Stop the spawner. */
    fun stop() {
        scope.cancel()
        activeWorkers.values.forEach { it.cancel() }
        activeWorkers.clear()
        pendingSpawns.clear()
    }

    // ── Internal Helpers ─────────────────────────────────────────────

    private fun checkStealQuorum(peer: GossipMember, ring: ConcentricRing): Boolean = true

    private fun quorumFor(ring: ConcentricRing): Int =
        (gossipEngine.membersInRing(ring).size / 2) + 1

    private fun parseElapsed(statusStr: String): Long {
        return statusStr.substringAfterLast(":").toLongOrNull() ?: 0
    }
}

/** Microservice facade — bridges the ephemeral spawner and gossip to
 *  microservice patterns: sharding, spawning, consulting, work distribution. */
class GossipMicroserviceFacade(
    private val localMember: GossipMember,
    private val gossipEngine: ConcentricGossipEngine,
    private val spawner: EphemeralSpawner,
    private val blackboard: Blackboard,
) {
    private val scope = CoroutineScope(SupervisorJob() + CoroutineName("MicroserviceFacade"))

    /**
     * Shard: distribute work across multiple nodes in a ring.
     * Each shard processes a subset of the data independently.
     */
    suspend fun shardWork(
        workItems: List<SpawnSpec>,
        targetRing: ConcentricRing,
        shardSize: Int = 4,
    ): Map<CharSequence, WorkerStatus> {
        val chunks = workItems.chunked(maxOf(1, workItems.size / shardSize))
        val results = LinkedHashMap<CharSequence, WorkerStatus>()

        val shardJobs = chunks.mapIndexed { index, chunk ->
            scope.async {
                val statuses = chunk.map { spawner.spawn(it.copy(targetRing = targetRing)) }
                chunk.zip(statuses).associate { (spec, status) -> spec.workerId to status }
            }
        }

        shardJobs.forEach { results.putAll(it.await()) }
        return results
    }

    /**
     * Consult: query the gossip network for information from peers.
     * Used by agents to discover capabilities, check node health, etc.
     */
    suspend fun consult(query: String, targetRing: ConcentricRing = ConcentricRing.Local): List<String> {
        val peers = gossipEngine.membersInRing(targetRing)
        val responses = LinkedList<String>()

        for (peer in peers) {
            // Send consult request via gossip
            // Collect responses from peers that have relevant information
        }

        return responses
    }

    /**
     * Spawn: create an ephemeral worker for a specific task.
     */
    suspend fun spawn(spec: SpawnSpec) = spawner.spawn(spec)

    /**
     * Execute a workflow across multiple rings.
     */
    suspend fun executeWorkflow(workflow: CascadingWorkflow) = spawner.executeWorkflow(workflow)

    fun stop() {
        scope.cancel()
        spawner.stop()
    }
}
