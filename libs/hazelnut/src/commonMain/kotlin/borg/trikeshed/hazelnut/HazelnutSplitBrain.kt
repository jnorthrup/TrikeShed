package borg.trikeshed.hazelnut

import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.userspace.FileImpl
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.math.ln

// ── Pijul Hash & Patch Logic ────────────────────────────────────────────────

/**
 * Simulated Pijul Patch Hash.
 * In a full implementation, this would wrap a cryptographic hash (SHA-256)
 * of the patch content. Here we use a String for KMM commonMain compatibility.
 */
@JvmInline
value class PatchHash(val value: String) {
    override fun toString(): String = value
}

/**
 * ChangePatch: The atomic unit of state change, modeled after Pijul.
 * Patches are independent and can be commuted if they don't touch the same context.
 */
data class ChangePatch(
    val hash: PatchHash,
    val author: CharSequence,
    val timestamp: Long,
    val changeType: PatchChangeType,
    val objectId: CharSequence,
    val payload: CharSequence,
    val parents: List<PatchHash> = emptyList(),
)

enum class PatchChangeType {
    CREATE, MODIFY, DELETE, MERGE
}

/**
 * Returns true if two patches commute (affect disjoint contexts).
 * Simplified: patches on different objects commute; patches on the same object do not.
 */
fun ChangePatch.commutesWith(other: ChangePatch): Boolean {
    return this.objectId != other.objectId
}

// ── Vector Clock for Causal Ordering ────────────────────────────────────────

data class VClock(
    val clocks: Map<CharSequence, Long> = emptyMap()
) {
    fun tick(node: CharSequence): VClock =
        VClock(clocks + (node to (clocks[node] ?: 0L) + 1))

    fun merge(other: VClock): VClock {
        val keys = clocks.keys + other.clocks.keys
        return VClock(keys.associateWith { k ->
            maxOf(clocks[k] ?: 0, other.clocks[k] ?: 0)
        })
    }

    fun dominates(other: VClock): Boolean =
        other.clocks.all { (k, v) -> (clocks[k] ?: 0L) >= v }

    fun isConcurrent(other: VClock): Boolean =
        !dominates(other) && !other.dominates(this)
}

// ── Git-Gated CRDT Store ────────────────────────────────────────────────────

/**
 * Git-Gated Crystore.
 * Enforces that new states must be reachable from the current head (git-gating).
 * If concurrent patches appear (split-brain), it uses the CRDT strategy to resolve.
 */
enum class CrdtStrategy {
    LWW,          // Last Writer Wins (Time-based)
    PN_COUNTER,   // Positive/Negative Counter
    MERGE_SET     // Set Union
}

class GitGatedCrystore(
    val nodeId: CharSequence,
    var head: PatchHash? = null,
    val patches: MutableList<ChangePatch> = mutableListOf(),
    var clock: VClock = VClock()
) {
    /**
     * Attempt to push a change.
     * If the current history is divergent (split-brain), this returns null.
     */
    fun push(patch: ChangePatch): Boolean {
        val isChild = patch.parents.isEmpty() || patch.parents.contains(head)
        if (!isChild) return false // Git-gate violation

        patches.add(patch)
        head = patch.hash
        clock = clock.tick(nodeId)
        return true
    }

    /**
     * Resolve a split-brain state using the specified CRDT strategy.
     * Accepts a list of concurrent patches from a remote peer.
     */
    fun resolve(remotes: List<ChangePatch>, strategy: CrdtStrategy): ChangePatch? {
        if (remotes.isEmpty()) return null

        // 1. Sort remotes by timestamp for LWW or deterministic merge
        val sortedRemotes = remotes.sortedBy { it.timestamp }
        val winner = when (strategy) {
            CrdtStrategy.LWW -> sortedRemotes.maxByOrNull { it.timestamp }
            CrdtStrategy.PN_COUNTER -> {
                // In a counter scenario, we merge values. Here we pick the longest payload as "merged"
                sortedRemotes.maxByOrNull { it.payload.length }
            }
            CrdtStrategy.MERGE_SET -> {
                // Return a synthetic merge patch containing all payloads
                ChangePatch(
                    hash = PatchHash("merge:${System.currentTimeMillis()}"),
                    author = nodeId,
                    timestamp = System.currentTimeMillis(),
                    changeType = PatchChangeType.MERGE,
                    objectId = remotes[0].objectId,
                    payload = remotes.joinToString("|") { it.payload },
                    parents = remotes.map { it.hash }
                )
            }
        } ?: sortedRemotes.first()

        // 2. Apply the winner
        patches.addAll(remotes) // Keep all history
        head = winner.hash
        clock = clock.merge(VClock(remotes.associate { it.author to 1L }))
        return winner
    }
}

