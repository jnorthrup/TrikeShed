package borg.trikeshed.bugzee

import borg.trikeshed.hazelnut.HazelnutClusterNode
import borg.trikeshed.hazelnut.Transport
import borg.trikeshed.hazelnut.TransportBinding
import borg.trikeshed.lib.emptyMap
import borg.trikeshed.lib.emptyList
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.userspace.FileImpl
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer

// - Bugzee Cluster: Hazelcast-inspired partitioned bug-tracking mesh -----------

// - Node state enum ------------------------------------------------------------

enum class BugzeeClusterState(val label: CharSequence) {
    ACTIVE("active"),
    PASSIVE("passive"),
    SUSPECT("suspect"),
    LEAVING("leaving"),
    REMOVED("removed"),
}

// - Cluster node (extends hazelnut HazelnutClusterNode shape + heartbeat) ------

data class BugzeeClusterNode(
    val nodeId: CharSequence,
    val transport: Transport,
    val address: CharSequence,
    val port: Int,
    val heartBeatInterval: Int = 5000,
    var state: BugzeeClusterState = BugzeeClusterState.ACTIVE,
    val weight: Int = 1,
    val tags: Map<CharSequence, CharSequence> = emptyMap(),
) {
    /** Full URI string for this node. */
    fun fullName(): CharSequence = "${transport.scheme}://$address:$port"

    /** Resolve to a hazelnut HazelnutClusterNode (lossless subset). */
    fun toHazelnutNode(): HazelnutClusterNode =
        HazelnutClusterNode(nodeId, transport, address, port)
}

// - Cluster config -------------------------------------------------------------

data class BugzeeClusterConfig(
    val nodes: List<BugzeeClusterNode> = emptyList(),
    val clusterName: CharSequence = "bugzee-default",
    val localNodeId: CharSequence = "bugzee-1",
    val partitionCount: Int = 271,
) {
    init {
        require(partitionCount > 0) { "partitionCount must be positive" }
    }
}

// - Rendezvous Hashing Partition ------------------------------------------------

/**
 * Maps bug IDs to cluster nodes via rendezvous (highest-weight) hashing.
 * Each node gets `weight` virtual slots; the bugId x virtualNode hash determines ownership.
 * Deterministic, no ring rebalancing needed - identical to Hazelcast's partitioning.
 */
