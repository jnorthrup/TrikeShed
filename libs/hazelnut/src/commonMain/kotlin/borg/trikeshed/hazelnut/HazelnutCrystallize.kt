package borg.trikeshed.hazelnut

import borg.trikeshed.lib.*

// ── Split-brain resolution: git-gated CRDT + pijul patch logic ─────────────

/** CRDT merge strategy for distributed object conflicts. */
enum class CrdtStrategy {
    LAST_WRITER_WINS,
    MULTI_VALUE_REGISTER,
    G_COUNTER,
    PN_COUNTER,
    G_SET,
    OR_SET,
    LWW_REGISTER,
    TWO_PHASE_SET,
    MERGEABLE_SEQUENCE,
    DELTA_STATE,
}

/**
 * Vector clock for causal ordering across nodes.
 * Each node increments its own entry on write.
 */
data class VClock(
    val entries: Map<CharSequence, Long> = emptyMap(),
) {
    /** Increment this node's entry. */
    fun tick(node: CharSequence): VClock =
        copy(entries = entries + (node to (entries[node] ?: 0) + 1))

    /** Concurrent if neither dominates the other. */
    fun isConcurrent(other: VClock): Boolean =
        !dominates(other) && !other.dominates(this) && this != other

    /** True if this clock >= other for all nodes. */
    fun dominates(other: VClock): Boolean =
        other.entries.all { (k, v) -> (entries[k] ?: 0) >= v }

    /** Merge two clocks (pointwise max). */
    fun merge(other: VClock): VClock {
        val allKeys = entries.keys + other.entries.keys
        return VClock(allKeys.associateWith { k ->
            maxOf(entries[k] ?: 0, other.entries[k] ?: 0)
        })
    }

    fun isEmpty(): Boolean = entries.isEmpty()
}

/**
 * Pijul-inspired patch record — each change is an independently mergeable
 * atomic patch with a cryptographic hash (simulated here).
 */
data class ChangePatch(
    val patchId: CharSequence,
    val parentHashes: List<CharSequence> = emptyList(),
    val nodeOrigin: CharSequence,
    val clock: VClock,
    val timestamp: Long,
    val changeType: ChangeType,
    val objectId: CharSequence,
    val targetValue: CharSequence,
)

enum class ChangeType {
    CREATE, UPDATE, DELETE, RESTORE,
}

/**
 * Git-gated CRDT store — enforces merge-before-accept semantics.
 * Every distributed object write produces a ChangePatch that is merged
 * against the current DAG topology before resolution.
 */