// ── NARS-3 Adaptive Nodal Influence ─────────────────────────────────────────

/**
 * NARS-3 (Simplified Non-Axiomatic Reasoning) Profile.
 * Measures a node's authority based on:
 * 1. Residence Time: How long it has been part of the cluster.
 * 2. Protocol Affinity: Performance on specific transport protocols.
 * 3. Data Affinity: Specialization on specific distributed object types.
 * 4. Stability: Inverse of conflict generation.
 */
data class NarsProfile(
    val nodeId: CharSequence,
    val residenceTimeMs: Long = 0L,
    val protocolInfluence: Map<Transport, Double> = emptyMap(),
    val dataAffinity: Map<DistributedObjectType, Double> = emptyMap(),
    val conflictsGenerated: Int = 0,
    val conflictsResolved: Int = 0,
    val uptimeMs: Long = 0L
) {
    // Composite influence score [0.0, 1.0]
    val influence: Double
        get() {
            val residenceWeight = ln(1.0 + residenceTimeMs / 60000.0) / 10.0 // Normalize roughly
            val stabilityWeight = if (conflictsGenerated == 0) 1.0
            else (conflictsResolved.toDouble() / conflictsGenerated).coerceIn(0.0, 1.0)
            val maxDataAffinity = dataAffinity.values.maxOrNull() ?: 0.0
            val maxProtoAffinity = protocolInfluence.values.maxOrNull() ?: 0.0

            return (
                (residenceWeight.coerceIn(0.0, 1.0) * 0.3) +
                (stabilityWeight * 0.4) +
                (maxDataAffinity * 0.15) +
                (maxProtoAffinity * 0.15)
            ).coerceIn(0.0, 1.0)
        }

    fun tick(timeDeltaMs: Long): NarsProfile = copy(residenceTimeMs = residenceTimeMs + timeDeltaMs)
}

// ── Conflict & Timeseries Analysis ──────────────────────────────────────────

data class TimeseriesPoint(
    val timestamp: Long,
    val nodeId: CharSequence,
    val metricName: CharSequence,
    val value: Double
)

class ConflictAnalytics(
    val windowMs: Long = 3600000 // 1 hour
) {
    val events: MutableList<TimeseriesPoint> = mutableListOf()

    fun record(nodeId: CharSequence, name: CharSequence, value: Double) {
        events.add(TimeseriesPoint(System.currentTimeMillis(), nodeId, name, value))
    }

    fun getConflictRate(nodeId: CharSequence): Double {
        val cutoff = System.currentTimeMillis() - windowMs
        val relevant = events.filter { it.timestamp > cutoff && it.nodeId == nodeId && it.metricName == "conflict" }
        return relevant.size.toDouble()
    }

    fun getNetworkStability(): Double {
        val cutoff = System.currentTimeMillis() - windowMs
        val splits = events.count { it.timestamp > cutoff && it.metricName == "split_brain" }
        // Stability decreases as splits increase
        return (1.0 - (splits / 100.0)).coerceIn(0.0, 1.0)
    }

    fun prune(maxEvents: Int) {
        if (events.size > maxEvents) {
            events.subList(0, events.size - maxEvents).clear()
        }
    }
}

// ── Production System Graph Nodes ───────────────────────────────────────────

data class ProductionGraphNode(
    val id: CharSequence,
    val status: NodeStatus,
    val transport: Transport,
    val lastHeartbeat: Long,
    val partitions: MutableList<CharSequence> = mutableListOf()
)

enum class NodeStatus {
    ACTIVE, SYNCING, SPLIT_BRAIN, GATE_CLOSED
}

class ProductionGraph {
    val nodes: MutableMap<CharSequence, ProductionGraphNode> = mutableMapOf()