class Partition(
    val partitionCount: Int,
) {
    companion object {
        /** FNV-1a hash over CharSequence minus fast, decent distribution. */
        private fun fnv1a(input: CharSequence): Long {
            var h: Long = -7046029254386353131L // FNV-1a 64-bit offset basis
            for (i in 0 until input.length) {
                h = h xor input[i].code.toLong()
                h = h * 1099511628211L // FNV-1a 64-bit prime
            }
            return h
        }

        /**
         * Highest-random-weight (rendezvous) scoring.
         * Given a key and candidate list, returns the node with the best hash score.
         */
        fun <T : Any> select(
            key: CharSequence,
            candidates: List<T>,
            nodeScore: (T, CharSequence) -> Long,
        ): T? = candidates
            .map { it to nodeScore(it, key) }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /** Score a node for a given bugId using FNV-1a of "bugId#virtualNodeIdx". */
    fun scoreNode(node: BugzeeClusterNode, bugId: CharSequence, virtualIdx: Int): Long {
        val composite = "$bugId#${node.nodeId}#$virtualIdx"
        return fnv1a(composite) xor (node.weight.toLong() shl 32)
    }

    /**
     * Determine which node owns a given bugId among the ACTIVE/LISTENING candidates.
     * Falls back to PASSIVE if no active nodes exist.
     */
    fun getNodeForBugId(
        bugId: CharSequence,
        nodes: List<BugzeeClusterNode>,
    ): BugzeeClusterNode? {
        val active = nodes.filter { n -> n.state == BugzeeClusterState.ACTIVE }
        val pool = if (active.isNotEmpty()) {
            active
        } else {
            nodes.filter { n -> n.state != BugzeeClusterState.REMOVED && n.state != BugzeeClusterState.LEAVING }
        }
        if (pool.isEmpty()) return null

        return select(
            key = bugId,
            candidates = pool,
            nodeScore = { node, key ->
                var bestScore = 0L
                for (v in 0 until node.weight) {
                    val s = scoreNode(node, key, v)
                    if (s > bestScore) bestScore = s
                }
                bestScore
            },
        )
    }
}

// - Gossip Protocol metadata ---------------------------------------------------

data class NodeMetadata(
    val nodeId: CharSequence,
    val state: BugzeeClusterState,
    val address: CharSequence,
    val port: Int,
    val transport: Transport,
    val version: Long,   // monotonic heartbeat version
    val lastSeen: Long,  // wall-clock millis epoch
    val weight: Int = 1,
    val partitions: List<Int> = emptyList(),
) {
    fun isNewerThan(other: NodeMetadata): Boolean = version > other.version

    /**
     * Merge two metadata entries: winner-take-all based on version.
     * On tie, REMOVED > LEAVING > SUSPECT > PASSIVE > ACTIVE (tombstone wins).
     */
    fun merge(other: NodeMetadata): NodeMetadata {
        require(nodeId == other.nodeId) { "Cannot merge metadata for different nodes" }
        val winner = if (isNewerThan(other)) this else other
        if (version == other.version && state != other.state) {
            val s = winState(this.state, other.state)
            return copy(
                state = s,
                version = version,
                lastSeen = maxOf(lastSeen, other.lastSeen),
                weight = maxOf(weight, other.weight),
            )
        }
        return winner.copy(
            lastSeen = maxOf(lastSeen, other.lastSeen),
            weight = maxOf(weight, other.weight),
        )
    }

    companion object {
        private fun winState(a: BugzeeClusterState, b: BugzeeClusterState): BugzeeClusterState {
            val ranks = listOf(
                BugzeeClusterState.REMOVED,
                BugzeeClusterState.LEAVING,
                BugzeeClusterState.SUSPECT,
                BugzeeClusterState.PASSIVE,
                BugzeeClusterState.ACTIVE,
            )
            return listOf(a, b).minByOrNull { ranks.indexOf(it) } ?: a
        }

        fun forNode(n: BugzeeClusterNode, ts: Long = System.currentTimeMillis()): NodeMetadata =
            NodeMetadata(
                nodeId = n.nodeId,
                state = n.state,
                address = n.address,
                port = n.port,
                transport = n.transport,
                version = 1L,
                lastSeen = ts,
                weight = n.weight,
            )
    }
}

/**
 * A gossip round: each node shares its view of the cluster membership.
 * Anti-entropy via versioned metadata + merge semantics.
 */
class GossipProtocol(
    private val localNodeId: CharSequence,
) {
    /** Known node metadata, keyed by nodeId. */
    val membershipMap: MutableMap<CharSequence, NodeMetadata> = mutableMapOf()

    /** Recent failure-detection suspects. */
    private val suspectTimers: MutableMap<CharSequence, Long> = mutableMapOf()

    private val suspectTimeoutMs: Long = 15000
    private val removedTimeoutMs: Long = 60000

    /** Register or update a node's metadata. Returns the merged entry. */
    fun update(metadata: NodeMetadata): NodeMetadata {
        val existing = membershipMap[metadata.nodeId]
        val merged = if (existing != null) existing.merge(metadata) else metadata
        membershipMap[metadata.nodeId] = merged
        return merged
    }

    /**
     * Produce a gossip payload: a subset of known metadata to send to peers.
     * In real code this would use random peer selection; here we return all entries.
     */
    fun gossipPayload(maxEntries: Int = 10): List<NodeMetadata> =
        membershipMap.values.take(maxEntries)

    /**
     * Receive gossip from peer: merge all received metadata entries.
     * Returns list of nodes whose state changed (delta).
     */
    fun receiveGossip(payload: List<NodeMetadata>): List<NodeMetadata> {
        val deltas = mutableListOf<NodeMetadata>()
        for (remote in payload) {
            val before = membershipMap[remote.nodeId]
            val merged = update(remote)
            if (before == null || before.version != merged.version || before.state != merged.state) {
                deltas += merged
            }
        }
        return deltas
    }

    /** Mark a node as suspect (heartbeat missed). */
    fun markSuspect(nodeId: CharSequence): CharSequence {
        val meta = membershipMap[nodeId] ?: return nodeId
        val merged = meta.copy(state = BugzeeClusterState.SUSPECT, version = meta.version + 1)
        membershipMap[nodeId] = merged
        suspectTimers[nodeId] = System.currentTimeMillis()
        return nodeId
    }

    /** Mark a node as removed after extended silence. */
    fun removeSilent(now: Long = System.currentTimeMillis()): List<CharSequence> {
        val removed = mutableListOf<CharSequence>()
        for ((nid, ts) in suspectTimers.toList()) {
            if (now - ts > removedTimeoutMs) {
                val meta = membershipMap[nid]
                if (meta != null) {
                    membershipMap[nid] = meta.copy(
                        state = BugzeeClusterState.REMOVED,
                        version = meta.version + 1,
                    )
                }
                removed += nid
            }
        }
        return removed
    }

    /** Get ACTIVE nodes from the membership map. */
    fun activeNodes(): List<NodeMetadata> =
        membershipMap.values.filter { it.state == BugzeeClusterState.ACTIVE }

    /** Current membership size. */
    val membershipSize: Int get() = membershipMap.size
}

// - Bugzee transport bindings --------------------------------------------------

/**
 * Create a TransportBinding for a given transport, socket, and uring facade.
 * Mirrors hazelnut's TransportBinding but adds stream tagging for gossip traffic.
 */
fun createBugzeeBinding(
    transport: Transport,
    facade: FunctionalUringFacade,
    socket: FileImpl,
    streamId: Int = 0,
): TransportBinding = TransportBinding(transport, facade, socket, streamId = streamId)

/**
 * Transport binding with cluster-aware metadata tagging.
 * Each binding tracks a gossip stream and a data stream.
 */
class BugzeeTransportBinding(
    binding: TransportBinding,
    val gossipStreamId: Int = 0,
    val dataStreamId: Int = 1,
) {
    val transport: Transport get() = binding.transport
    val facade: FunctionalUringFacade get() = binding.facade
    val socket: FileImpl get() = binding.socket

    private var dataCounter: Long = binding.userDataCounter

    private fun nextDataToken(): Long = dataCounter++

    /** Enqueue a gossip message. */
    fun enqueueGossip(payload: ByteArray): Long {
        val token = nextDataToken()
        val buf = ByteBuffer.wrap(payload)
        facade.enqueue(
            UringSubmission(
                opcode = transport.uringOpcode,
                fd = socket.id,
                addr = 0L,
                len = payload.size,
                offset = gossipStreamId.toLong(),
                userData = token,
                buffer = buf,
            ),
        )
        return token
    }

    /** Enqueue a data message (bug envelope, query, etc.). */
    fun enqueueData(payload: ByteArray): Long {
        val token = nextDataToken()
        val buf = ByteBuffer.wrap(payload)
        facade.enqueue(
            UringSubmission(
                opcode = transport.uringOpcode,
                fd = socket.id,
                addr = 0L,
                len = payload.size,
                offset = dataStreamId.toLong(),
                userData = token,
                buffer = buf,
            ),
        )
        return token
    }

    /** Enqueue a recv on the gossip stream. */
    fun enqueueGossipRecv(buffer: ByteBuffer): Long {
        val token = nextDataToken()
        facade.enqueue(
            UringSubmission(
                opcode = transport.recvOpcode,
                fd = socket.id,
                addr = 0L,
                len = buffer.remaining(),
                offset = gossipStreamId.toLong(),
                userData = token,
                buffer = buffer,
            ),
        )
        return token
    }

    /** Enqueue a recv on the data stream. */
    fun enqueueDataRecv(buffer: ByteBuffer): Long {
        val token = nextDataToken()
        facade.enqueue(
            UringSubmission(
                opcode = transport.recvOpcode,
                fd = socket.id,
                addr = 0L,
                len = buffer.remaining(),
                offset = dataStreamId.toLong(),
                userData = token,
                buffer = buffer,
            ),
        )
        return token
    }
}

// - Bugzee Cluster Registry: all-node state -------------------------------------

/**
 * In-memory representation of the distributed cluster membership.
 * Each node knows about every other node via the gossip protocol.
 */
class BugzeeClusterRegistry(
    val config: BugzeeClusterConfig,
) {
    val partition: Partition = Partition(config.partitionCount)
    val gossip: GossipProtocol = GossipProtocol(config.localNodeId)
    val nodeBindings: MutableMap<CharSequence, BugzeeTransportBinding> = mutableMapOf()
    var selfNode: BugzeeClusterNode = BugzeeClusterNode(
        nodeId = config.localNodeId,
        transport = config.nodes.find { it.nodeId == config.localNodeId }?.transport
            ?: Transport.HTX,
        address = "127.0.0.1",
        port = 8080,
        heartBeatInterval = 5000,
    )

    /** Register a binding for a remote node. */
    fun registerBinding(nodeId: CharSequence, binding: BugzeeTransportBinding) {
        nodeBindings[nodeId] = binding
    }

    /** Look up a node's binding, if connected. */
    fun getBinding(nodeId: CharSequence): BugzeeTransportBinding? =
        nodeBindings[nodeId]

    /** All known active nodes including self. */
    fun activeNodes(): List<BugzeeClusterNode> {
        val self = if (selfNode.state == BugzeeClusterState.ACTIVE) listOf(selfNode) else emptyList()
        val known = config.nodes.filter { n ->
            n.nodeId != config.localNodeId && gossip.activeNodes().any { it.nodeId == n.nodeId }
        }
        return self + known
    }

    /** Get the partition owner for a bugId. */
    fun getNodeForBugId(bugId: CharSequence): BugzeeClusterNode? =
        partition.getNodeForBugId(bugId, activeNodes())

    /** Full membership view. */
    fun membershipView(): List<NodeMetadata> =
        gossip.membershipMap.values.toList()
}

// - Cluster state snapshot (serializable) ---------------------------------------

data class BugzeeClusterSnapshot(
    val clusterName: CharSequence,
    val localNodeId: CharSequence,
    val membershipSize: Int,
    val activeNodeCount: Int,
    val partitionCount: Int,
    val localState: BugzeeClusterState,
    val metadata: List<NodeMetadata>,
) {
    fun toRowVec(): DocRowVec =
        DocRowVec(
            keys = listOf(
                "clusterName",
                "localNodeId",
                "membershipSize",
                "activeNodeCount",
                "partitionCount",
                "localState",
                "metadataCount",
            ),
            cells = listOf(
                clusterName,
                localNodeId,
                membershipSize,
                activeNodeCount,
                partitionCount,
                localState.name,
                metadata.size,
            ),
        )
}

// - BugzeeClusterService: join / leave / discover / broadcast -------------------

/**
 * High-level cluster service managing the full lifecycle of a Bugzee node.
 *
 * - **join()**: register self into cluster config, create bindings to all known peers
 * - **leave()**: transition state to LEAVING, notify peers via gossip
 * - **discover()**: return all known cluster nodes with their current state
 * - **broadcast()**: send a payload to all active nodes via transport bindings
 * - **getNodeForBugId()**: use rendezvous hashing to determine partition owner
 */
class BugzeeClusterService(
    private val config: BugzeeClusterConfig,
    private val registry: BugzeeClusterRegistry = BugzeeClusterRegistry(config),
) {
    val localNodeId: CharSequence get() = config.localNodeId
    val clusterName: CharSequence get() = config.clusterName
    val partitionCount: Int get() = config.partitionCount

    // - Lifecycle ---------------------------------------------------------------

    /**
     * Join the cluster: initialise self-node, register with all known peers,
     * and prime the gossip protocol with initial membership.
     */
    fun join(
        facade: FunctionalUringFacade? = null,
        socketProvider: ((BugzeeClusterNode) -> FileImpl)? = null,
    ): BugzeeClusterSnapshot {
        // Register self in gossip
        val selfMeta = NodeMetadata(
            nodeId = config.localNodeId,
            state = registry.selfNode.state,
            address = registry.selfNode.address,
            port = registry.selfNode.port,
            transport = registry.selfNode.transport,
            version = 1L,
            lastSeen = System.currentTimeMillis(),
            weight = registry.selfNode.weight,
        )
        registry.gossip.update(selfMeta)

        // Seed membership from config
        for (node in config.nodes) {
            if (node.nodeId != config.localNodeId) {
                registry.gossip.update(NodeMetadata.forNode(node))

                // Create transport binding if possible
                if (facade != null && socketProvider != null) {
                    val socket = socketProvider(node)
                    val binding = BugzeeTransportBinding(
                        createBugzeeBinding(node.transport, facade, socket),
                    )
                    registry.registerBinding(node.nodeId, binding)
                }
            }
        }

        return snapshot()
    }

    /**
     * Leave the cluster: transition to LEAVING state, update gossip metadata.
     * The node will eventually transition to REMOVED by peers' anti-entropy.
     */
    fun leave(): BugzeeClusterSnapshot {
        val current = registry.gossip.membershipMap[config.localNodeId]
        if (current != null) {
            val updated = current.copy(
                state = BugzeeClusterState.LEAVING,
                version = current.version + 1,
                lastSeen = System.currentTimeMillis(),
            )
            registry.gossip.update(updated)
        }
        registry.selfNode = registry.selfNode.copy(state = BugzeeClusterState.LEAVING)
        return snapshot()
    }

    /**
     * Full cluster removal: transition self to REMOVED.
     */
    fun remove(): BugzeeClusterSnapshot {
        val current = registry.gossip.membershipMap[config.localNodeId]
        if (current != null) {
            registry.gossip.update(
                current.copy(
                    state = BugzeeClusterState.REMOVED,
                    version = current.version + 1,
                ),
            )
        }
        registry.selfNode = registry.selfNode.copy(state = BugzeeClusterState.REMOVED)
        return snapshot()
    }

    // - Discovery ---------------------------------------------------------------

    /**
     * Discover all known cluster members and their current state.
     * Returns a snapshot for each node.
     */
    fun discover(): List<NodeMetadata> =
        registry.gossip.membershipMap.values.toList()

    /**
     * Get a specific node by ID from the membership map.
     */
    fun discover(nodeId: CharSequence): NodeMetadata? =
        registry.gossip.membershipMap[nodeId]

    // - Broadcast ---------------------------------------------------------------

    /**
     * Broadcast a payload to all active nodes via their transport bindings.
     * Returns the list of userData tokens for enqueued sends.
     *
     * If a node has no binding, it is skipped (disconnected).
     */
    fun broadcast(payload: ByteArray): Map<CharSequence, Long> {
        val tokens = mutableMapOf<CharSequence, Long>()
        for ((nodeId, binding) in registry.nodeBindings) {
            val meta = registry.gossip.membershipMap[nodeId]
            if (meta?.state == BugzeeClusterState.ACTIVE) {
                val token = binding.enqueueGossip(payload)
                tokens[nodeId] = token
            }
        }
        // Submit all queued uring ops
        val binding = registry.nodeBindings.values.firstOrNull()
        binding?.facade?.submit()
        return tokens
    }

    /**
     * Broadcast a heartbeat signal (empty payload) to all peers.
     */
    fun heartbeat(): Map<CharSequence, Long> =
        broadcast(byteArrayOf())

    // - Partition lookup --------------------------------------------------------

    /**
     * Determine the node responsible for a given bugId via rendezvous hashing.
     * Returns null if no active nodes are available.
     */
    fun getNodeForBugId(bugId: CharSequence): BugzeeClusterNode? =
        registry.getNodeForBugId(bugId)

    /**
     * Get all partition IDs owned by a specific node.
     * Used for debugging and visualisations.
     */
    fun getPartitionsForNode(nodeId: CharSequence): List<Int> =
        (0 until config.partitionCount)
            .filter { i ->
                val assigned = registry.partition.getNodeForBugId(
                    "partition-$i",
                    registry.activeNodes(),
                )
                assigned?.nodeId == nodeId
            }

    // - State management --------------------------------------------------------

    /** Set a node's state (for gossip propagation). */
    fun setNodeState(nodeId: CharSequence, state: BugzeeClusterState) {
        val meta = registry.gossip.membershipMap[nodeId]
        if (meta != null) {
            registry.gossip.update(
                meta.copy(state = state, version = meta.version + 1, lastSeen = System.currentTimeMillis()),
            )
        }
    }

    /**
     * Run anti-entropy: merge received gossip and expire silent suspects.
     */
    fun antiEntropy(received: List<NodeMetadata>): List<NodeMetadata> {
        val deltas = registry.gossip.receiveGossip(received)
        val removed = registry.gossip.removeSilent()
        return deltas + removed.map { rid ->
            registry.gossip.membershipMap[rid] ?: NodeMetadata(
                nodeId = rid,
                state = BugzeeClusterState.REMOVED,
                address = "",
                port = 0,
                transport = Transport.HTX,
                version = 0L,
                lastSeen = System.currentTimeMillis(),
            )
        }
    }

    // - Snapshot ----------------------------------------------------------------

    /** Capture a point-in-time snapshot of cluster state. */
    fun snapshot(): BugzeeClusterSnapshot =
        BugzeeClusterSnapshot(
            clusterName = clusterName,
            localNodeId = localNodeId,
            membershipSize = registry.gossip.membershipSize,
            activeNodeCount = registry.gossip.activeNodes().size,
            partitionCount = config.partitionCount,
            localState = registry.selfNode.state,
            metadata = registry.gossip.membershipMap.values.toList(),
        )
}

// - Couch grounding: cluster snapshot - DocRowVec-----------------------------------

fun BugzeeClusterRegistry.toRowVec(): DocRowVec {
    val self = selfNode
    val meta = listOf(
        "localNodeId",
        "state",
        "transport",
        "address",
        "port",
        "membershipSize",
        "activeNodes",
        "partitionCount",
        "boundRemotes",
    )
    val cells = listOf(
        config.localNodeId,
        self.state.name,
        self.transport.scheme,
        self.address,
        self.port,
        gossip.membershipSize,
        gossip.activeNodes().size,
        config.partitionCount,
        nodeBindings.size,
    )
    return DocRowVec(
        keys = meta,
        cells = cells,
    )
}