class GitGatedCrystore(
    val localNodeId: CharSequence,
    private val patches: MutableList<ChangePatch> = mutableListOf(),
    private val heads: MutableSet<CharSequence> = mutableSetOf(),
    var patchCounter: Long = 0,
) {
    /** Record a new change with auto-incremented vector clock. */
    fun record(
        objectId: CharSequence,
        value: CharSequence,
        type: ChangeType = ChangeType.UPDATE,
        parentHash: CharSequence? = null,
    ): ChangePatch {
        val clock = currentClock().tick(localNodeId)
        patchCounter++
        val patchId = "$objectId:$patchCounter"
        val parents = if (parentHash != null) listOf(parentHash) else heads.toList()
        val patch = ChangePatch(
            patchId = patchId,
            parentHashes = parents,
            nodeOrigin = localNodeId,
            clock = clock,
            timestamp = System.currentTimeMillis(),
            changeType = type,
            objectId = objectId,
            targetValue = value,
        )
        patches.add(patch)
        heads.clear()
        heads.add(patchId)
        return patch
    }

    /** Detect split-brain: two concurrent heads that haven't merged. */
    fun isInSplitBrain(): Boolean {
        val headPatches = heads.map { h -> patches.find { it.patchId == h } }.filterNotNull()
        for (i in headPatches.indices) {
            for (j in i + 1 until headPatches.size) {
                if (headPatches[i].clock.isConcurrent(headPatches[j].clock)) return true
            }
        }
        return false
    }

    /** Merge remote patches: resolve concurrent patches via strategy. */
    fun mergeRemote(remote: List<ChangePatch>, strategy: CrdtStrategy): List<CharSequence> {
        val merged = mutableListOf<CharSequence>()
        for (remotePatch in remote) {
            // Incorporate remote clock
            val existing = patches.find { it.patchId == remotePatch.patchId }
            if (existing == null) {
                patches.add(remotePatch)
                merged.add(remotePatch.patchId)
            }

            // Update heads: remove any dominated head, add merged head
            val dominated = heads.filter { h ->
                val hp = patches.find { it.patchId == h }
                hp != null && hp.clock.dominates(remotePatch.clock).not() &&
                    remotePatch.clock.dominates(hp.clock)
            }
            heads -= dominated
            heads += remotePatch.patchId
        }

        // Git-gated merge: auto-merge if no conflict, strategy if concurrent
        if (isInSplitBrain()) {
            val conflicting = resolveConflicts(strategy)
            merged += conflicting
        }
        return merged
    }

    private fun currentClock(): VClock {
        if (patches.isEmpty()) return VClock()
        return patches.last().clock
    }

    /** Apply a CRDT strategy to resolve concurrent heads. */
    fun resolveConflicts(strategy: CrdtStrategy): List<CharSequence> {
        val headPatches = heads.mapNotNull { h -> patches.find { it.patchId == h } }
        if (headPatches.size < 2) return emptyList()

        val resolved = when (strategy) {
            CrdtStrategy.LAST_WRITER_WINS, CrdtStrategy.LWW_REGISTER -> {
                val winner = headPatches.maxByOrNull { it.timestamp } ?: return emptyList()
                heads.clear()
                heads.add(winner.patchId)
                listOf(winner.patchId)
            }
            CrdtStrategy.G_COUNTER, CrdtStrategy.PN_COUNTER -> {
                val mergedClock = headPatches.fold(VClock()) { acc, p -> acc.merge(p.clock) }
                patchCounter++
                val mergeId = "merged:$patchCounter"
                val mergedPatch = ChangePatch(
                    patchId = mergeId,
                    parentHashes = heads.toList(),
                    nodeOrigin = localNodeId,
                    clock = mergedClock,
                    timestamp = System.currentTimeMillis(),
                    changeType = ChangeType.UPDATE,
                    objectId = headPatches[0].objectId,
                    targetValue = headPatches.maxByOrNull { it.targetValue.length }?.targetValue ?: "",
                )
                patches.add(mergedPatch)
                heads.clear()
                heads.add(mergeId)
                listOf(mergeId)
            }
            CrdtStrategy.G_SET, CrdtStrategy.OR_SET -> {
                val mergedClock = headPatches.fold(VClock()) { acc, p -> acc.merge(p.clock) }
                patchCounter++
                val mergeId = "merged:$patchCounter"
                val mergedPatch = ChangePatch(
                    patchId = mergeId,
                    parentHashes = heads.toList(),
                    nodeOrigin = localNodeId,
                    clock = mergedClock,
                    timestamp = System.currentTimeMillis(),
                    changeType = ChangeType.UPDATE,
                    objectId = headPatches[0].objectId,
                    targetValue = headPatches.joinToString(",") { it.targetValue },
                )
                patches.add(mergedPatch)
                heads.clear()
                heads.add(mergeId)
                listOf(mergeId)
            }
            CrdtStrategy.MULTI_VALUE_REGISTER -> {
                // Keep all concurrent values (MV-Register)
                headPatches.map { it.patchId }
            }
            CrdtStrategy.TWO_PHASE_SET -> {
                val adds = headPatches.filter { it.changeType != ChangeType.DELETE }
                val deletes = headPatches.filter { it.changeType == ChangeType.DELETE }
                if (deletes.isNotEmpty()) {
                    heads.clear()
                    heads.add(deletes.last().patchId)
                    listOf(deletes.last().patchId)
                } else {
                    val winner = adds.maxByOrNull { it.timestamp } ?: return emptyList()
                    heads.clear()
                    heads.add(winner.patchId)
                    listOf(winner.patchId)
                }
            }
            CrdtStrategy.MERGEABLE_SEQUENCE -> {
                // Topological sort: linearize concurrent patches
                val sorted = headPatches.sortedBy { it.timestamp }
                val mergedClock = sorted.fold(VClock()) { acc, p -> acc.merge(p.clock) }
                patchCounter++
                val mergeId = "merged:$patchCounter"
                val mergedPatch = ChangePatch(
                    patchId = mergeId,
                    parentHashes = sorted.map { it.patchId },
                    nodeOrigin = localNodeId,
                    clock = mergedClock,
                    timestamp = sorted.last().timestamp,
                    changeType = ChangeType.UPDATE,
                    objectId = headPatches[0].objectId,
                    targetValue = sorted.last().targetValue,
                )
                patches.add(mergedPatch)
                heads.clear()
                heads.add(mergeId)
                listOf(mergeId)
            }
            CrdtStrategy.DELTA_STATE -> {
                // Delta-state: accumulate all changes
                val mergedClock = headPatches.fold(VClock()) { acc, p -> acc.merge(p.clock) }
                patchCounter++
                val mergeId = "merged:$patchCounter"
                val mergedPatch = ChangePatch(
                    patchId = mergeId,
                    parentHashes = heads.toList(),
                    nodeOrigin = localNodeId,
                    clock = mergedClock,
                    timestamp = System.currentTimeMillis(),
                    changeType = ChangeType.UPDATE,
                    objectId = headPatches[0].objectId,
                    targetValue = headPatches.joinToString("|") { p -> "node=${p.nodeOrigin}:$p.targetValue" },
                )
                patches.add(mergedPatch)
                heads.clear()
                heads.add(mergeId)
                listOf(mergeId)
            }
        }
        return resolved
    }

    val patchCount: Int get() = patches.size
    val currentHeads: Set<CharSequence> get() = heads.toSet()
    fun patchById(id: CharSequence): ChangePatch? = patches.find { it.patchId == id }
}

