package borg.trikeshed.hazelnut

import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.FileImpl
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.lib.*

// ── Transport SPI: SCTP / QUIC / HTX / IPFS bound to uring-facade-nio ────────

/**
 * Transport protocol identity for distributed object messaging.
 * Each maps to a uring submission pattern via [TransportBinding].
 */
enum class Transport(
    val scheme: CharSequence,
    val defaultPort: Int,
    val uringOpcode: UringOp?,
) {
    SCTP("sctp", 2904, UringOp.CONNECT),
    QUIC("quic", 443, UringOp.SENDMSG),
    HTX("htx", 8080, UringOp.SEND),
    IPFS("ipfs", 4001, UringOp.SEND),
}

/**
 * A transport binding ties a [Transport] to a [FunctionalUringFacade] backend
 * and a socket [FileImpl] handle.
 *
 * The binding owns no lifecycle — it delegates to the uring facade for all I/O.
 */
class TransportBinding(
    val transport: Transport,
    val facade: FunctionalUringFacade,
    val socket: FileImpl,
    var userDataCounter: Long = 1L,
) {
    fun nextUserData(): Long = userDataCounter++

    /** Enqueue a send submission for [payload]. Returns the userData token. */
    fun enqueueSend(payload: ByteArray): Long {
        val token = nextUserData()
        val buf = ByteBuffer.wrap(payload)
        val submission = when (transport) {
            Transport.HTX, Transport.SCTP ->
                UringOp.Submissions.send(socket.fd, buf.addr, payload.size, token)
            Transport.QUIC ->
                UringSubmission(
                    opcode = UringOp.SENDMSG,
                    fd = socket.fd,
                    addr = buf.addr,
                    len = payload.size,
                    offset = 0,
                    userData = token,
                    buffer = buf,
                )
            Transport.IPFS ->
                UringSubmission(
                    opcode = UringOp.SEND,
                    fd = socket.fd,
                    addr = buf.addr,
                    len = payload.size,
                    offset = 0,
                    userData = token,
                    buffer = buf,
                )
        }
        facade.enqueue(submission)
        return token
    }

    /** Enqueue a recv submission into [buffer]. Returns the userData token. */
    fun enqueueRecv(buffer: ByteBuffer): Long {
        val token = nextUserData()
        facade.enqueue(
            UringOp.Submissions.recv(socket.fd, buffer.addr, buffer.remaining().toInt(), token)
        )
        return token
    }
}

// ── Distributed object types (Hazelcast-patterned, absorbing reddish shapes) ─

enum class DistributedObjectType {
    STRING, LIST, HASH, SET, QUEUE, TOPIC,
}

/** Base interface for all distributed objects in hazelnut. */
interface DistributedObject {
    val id: CharSequence
    val type: DistributedObjectType
    val ttl: Long?
}

/**
 * Distributed string — absorbs reddish ReddishKey STRING type.
 * More precise: revision tracking + origin node.
 */
data class DString(
    override val id: CharSequence,
    val value: CharSequence,
    override val ttl: Long? = null,
    val revision: Long = 0,
    val originNode: CharSequence = "local",
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.STRING

    fun copyWithValue(new: CharSequence): DString =
        copy(value = new, revision = revision + 1)
}

/**
 * Distributed list — absorbs reddish ReddishList.
 * LPush/RPush semantics, distributed append.
 */
data class DList(
    override val id: CharSequence,
    val elements: List<CharSequence> = emptyList(),
    override val ttl: Long? = null,
    val revision: Long = 0,
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.LIST

    fun appendLeft(vararg items: CharSequence): DList =
        copy(elements = items.toList() + elements, revision = revision + 1)

    fun appendRight(vararg items: CharSequence): DList =
        copy(elements = elements + items.toList(), revision = revision + 1)

    fun popLeft(): Pair<CharSequence?, DList> {
        val (head, tail) = elements.splitAt(1)
        return (head.firstOrNull() ?: null) to copy(elements = tail, revision = revision + 1)
    }

    fun popRight(): Pair<CharSequence?, DList> {
        if (elements.isEmpty()) return null to this
        val init = elements.dropLast(1)
        val last = elements.last()
        return last to copy(elements = init, revision = revision + 1)
    }
}

/**
 * Distributed hash — absorbs reddish ReddishHash.
 * Field-level operations, partial updates.
 */
data class DHash(
    override val id: CharSequence,
    val fields: Map<CharSequence, CharSequence> = emptyMap(),
    override val ttl: Long? = null,
    val revision: Long = 0,
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.HASH

    fun set(field: CharSequence, value: CharSequence): DHash =
        copy(fields = fields + (field to value), revision = revision + 1)

    fun get(field: CharSequence): CharSequence? = fields[field]

    fun remove(vararg fieldsToRemove: CharSequence): DHash =
        copy(
            fields = fields.filterKeys { k -> fieldsToRemove.none { k == it } },
            revision = revision + 1,
        )

    val size: Int get() = fields.size
}

/**
 * Distributed queue — backed by DList with FIFO semantics.
 * Precise capacity bounds and ordering guarantees.
 */
data class DQueue(
    override val id: CharSequence,
    val items: List<CharSequence> = emptyList(),
    override val ttl: Long? = null,
    val maxCapacity: Int = Int.MAX_VALUE,
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.QUEUE

    fun enqueue(item: CharSequence): DQueue {
        require(items.size < maxCapacity) { "Queue capacity exceeded: $maxCapacity" }
        return copy(items = items + item)
    }

    fun dequeue(): Pair<CharSequence?, DQueue> =
        if (items.isEmpty()) null to this else items[0] to copy(items = items.drop(1))

    val size: Int get() = items.size
}

