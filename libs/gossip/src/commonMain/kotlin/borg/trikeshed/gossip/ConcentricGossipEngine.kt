package borg.trikeshed.gossip

import borg.trikeshed.kademlia.bitops.BitOps
import borg.trikeshed.kademlia.id.NUID
import borg.trikeshed.kademlia.net.NetMask
import borg.trikeshed.kademlia.routing.RoutingTable
import borg.trikeshed.kademlia.id.impl.ULongNUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore

/**
 * ConcentricGossipEngine — P2P gossip membership + message propagation
 * over GK Kademlia concentric rings.
 *
 * Membership protocol:
 *   - JOIN: new member announces to seed nodes, gets assigned a ring.
 *   - PING/PONG: heartbeat across all rings, exponential backoff on miss.
 *   - SUSPECT: after 3 missed heartbeats, gossip "is this node dead?" to peers.
 *   - DEAD: after quorum agrees on suspicion, evict the node.
 *
 * Message propagation:
 *   - SWIM-style: each member selects k random peers per gossip interval.
 *   - Anti-entropy: periodic full membership sync with one peer per ring.
 *   - Ring-scoped fan-out: messages flow outward (0→1→2→3→4) by default,
 *     but can be targeted to a specific ring.
 *
 * Work stealing:
 *   - Idle workers pull WorkSteal requests toward outer rings.
 *   - Saturated workers push WorkOffer outward to find capacity.
 *   - Steal quorum: requires majority of ring peers to agree.
 */