// ── NARS-3 adaptive nodal influence model ────────────────────────────────────

/**
 * NARS-3 (Non-Axiomatic Reasoning System level 3) adaptive nodal model.
 * Each node carries influence weights that evolve based on:
 * - Protocol residence time (how long the node has served a transport)
 * - Conflict frequency (split-brain detection rate)
 * - Data-specific affinity (which object types it specializes in)
 * - Timeseries reliability (uptime / response stability)
 */
data class NarsNodeProfile(
    val nodeId: CharSequence,
    val transport: Transport,
    val residenceTimeMs: Long = 0L,
    val objectAffinity: Map<DistributedObjectType, Double> = emptyMap(),
    val conflictCount: Long = 0,
    val conflictResolved: Long = 0,
    val reliabilityHistory: List<Double> = listOf(1.0),
    val lastHeartbeat: Long = 0,
) {
    /** Node influence score: composite of all four factors. */
    val influence: Double
        get() {
            val residence = kotlin.math.ln(1.0 + residenceTimeMs.toDouble() / 60000.0)
            val conflictRatio = if (conflictCount == 0L) 1.0
                else conflictResolved.toDouble() / conflictCount.toDouble()
            val maxAffinity = objectAffinity.values.maxOrNull() ?: 0.5
            val avgReliability = reliabilityHistory.takeLast(10).average()
            // Weighted composite: residence(0.25) + conflict(0.25) + affinity(0.25) + reliability(0.25)
            return 0.25 * (residence / 5.0).coerceIn(0.0, 1.0) +
                    0.25 * conflictRatio +
                    0.25 * maxAffinity +
                    0.25 * avgReliability
        }

    /** Update from heartbeat: append reliability sample. */
    fun tickReliability(ok: Boolean): NarsNodeProfile =
        copy(reliabilityHistory = reliabilityHistory + if (ok) 1.0 else 0.0)

    /** Specialize in an object type. */
    fun specialize(type: DistributedObjectType, weight: Double): NarsNodeProfile =
        copy(objectAffinity = objectAffinity + (type to (objectAffinity[type] ?: 0.0) + weight))

    /** Record conflict. */
    fun recordConflict(resolved: Boolean): NarsNodeProfile =
        copy(
            conflictCount = conflictCount + 1,
            conflictResolved = conflictResolved + if (resolved) 1 else 0,
        )
}

/**
 * Adaptive cluster: selects the leader based on NARS-3 influence scores.
 * Nodes compete dynamically — higher influence means more authoritative
 * for conflict resolution and object ownership.
 */
class NarsAdaptiveCluster(
    val profiles: MutableMap<CharSequence, NarsNodeProfile> = mutableMapOf(),
) {
    fun upsert(nodeId: CharSequence, transport: Transport): NarsNodeProfile {
        val existing = profiles[nodeId]
        val updated = if (existing != null) {
            existing.copy(
                residenceTimeMs = existing.residenceTimeMs + 60000,
                lastHeartbeat = System.currentTimeMillis(),
            )
        } else {
            NarsNodeProfile(nodeId, transport, lastHeartbeat = System.currentTimeMillis())
        }
        profiles[nodeId] = updated
        return updated
    }

    /** Find the most influential node for a given object type. */
    fun leaderFor(type: DistributedObjectType?): NarsNodeProfile? {
        val active = profiles.values.filter {
            System.currentTimeMillis() - it.lastHeartbeat < 300000 // 5min staleness
        }
        if (active.isEmpty()) return null
        return if (type != null) {
            active.maxByOrNull { p -> p.influence * (p.objectAffinity[type] ?: 0.3) }
        } else {
            active.maxByOrNull { it.influence }
        }
    }

    fun recordConflict(nodeId: CharSequence, resolved: Boolean) {
        profiles.computeIfPresent(nodeId) { _, p -> p.recordConflict(resolved) }
    }

    fun heartbeat(nodeId: CharSequence, ok: Boolean) {
        profiles.computeIfPresent(nodeId) { _, p -> p.tickReliability(ok) }
    }

    fun specialize(nodeId: CharSequence, type: DistributedObjectType, weight: Double) {
        profiles.computeIfPresent(nodeId) { _, p -> p.specialize(type, weight) }
    }

    val liveCount: Int get() = profiles.count {
        System.currentTimeMillis() - it.value.lastHeartbeat < 300000
    }
}