    fun register(node: ProductionGraphNode) {
        nodes[node.id] = node
    }

    fun updateStatus(id: CharSequence, status: NodeStatus) {
        nodes[id] = nodes[id]?.copy(status = status) ?: return
    }

    fun findReplicasFor(objectId: CharSequence): List<ProductionGraphNode> =
        nodes.values.filter { objectId in it.partitions }
    
    fun activeNodes(): List<ProductionGraphNode> =
        nodes.values.filter { it.status == NodeStatus.ACTIVE || it.status == NodeStatus.SYNCING }
}

// ── Split-Brain Orchestrator (The Brain) ────────────────────────────────────

/**
 * Orchestrates split-brain detection and resolution.
 * All I/O is strictly bound to [TransportBinding] (uring-nio).
 */
class SplitBrainOrchestrator(
    private val crystore: GitGatedCrystore,
    private val profiles: MutableMap<CharSequence, NarsProfile> = mutableMapOf(),
    private val graph: ProductionGraph = ProductionGraph(),
    private val analytics: ConflictAnalytics = ConflictAnalytics(),
    private val binding: TransportBinding
) {
    /**
     * Syncs with a remote node via uring-nio transport.
     * Sends the current head hash and expects a list of remote patches.
     * Resolution happens locally after data arrives.
     */
    fun initiateSync(remoteNodeId: CharSequence, myClock: VClock, localHead: PatchHash?) {
        // 1. Encode request
        val requestPayload = "${crystore.nodeId}:${myClock.clocks}:${localHead ?: "null"}"
        
        // 2. IO Bound to Uring
        binding.enqueueSend(requestPayload.encodeToByteArray())
        binding.facade.submit() // Non-blocking submit
        
        // 3. In a real scenario, we'd await completion in the CQ (Completion Queue).
        // For this orchestrator design, we assume this method triggers the flow.
    }

    /**
     * Process incoming remote state.
     * This is where the "Split Brain" happens: we compare remote head vs local head.
     */
    fun onRemoteData(remotePatches: List<ChangePatch>, remoteNodeId: CharSequence) {
        analytics.record(remoteNodeId, "contact", 1.0)
        
        // Check for split brain
        val isSplitBrain = remotePatches.any { p -> 
            crystore.head != null && p.parents.none { it == crystore.head!! } 
        }

        if (isSplitBrain) {
            analytics.record(remoteNodeId, "split_brain", 1.0)
            handleSplitBrain(remotePatches, remoteNodeId)
        } else {
            // Normal sync
            remotePatches.forEach { crystore.push(it) }
            analytics.record(remoteNodeId, "sync_success", 1.0)
        }
    }

    private fun handleSplitBrain(remotePatches: List<ChangePatch>, remoteNodeId: CharSequence) {
        // 1. Get profiles for decision making
        val localProfile = profiles[crystore.nodeId] ?: NarsProfile(crystore.nodeId)
        val remoteProfile = profiles[remoteNodeId] ?: NarsProfile(remoteNodeId)

        // 2. Determine strategy based on NARS influence
        // If remote node has significantly higher influence, yield.
        // If local node has higher influence, force LWW.
        // If close, use MERGE_SET (expensive but safe).
        
        val strategy = when {
            remoteProfile.influence > localProfile.influence + 0.2 -> CrdtStrategy.LWW
            localProfile.influence > remoteProfile.influence + 0.2 -> CrdtStrategy.LWW
            else -> CrdtStrategy.MERGE_SET
        }

        analytics.record(crystore.nodeId, "resolution_strategy", strategy.ordinal.toDouble())

        // 3. Resolve
        val result = crystore.resolve(remotePatches, strategy)
        if (result != null) {
            analytics.record(crystore.nodeId, "conflict_resolved", 1.0)
            // Send resolved merge back to remote via uring
            val resolutionMsg = "RESOLVED:${result.hash.value}"
            binding.enqueueSend(resolutionMsg.encodeToByteArray())
            binding.facade.submit()
        }
    }

    fun getStableNodes(): List<ProductionGraphNode> {
        return graph.activeNodes().filter { node ->
            val p = profiles[node.id]
            p != null && p.conflictsGenerated < 5
        }
    }
}