/**
 * Distributed topic — pub/sub with subscriber handles.
 * Patterned around Hazelcast ITopic.
 */
data class DTopic(
    override val id: CharSequence,
    override val ttl: Long? = null,
    val subscriberIds: List<CharSequence> = emptyList(),
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.TOPIC

    fun subscribe(subscriberId: CharSequence): DTopic =
        copy(subscriberIds = subscriberIds + subscriberId)

    fun unsubscribe(subscriberId: CharSequence): DTopic =
        copy(subscriberIds = subscriberIds.filter { it != subscriberId })
}

// ── Hazelnut client extended for distributed operations ──────────────────────

data class HazelnutClusterNode(
    val nodeId: CharSequence,
    val transport: Transport,
    val address: CharSequence,
    val port: Int,
)

data class HazelnutClusterConfig(
    val nodes: List<HazelnutClusterNode> = emptyList(),
    val binding: TransportBinding? = null,
    val localNodeId: CharSequence = "node-1",
)

fun HazelnutClusterNode.fullName(): CharSequence =
    "${transport.scheme}://$address:$port"

// ── Couch grounding: every distributed object → RowVec ──────────────────────

private val distributedObjectRowKeys = listOf(
    "objectId", "objectType", "revision", "ttl", "originNode",
)

/** Project a [DistributedObject] to a DocRowVec for couch persistence. */
fun DistributedObject.toCouchRowVec(): borg.trikeshed.miniduck.DocRowVec {
    val keys = distributedObjectRowKeys + when (this) {
        is DString -> listOf("value", "valueLength")
        is DList -> listOf("elementCount")
        is DHash -> listOf("fieldCount")
        is DQueue -> listOf("itemCount", "maxCapacity")
        is DTopic -> listOf("subscriberCount")
    }
    val cells = listOf(
        id.toString(),
        type.name,
        0, // revision field index
        ttl?.let { it.toString() } ?: "-1",
        if (this is DString) originNode else "local",
    ) + when (this) {
        is DString -> listOf(value, value.length)
        is DList -> listOf(elements.size)
        is DHash -> listOf(fields.size)
        is DQueue -> listOf(items.size, maxCapacity)
        is DTopic -> listOf(subscriberIds.size)
    }
    return borg.trikeshed.miniduck.DocRowVec(keys = keys, cells = cells)
}

// ── Hazelnut service with transport + distributed object support ─────────────

class HazelnutTransportClient(
    private val binding: TransportBinding,
) : HazelnutClient {
    override fun upsert(envelope: HazelnutEnvelope): HazelnutWriteReceipt {
        val payload = envelope.toCouchRowVec().cells.joinToString(",")
            .encodeToByteArray()
        binding.enqueueSend(payload)
        binding.facade.submit()
        return HazelnutWriteReceipt(
            forum = envelope.forum,
            threadId = envelope.threadId,
            commentId = envelope.commentId,
            accepted = true,
        )
    }

    override fun query(query: HazelnutQuery): Series<HazelnutEnvelope> =
        Join.emptySeriesOf()
}

/**
 * Unified hazelnut service: forum threads + distributed objects on one transport.
 * Absorbs reddish shapes — Redis STRING/LIST/HASH map to DString/DList/DHash.
 */
class HazelnutUnifiedService(
    private val cluster: HazelnutClusterConfig,
    private val client: HazelnutClient,
) {
    val binding: TransportBinding? get() = cluster.binding

    // -- Forum operations (original hazelnut) --
    fun publish(envelope: HazelnutEnvelope): HazelnutWriteReceipt =
        client.upsert(envelope)

    fun sync(query: HazelnutQuery): Series<HazelnutEnvelope> =
        client.query(query)

    // -- Distributed object operations (absorbed reddish) --

    fun setString(key: CharSequence, value: CharSequence, ttl: Long? = null): DString =
        DString(id = key, value = value, ttl = ttl, revision = 0, originNode = cluster.localNodeId)

    fun getString(key: CharSequence): DString? = null // client-backed

    fun lpush(key: CharSequence, vararg elements: CharSequence): DList =
        DList(id = key).appendRight(*elements)

    fun rpush(key: CharSequence, vararg elements: CharSequence): DList =
        DList(id = key).appendRight(*elements)

    fun lpop(list: DList): Pair<CharSequence?, DList> = list.popLeft()

    fun rpop(list: DList): Pair<CharSequence?, DList> = list.popRight()

    fun hset(key: CharSequence, field: CharSequence, value: CharSequence): DHash =
        DHash(id = key).set(field, value)

    fun hget(hash: DHash, field: CharSequence): CharSequence? = hash.get(field)

    fun hgetall(hash: DHash): Map<CharSequence, CharSequence> = hash.fields

    fun queue(key: CharSequence, maxCapacity: Int = Int.MAX_VALUE): DQueue =
        DQueue(id = key, maxCapacity = maxCapacity)

    fun topic(key: CharSequence, subscribeId: CharSequence? = null): DTopic =
        if (subscribeId != null)
            DTopic(id = key).subscribe(subscribeId)
        else
            DTopic(id = key)

    // -- Couch grounding --

    fun project(obj: DistributedObject): borg.trikeshed.miniduck.DocRowVec =
        obj.toCouchRowVec()

    fun project(envelope: HazelnutEnvelope): borg.trikeshed.miniduck.DocRowVec =
        envelope.toRowVec()
}