// ── Production system graph nodes + timeseries analysis ──────────────────────

/**
 * Production system graph node — represents a distributed object as a node
 * in the hazelnut cluster graph with edge-aware dependencies.
 */
enum class GraphNodeType {
    DATA_NODE,      // Holds distributed object value
    COORDINATOR,    // Orchestrates replication for a partition
    GATEKEEPER,     // Git-gated merge validator
    OBSERVER,       // Read-only replica
    BRIDGE,         // Cross-transport relay (e.g. QUIC -> IPFS)
}

data class GraphEdge(
    val source: CharSequence,
    val target: CharSequence,
    val edgeType: CharSequence,  // "replicates", "monitors", "bridges", "owns"
    val weight: Double = 1.0,
)

data class ProductionGraphNode(
    val nodeId: CharSequence,
    val nodeType: GraphNodeType,
    val transport: Transport,
    val partitions: Set<CharSequence> = emptySet(),
    val objectIds: Set<CharSequence> = emptySet(),
    val uptimeMs: Long = 0L,
    val lastActivity: Long = 0,
) {
    fun touch(): ProductionGraphNode =
        copy(
            uptimeMs = uptimeMs + (System.currentTimeMillis() - lastActivity).coerceAtLeast(0),
            lastActivity = System.currentTimeMillis(),
        )

    fun addPartition(p: CharSequence): ProductionGraphNode =
        copy(partitions = partitions + p)

    fun addObject(id: CharSequence): ProductionGraphNode =
        copy(objectIds = objectIds + id)
}

/**
 * Cluster topology graph — tracks node connections and partition assignments.
 */
class HazelTopology(
    val nodes: MutableMap<CharSequence, ProductionGraphNode> = mutableMapOf(),
    val edges: MutableList<GraphEdge> = mutableListOf(),
) {
    fun addNode(node: ProductionGraphNode) {
        nodes[node.nodeId] = node
    }

    fun addEdge(source: CharSequence, target: CharSequence, type: CharSequence, weight: Double = 1.0) {
        edges.add(GraphEdge(source, target, type, weight))
    }

    /** Find nodes that replicate a given object. */
    fun replicatorsFor(objectId: CharSequence): List<ProductionGraphNode> =
        nodes.values.filter { objectId in it.objectIds }

    /** Find node types in the cluster. */
    fun nodesByType(type: GraphNodeType): List<ProductionGraphNode> =
        nodes.values.filter { it.nodeType == type }

    /** Detect partition: nodes that hold the same objectId. */
    fun partitionsFor(objectId: CharSequence): Set<CharSequence> =
        nodes.values.filter { objectId in it.objectIds }.flatMap { it.partitions }.toSet()

    fun coordinatorFor(objectId: CharSequence): ProductionGraphNode? =
        nodes.values.filter { objectId in it.objectIds && it.nodeType == GraphNodeType.COORDINATOR }
            .maxByOrNull { it.uptimeMs }

    val nodeCount: Int get() = nodes.size
    val edgeCount: Int get() = edges.size
}

/**
 * Timeseries analysis for conflict patterns — tracks split-brain events,
 * merge latencies, and node reliability over time to identify systemic issues.
 */
data class TimeseriesSample(
    val timestamp: Long,
    val metricName: CharSequence,
    val value: Double,
    val nodeId: CharSequence,
    val labels: Map<CharSequence, CharSequence> = emptyMap(),
)