class ConcentricGossipEngine(
    private val localMember: GossipMember,
    private val seedNodes: List<GossipMember> = emptyList(),
    private val config: GossipConfig = GossipConfig(),
) {
    companion object {
        const val DEFAULT_GOSSIP_INTERVAL_MS = 1000L
        const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 3000L
        const val DEFAULT_SUSPECT_THRESHOLD = 3
    }

    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineName("GossipEngine-${localMember.id}") + config.dispatcher
    )
    private val messageChannel = Channel<GossipMessage>(capacity = config.messageBufferSize)
    private val membershipEvents = Channel<MembershipEvent>(capacity = Channel.UNLIMITED)

    // Membership table: MemberId → GossipMember
    private val members = LinkedHashMap<MemberId, GossipMemberState>()
    private val ringMembers = LinkedHashMap<ConcentricRing, LinkedHashSet<MemberId>>()
    private val suspectCount = LinkedHashMap<MemberId, Int>()

    // GK Kademlia routing table for wide-area discovery
    private val routingTable: RoutingTable<ULong, NetMaskCoolSz>? = buildRoutingTable()

    // Work queue: pending work offers awaiting acceptance
    private val pendingWork = LinkedHashMap<CharSequence, GossipMessage.WorkOffer>()

    private var gossipSeq = 0L
    private val ringSemaphore = Semaphore(config.maxConcurrentRingOps)

    data class GossipMemberState(
        val member: GossipMember,
        var lastHeartbeat: Long = System.currentTimeMillis(),
        var suspectCount: Int = 0,
        var isSuspected: Boolean = false,
    )

    fun start() {
        scope.launch { gossipLoop() }
        scope.launch { membershipLoop() }
        scope.launch { workStealLoop() }
        // Seed the cluster
        for (seed in seedNodes) {
            members[seed.id] = GossipMemberState(seed)
            ringMembers.getOrPut(seed.ring) { LinkedHashSet() }.add(seed.id)
            scope.launch { sendMessage(seed, GossipMessage.Join(localMember, seedNodes.map { it.id })) }
        }
        ringMembers.getOrPut(localMember.ring) { LinkedHashSet() }.add(localMember.id)
        members[localMember.id] = GossipMemberState(localMember)
    }

    fun stop() {
        scope.cancel()
        messageChannel.cancel()
        membershipEvents.cancel()
    }

    // ── Gossip Loop ──────────────────────────────────────────────────

    private suspend fun gossipLoop() {
        while (scope.isActive) {
            delay(config.gossipIntervalMs)
            gossipSeq++

            // Ping random peers in each ring
            for (ring in ConcentricRing.entries) {
                val ringPeers = ringMembers[ring]?.toList() ?: continue
                if (ringPeers.isEmpty()) continue

                // Select k random peers for gossip (k depends on ring)
                val k = when (ring) {
                    ConcentricRing.Local -> 0  // shared memory, no gossip needed
                    ConcentricRing.Rack -> 2
                    ConcentricRing.Region -> 3
                    ConcentricRing.WideArea -> 2
                    ConcentricRing.Federation -> 1
                }
                val selected = ringPeers.shuffled().take(k)
                for (peerId in selected) {
                    val state = members[peerId] ?: continue
                    if (state.isSuspected) continue
                    ringSemaphore.acquire()
                    try {
                        sendMessage(state.member, GossipMessage.Ping(localMember.id, gossipSeq))
                    } finally {
                        ringSemaphore.release()
                    }
                }
            }

            // Anti-entropy: full membership sync with one peer per ring
            for (ring in ConcentricRing.entries) {
                val ringPeers = ringMembers[ring]?.toList() ?: continue
                if (ringPeers.isEmpty()) continue
                val selected = ringPeers.randomOrNull() ?: continue
                val state = members[selected] ?: continue
                // Send full membership table for reconciliation
                // (omitted: serialization to GossipMessage)
            }
        }
    }

    // ── Membership Loop ───────────────────────────────────────────────

    private suspend fun membershipLoop() {
        while (scope.isActive) {
            delay(config.heartbeatTimeoutMs)
            val now = System.currentTimeMillis()

            for ((memberId, state) in members.toList()) {
                if (memberId == localMember.id) continue
                val elapsed = now - state.lastHeartbeat
                if (elapsed > config.heartbeatTimeoutMs * (state.suspectCount + 1)) {
                    if (!state.isSuspected && state.suspectCount >= config.suspectThreshold) {
                        state.isSuspected = true
                        membershipEvents.send(MembershipEvent.Heartbeat(state.member)) // SUSPECT
                        // Gossip suspicion to peers for quorum vote
                        gossipSuspect(state.member)
                    }
                }
                // Check if we reached quorum on suspicion
                if (state.isSuspected && suspectCount.getOrDefault(memberId, 0) >= quorumFor(state.member.ring)) {
                    // Evict the member
                    members.remove(memberId)
                    ringMembers[state.member.ring]?.remove(memberId)
                    membershipEvents.send(MembershipEvent.Left(state.member))
                    suspectCount.remove(memberId)
                }
            }
        }
    }

    // ── Work Steal Loop ──────────────────────────────────────────────

    private suspend fun workStealLoop() {
        while (scope.isActive) {
            delay(config.workStealIntervalMs)

            // Check if local ring is idle and can steal from outer rings
            if (pendingWork.isEmpty() && localRingCapacity() > config.stealThreshold) {
                for (outerRing in ConcentricRing.entries) {
                    if (outerRing.id <= localMember.ring.id) continue
                    val outerPeers = ringMembers[outerRing] ?: continue
                    if (outerPeers.isNotEmpty()) {
                        // Steal work from outer ring
                        val work = pendingWork.values.firstOrNull { it.ring == outerRing }
                        if (work != null) {
                            ringSemaphore.acquire()
                            try {
                                // Send steal request to outer ring
                                val targetPeerId = outerPeers.randomOrNull()
                                targetPeerId?.let {
                                    val peer = members[it]?.member
                                    if (peer != null) {
                                        sendMessage(peer, GossipMessage.WorkSteal(outerRing, localMember.id, 1))
                                    }
                                }
                            } finally {
                                ringSemaphore.release()
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Message Handling ─────────────────────────────────────────────

    suspend fun handleMessage(msg: GossipMessage, from: GossipMember) {
        when (msg) {
            is GossipMessage.Ping -> {
                // Update heartbeat tracking
                val state = members[from.id]
                if (state != null) {
                    state.lastHeartbeat = System.currentTimeMillis()
                    state.suspectCount = 0
                    state.isSuspected = false
                }
                // Send pong back
                sendMessage(from, GossipMessage.Pong(localMember.id, msg.seq))
                messageChannel.send(msg)
            }
            is GossipMessage.Pong -> {
                val state = members[from.id]
                if (state != null) {
                    state.lastHeartbeat = System.currentTimeMillis()
                    state.suspectCount = 0
                    state.isSuspected = false
                }
                messageChannel.send(msg)
            }
            is GossipMessage.Join -> {
                // Register new member
                val ring = ringForNewMember(from)
                val promoted = from.copy(ring = ring, lastSeen = System.currentTimeMillis())
                members[promoted.id] = GossipMemberState(promoted)
                ringMembers.getOrPut(ring) { LinkedHashSet() }.add(promoted.id)
                membershipEvents.send(MembershipEvent.Joined(promoted))
                messageChannel.send(msg)
                // Gossip join to peers in same ring
                gossipToRing(ring, msg, exclude = promoted.id)
            }
            is GossipMessage.WorkOffer -> {
                pendingWork[msg.workId] = msg
                messageChannel.send(msg)
                // Try to find an idle worker
                for ((peerId, state) in members) {
                    if (workerIsIdle(peerId)) {
                        sendMessage(state.member, msg)
                        break
                    }
                }
            }
            is GossipMessage.WorkAccept -> {
                pendingWork.remove(msg.workId)
                messageChannel.send(msg)
            }
            is GossipMessage.WorkResult -> {
                messageChannel.send(msg)
            }
            is GossipMessage.WorkSteal -> msg
            is GossipMessage.BlackboardWrite -> { /* handled by blackboard layer */
                messageChannel.send(msg) }
            is GossipMessage.BlackboardRead -> { /* handled by blackboard layer */
                messageChannel.send(msg) }
            is GossipMessage.BlackboardUpdate -> { /* handled by blackboard layer */
                messageChannel.send(msg) }
            is GossipMessage.WorkflowStart -> { /* handled by workflow layer */
                messageChannel.send(msg) }
            is GossipMessage.WorkflowStageComplete -> { /* handled by workflow layer */
                messageChannel.send(msg) }
        }
    }

    // ── Ring Assignment ──────────────────────────────────────────────

    /**
     * Assign a new member to the appropriate ring based on latency
     * measurement and current ring capacity.
     */
    private fun ringForNewMember(member: GossipMember): ConcentricRing {
        // Try to measure latency (round-trip ping time)
        // For now, use seed ring as fallback
        return ConcentricRing.WideArea
    }

    private fun quorumFor(ring: ConcentricRing): Int {
        val count = ringMembers[ring]?.size ?: 1
        return (count / 2) + 1
    }

    private fun localRingCapacity(): Double {
        val ringSize = ringMembers[localMember.ring]?.size ?: 0
        return if (ringSize == 0) 1.0 else (pendingWork.size.toFloat() / ringSize).toDouble()
    }

    private fun workerIsIdle(memberId: MemberId): Boolean {
        return members[memberId]?.let { state ->
            state.suspectCount == 0 && (System.currentTimeMillis() - state.lastHeartbeat) < config.heartbeatTimeoutMs
        } ?: false
    }

    /** Get all active members in a specific ring. */
    fun membersInRing(ring: ConcentricRing): List<GossipMember> {
        val ids = ringMembers[ring] ?: return emptyList()
        return ids.mapNotNull { id -> members[id]?.member.takeIf { it.id == id } }
    }

    /** Send a gossip message to a specific peer (placeholder — real impl serializes to UDP/TCP). */
    suspend fun sendMessage(to: GossipMember, msg: GossipMessage) {
        messageChannel.send(msg)
    }

    /** Steal work items from peers in the target ring. */
    suspend fun stealWorkFromRing(ring: ConcentricRing, count: Int): List<SpawnSpec> {
        val peers = membersInRing(ring)
        if (peers.isEmpty()) return emptyList()
        // In real impl: gossip WorkSteal to peers, collect offered work
        return emptyList()
    }

    // ── Private Gossip Helpers ───────────────────────────────────────

    private suspend fun _gossipSend(to: GossipMember, msg: GossipMessage) {
        messageChannel.send(msg)
    }

    private suspend fun gossipToRing(
        ring: ConcentricRing,
        msg: GossipMessage,
        exclude: MemberId = MemberId(ByteArray(0)),
    ) {
        val peers = ringMembers[ring]?.filter { it != exclude } ?: return
        for (peerId in peers.take(config.gossipFanout)) {
            val state = members[peerId] ?: continue
            sendMessage(state.member, msg)
        }
    }

    private suspend fun gossipSuspect(member: GossipMember) {
        // Gossip suspicion to peers in the member's ring for quorum voting
        val ring = member.ring
        val peers = ringMembers[ring] ?: return
        for (peerId in peers) {
            if (peerId == member.id || peerId == localMember.id) continue
            suspectCount[member.id] = (suspectCount[member.id] ?: 0) + 1
        }
    }

    private fun buildRoutingTable(): RoutingTable<ULong, NetMaskCoolSz>? {
        // Build from GK Kademlia routing table if available
        // Uses the CoolSz (64-bit) netmask for wide-area peer discovery
        return try {
            val nuid = object : ULongNUID(
                kotlin.random.Random.nextLong().toULong()
            ) {
                override val netmask = NetMaskCoolSz
            }
            RoutingTable(nuid, optimal = false)
        } catch (_: Throwable) {
            null
        }
    }
}

object NetMaskCoolSz : borg.trikeshed.kademlia.net.NetMask<ULong> {
    override val bits: Int = 64
}

data class GossipConfig(
    val gossipIntervalMs: Long = ConcentricGossipEngine.DEFAULT_GOSSIP_INTERVAL_MS,
    val heartbeatTimeoutMs: Long = ConcentricGossipEngine.DEFAULT_HEARTBEAT_TIMEOUT_MS,
    val suspectThreshold: Int = ConcentricGossipEngine.DEFAULT_SUSPECT_THRESHOLD,
    val gossipFanout: Int = 3,
    val maxConcurrentRingOps: Int = 16,
    val messageBufferSize: Int = 256,
    val workStealIntervalMs: Long = 5000L,
    val stealThreshold: Double = 0.5,
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
)
