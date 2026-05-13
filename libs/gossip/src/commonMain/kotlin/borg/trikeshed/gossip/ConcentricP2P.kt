package borg.trikeshed.gossip

/**
 * ConcentricP2P — Gossip-based subnet with concentric rings of
 * increasing distance from the local node.
 *
 * Ring 0: local co-process (same host, shared memory)
 * Ring 1: same rack / latency < 1ms (LAN gossip)
 * Ring 2: same region / latency < 10ms (regional mesh)
 * Ring 3: wide-area / latency < 100ms (internet gossip)
 * Ring 4: federation / any latency (cross-org)
 *
 * Each ring is a k-bucket subset from the GK Kademlia routing table,
 * partitioned by XOR distance. Gossip messages fan outward from Ring 0
 * inward-to-outward (0→1→2→3→4) but can also flow inward as
 * acknowledgments (4→3→2→1→0).
 *
 * Ephemeral workers spawn locally (Ring 0), then migrate outward
 * via gossip if the local ring is saturated. Work stealing pulls
 * tasks from saturated rings upward (Ring 0 ← Ring 1 ← ...).
 */
enum class ConcentricRing(val id: Int, val maxLatencyMs: Long, val description: CharSequence) {
    Local(0, 0, "same host, shared memory"),
    Rack(1, 1, "same rack, < 1ms"),
    Region(2, 10, "same region, < 10ms"),
    WideArea(3, 100, "internet, < 100ms"),
    Federation(4, Long.MAX_VALUE, "cross-org, any latency"),
}

/** Unique identifier for a gossip member. */
data class MemberId(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is MemberId && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        fun random() = kotlin.random.Random.nextBytes(20)
        fun fromLong(id: Long) = ByteArray(20) { if (it < 8) ((id ushr ((7 - it) * 8)) and 0xFF).toByte() else 0 }
    }
}

/** A gossip member with ring assignment. */
data class GossipMember(
    val id: MemberId,
    val ring: ConcentricRing,
    val address: String,
    val port: Int,
    val capabilities: Set<String> = emptySet(),
    val lastSeen: Long = System.currentTimeMillis(),
    val heartbeatSeq: Long = 0,
    val ephemeral: Boolean = false,
)

/** Membership event. */
sealed class MembershipEvent {
    data class Joined(val member: GossipMember) : MembershipEvent()
    data class Left(val member: GossipMember) : MembershipEvent()
    data class RingMoved(val member: GossipMember, val oldRing: ConcentricRing) : MembershipEvent()
    data class Heartbeat(val member: GossipMember) : MembershipEvent()
}

/** Gossip message payload. */
sealed class GossipMessage {
    // Membership
    data class Ping(val from: MemberId, val seq: Long) : GossipMessage()
    data class Pong(val from: MemberId, val seq: Long) : GossipMessage()
    data class Join(val member: GossipMember, val seedNodes: List<MemberId>) : GossipMessage()

    // Work distribution
    data class WorkOffer(val workId: CharSequence, val priority: Int, val ring: ConcentricRing, val deadlineMs: Long? = null) : GossipMessage()
    data class WorkAccept(val workId: CharSequence, val workerId: MemberId) : GossipMessage()
    data class WorkResult(val workId: CharSequence, val workerId: MemberId, val result: ByteArray, val elapsedMs: Long) : GossipMessage()
    data class WorkSteal(val targetRing: ConcentricRing, val workerId: MemberId, val count: Int) : GossipMessage()

    // Blackboard MUD/MOO
    data class BlackboardWrite(val boardId: CharSequence, val key: CharSequence, val value: ByteArray, val ttlMs: Long? = null) : GossipMessage()
    data class BlackboardRead(val boardId: CharSequence, val key: CharSequence, val requester: MemberId) : GossipMessage()
    data class BlackboardUpdate(val boardId: CharSequence, val key: CharSequence, val delta: ByteArray, val version: Long) : GossipMessage()

    // Cascading workflow
    data class WorkflowStart(val workflowId: CharSequence, val stages: List<WorkflowStage>, val initiator: MemberId) : GossipMessage()
    data class WorkflowStageComplete(val workflowId: CharSequence, val stageId: CharSequence, val workerId: MemberId) : GossipMessage()
}

/** A stage in a cascading workflow. */
data class WorkflowStage(
    val stageId: CharSequence,
    val targetRing: ConcentricRing,
    val timeoutMs: Long,
    val retryCount: Int = 3,
    val dependsOn: List<CharSequence> = emptyList(), // prerequisite stage IDs
)