class ConflictAnalytics(
    val samples: MutableList<TimeseriesSample> = mutableListOf(),
    val windowMinutes: Int = 60,
) {
    fun record(nodeId: CharSequence, metric: CharSequence, value: Double, vararg labels: Pair<CharSequence, CharSequence>) {
        samples.add(
            TimeseriesSample(
                timestamp = System.currentTimeMillis(),
                metricName = metric,
                value = value,
                nodeId = nodeId,
                labels = labels.toMap(),
            ),
        )
    }

    /** Conflict rate per minute over the window. */
    fun conflictRate(nodeId: CharSequence? = null): Double {
        val cutoff = System.currentTimeMillis() - windowMinutes * 60000
        val filtered = if (nodeId != null) {
            samples.filter { it.timestamp >= cutoff && it.nodeId == nodeId }
        } else {
            samples.filter { it.timestamp >= cutoff }
        }
        val conflicts = filtered.count { it.metricName.toString().contains("conflict") }
        return conflicts.toDouble() / windowMinutes.toDouble()
    }

    /** Most conflicted node in the time window. */
    fun mostContestedNodeId(): CharSequence? {
        val cutoff = System.currentTimeMillis() - windowMinutes * 60000
        val contested = samples
            .filter { it.timestamp >= cutoff && it.metricName.toString().contains("conflict") }
            .groupBy { it.nodeId }
            .mapValues { (_, v) -> v.size }
        return contested.maxByOrNull { it.value }?.key
    }

    /** Average merge latency in the window. */
    fun avgMergeLatencyMs(): Double {
        val cutoff = System.currentTimeMillis() - windowMinutes * 60000
        val mergeSamples = samples.filter {
            it.timestamp >= cutoff && it.metricName.toString() == "merge_latency"
        }
        return if (mergeSamples.isEmpty()) 0.0 else mergeSamples.map { it.value }.average()
    }

    /** Split-brain event count by object type */
    fun splitBrainByObjectType(): Map<CharSequence, Int> {
        val cutoff = System.currentTimeMillis() - windowMinutes * 60000
        return samples
            .filter {
                it.timestamp >= cutoff &&
                    it.metricName.toString().contains("split_brain")
            }
            .mapNotNull { it.labels["objectType"] }
            .groupingBy { it }
            .eachCount()
    }

    fun sampleCount(): Int = samples.size
    fun prune(windowMinutes: Int) {
        val cutoff = System.currentTimeMillis() - windowMinutes * 60000L
        samples.removeAll { it.timestamp < cutoff }
    }
}

// ── Unified split-brain orchestrator ────────────────────────────────────────

/**
 * Orchestrates split-brain resolution across the hazelnut cluster:
 * - Crystore for git-gated CRDT patch tracking
 * - NARS-3 for adaptive node influence
 * - Topology for graph node coordination
 * - Analytics for timeseries conflict analysis
 * - All I/O through uring-facade-nio TransportBindings (no naive direct IO)
 */
class SplitBrainOrchestrator(
    private val crystore: GitGatedCrystore,
    private val cluster: NarsAdaptiveCluster,
    private val topology: HazelTopology,
    private val analytics: ConflictAnalytics,
) {
    /** Resolve a split-brain event: detect, select leader via NARS-3, merge via CRDT. */
    fun resolve(): List<CharSequence> {
        if (!crystore.isInSplitBrain()) return emptyList()

        // Record split-brain
        analytics.record(crystore.localNodeId, "split_brain", 1.0)

        // Find authoritative node via NARS-3
        val leader = cluster.leaderFor(null)
        val resolved = crystore.resolveConflicts(
            // Influence-based strategy selection
            if (leader != null && leader.influence > 0.7) CrdtStrategy.LAST_WRITER_WINS
            else CrdtStrategy.MERGEABLE_SEQUENCE,
        )

        // Update analytics
        analytics.record(
            crystore.localNodeId,
            "merge_latency",
            System.currentTimeMillis().toDouble(),
        )

        if (leader != null) {
            cluster.recordConflict(leader.nodeId, resolved.isNotEmpty())
            analytics.record(
                leader.nodeId,
                "conflict_resolved",
                if (resolved.isNotEmpty()) 1.0 else 0.0,
            )
        }

        return resolved
    }

    /** Register a new node in the cluster topology. */
    fun registerNode(node: ProductionGraphNode): ProductionGraphNode {
        cluster.upsert(node.nodeType.name + "_$node", node.transport)
        topology.addNode(node)
        analytics.record(node.nodeId, "node_register", 1.0, "transport" to node.transport.scheme)
        return node.touch()
    }

    /** Replicate an object across nodes via uring transport (no naive IO). */
    fun replicate(obj: DistributedObject, binding: TransportBinding) {
        val row = obj.toCouchRowVec()
        val payload = buildString {
            for (i in 0 until row.size) {
                if (i > 0) append(", ")
                append(row[i]?.toString() ?: "")
            }
        }.encodeToByteArray()
        binding.enqueueSend(payload)
        binding.facade.submit()

        crystore.record(obj.id, row["objectId"]?.toString() ?: obj.id.toString())
        analytics.record(
            binding.socket.id.toString(),
            "replication",
            obj.revision.toDouble(),
            "objectType" to obj.type.name,
        )
    }
}
