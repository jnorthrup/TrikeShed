package borg.trikeshed.hazelnut

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.userspace.FileImpl
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer

// ── Hazelnut: forum/thread layer (original) ──────────────────────────────────

private val hazelnutRowKeys = listOf("forum", "threadId", "commentId", "title", "body", "author", "attachmentCount")
private val hazelnutExtraKeys = listOf("labelCount")

data class HazelnutEnvelope(
    val forum: CharSequence,
    val threadId: CharSequence,
    val commentId: CharSequence,
    val title: CharSequence? = null,
    val body: CharSequence,
    val author: CharSequence? = null,
    val labels: Map<CharSequence, CharSequence> = emptyMap(),
    val attachments: Series<RowVec> = emptySeries(),
)

data class HazelnutQuery(
    val forum: CharSequence,
    val threadId: CharSequence? = null,
    val cursor: CharSequence? = null,
    val limit: Int = 25,
)

data class HazelnutWriteReceipt(
    val forum: CharSequence,
    val threadId: CharSequence,
    val commentId: CharSequence,
    val accepted: Boolean,
    val revision: CharSequence? = null,
)

interface HazelnutClient {
    fun upsert(envelope: HazelnutEnvelope): HazelnutWriteReceipt
    fun query(query: HazelnutQuery): Series<HazelnutEnvelope>
}

fun HazelnutEnvelope.toRowVec(): DocRowVec =
    DocRowVec(
        keys = hazelnutRowKeys + hazelnutExtraKeys,
        cells = listOf(
            forum,
            threadId,
            commentId,
            title,
            body,
            author,
            attachments.size,
            labels.size,
        ),
        child = attachments,
    )

class HazelnutService(
    private val client: HazelnutClient,
) {
    fun publish(envelope: HazelnutEnvelope): HazelnutWriteReceipt = client.upsert(envelope)

    fun sync(query: HazelnutQuery): Series<HazelnutEnvelope> = client.query(query)

    fun project(envelope: HazelnutEnvelope): DocRowVec = envelope.toRowVec()
}

// ── Transport SPI: SCTP / QUIC / HTX / IPFS bound to uring-facade-nio ────────

enum class Transport(
    val scheme: CharSequence,
    val defaultPort: Int,
    val uringOpcode: UringOp,
    val recvOpcode: UringOp,
) {
    SCTP("sctp", 2904, UringOp.SENDMSG, UringOp.RECVMSG),
    QUIC("quic", 443, UringOp.SENDMSG, UringOp.RECVMSG),
    HTX("htx", 8080, UringOp.SEND, UringOp.RECV),
    IPFS("ipfs", 4001, UringOp.SEND, UringOp.RECV),
}

class TransportBinding(
    val transport: Transport,
    val facade: FunctionalUringFacade,
    val socket: FileImpl,
    var userDataCounter: Long = 1L,
    val streamId: Int = 0,
) {
    fun nextUserData(): Long = userDataCounter++

    fun enqueueSend(payload: ByteArray): Long {
        val token = nextUserData()
        val buf = ByteBuffer.wrap(payload)
        facade.enqueue(
            UringSubmission(
                opcode = transport.uringOpcode,
                fd = socket.id,
                addr = 0L,
                len = payload.size,
                offset = streamId.toLong(),
                userData = token,
                buffer = buf,
            ),
        )
        return token
    }

    fun enqueueRecv(buffer: ByteBuffer): Long {
        val token = nextUserData()
        facade.enqueue(
            UringSubmission(
                opcode = transport.recvOpcode,
                fd = socket.id,
                addr = 0L,
                len = buffer.remaining(),
                offset = streamId.toLong(),
                userData = token,
                buffer = buffer,
            ),
        )
        return token
    }
}

// ── Distributed object taxonomy: 9 types matching Redis + distributed semantics ──

enum class DistributedObjectType {
    STRING, LIST, HASH, SET, SORTED_SET, BITMAP, GEO, STREAM, HYPERLOGLOG
}

interface DistributedObject {
    val id: CharSequence
    val type: DistributedObjectType
    val ttl: Long?
    val revision: Long
    val originNode: CharSequence
}

// ── DString ↔ Reddish STRING / ReddishKey ──

data class DString(
    override val id: CharSequence,
    val value: CharSequence,
    override val ttl: Long? = null,
    override val revision: Long = 0,
    override val originNode: CharSequence = "local",
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.STRING
    fun copyWithValue(new: CharSequence): DString = copy(value = new, revision = revision + 1)
}

// ── DList ↔ Reddish LIST / ReddishList ──

data class DList(
    override val id: CharSequence,
    val elements: List<CharSequence> = emptyList(),
    override val ttl: Long? = null,
    override val revision: Long = 0,
    override val originNode: CharSequence = "local",
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.LIST
    fun appendLeft(vararg items: CharSequence): DList =
        copy(elements = items.toList() + elements, revision = revision + 1)
    fun appendRight(vararg items: CharSequence): DList =
        copy(elements = elements + items.toList(), revision = revision + 1)
    fun popLeft(): Pair<CharSequence?, DList> =
        if (elements.isEmpty()) null to this else elements[0] to copy(elements = elements.drop(1), revision = revision + 1)
    fun popRight(): Pair<CharSequence?, DList> =
        if (elements.isEmpty()) null to this
        else elements.last() to copy(elements = elements.dropLast(1), revision = revision + 1)
}

// ── DHash ↔ Reddish HASH / ReddishHash ──

data class DHash(
    override val id: CharSequence,
    val fields: Map<CharSequence, CharSequence> = emptyMap(),
    override val ttl: Long? = null,
    override val revision: Long = 0,
    override val originNode: CharSequence = "local",
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.HASH
    fun set(field: CharSequence, value: CharSequence): DHash =
        copy(fields = fields + (field to value), revision = revision + 1)
    fun get(field: CharSequence): CharSequence? = fields[field]
    fun remove(vararg fieldsToRemove: CharSequence): DHash =
        copy(fields = fields.filterKeys { k -> fieldsToRemove.none { k == it } }, revision = revision + 1)
    val size: Int get() = fields.size
}

// ── DSet ↔ Reddish SET / ReddishSet ──

data class DSet(
    override val id: CharSequence,
    val members: Set<CharSequence> = emptySet(),
    override val ttl: Long? = null,
    override val revision: Long = 0,
    override val originNode: CharSequence = "local",
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.SET
    fun add(vararg m: CharSequence): DSet =
        copy(members = members + m.toSet(), revision = revision + 1)
    fun remove(vararg m: CharSequence): DSet =
        copy(members = members.filter { v -> m.none { v == it } }.toSet(), revision = revision + 1)
    val size: Int get() = members.size
}

// ── DSortedSet ↔ Reddish SORTED_SET / ReddishSortedSet ──

data class SortedSetEntry(
    val member: CharSequence,
    val score: Double,
)

data class DSortedSet(
    override val id: CharSequence,
    val entries: List<SortedSetEntry> = emptyList(),
    override val ttl: Long? = null,
    override val revision: Long = 0,
    override val originNode: CharSequence = "local",
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.SORTED_SET

    fun add(member: CharSequence, score: Double): DSortedSet {
        val idx = entries.indexOfFirst { it.member == member }
        val next = if (idx >= 0) {
            entries.toMutableList().also { it[idx] = SortedSetEntry(member, score) }
        } else {
            entries + SortedSetEntry(member, score)
        }
        return copy(entries = next.sortedBy { it.score }, revision = revision + 1)
    }

    fun remove(vararg members: CharSequence): DSortedSet =
        copy(entries = entries.filter { e -> members.none { e.member == it } }, revision = revision + 1)

    fun score(member: CharSequence): Double? = entries.find { it.member == member }?.score

    fun rangeByScore(min: Double, max: Double): List<SortedSetEntry> =
        entries.filter { it.score in min..max }
}

// ── DBitmap ↔ Reddish BITMAP / ReddishBitmap ──

data class DBitmap(
    override val id: CharSequence,
    val bytes: ByteArray = byteArrayOf(),
    override val ttl: Long? = null,
    override val revision: Long = 0,
    override val originNode: CharSequence = "local",
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.BITMAP

    fun bitAt(offset: Int): Boolean =
        if (offset < 0 || bytes.isEmpty()) false
        else (bytes[offset / 8].toInt() and (1 shl (offset % 8))) != 0

    fun setBit(offset: Int, value: Boolean): DBitmap {
        val copy = bytes.copyOf(maxOf(bytes.size, offset / 8 + 1))
        val b = offset / 8
        val bit = offset % 8
        if (value) copy[b] = (copy[b].toInt() or (1 shl bit)).toByte()
        else copy[b] = (copy[b].toInt() and (0xFF xor (1 shl bit))).toByte()
        return copy(bytes = copy, revision = revision + 1)
    }

    fun populateCount(): Int = (0 until bytes.size * 8).count { bitAt(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DBitmap) return false
        return id == other.id && bytes.contentEquals(other.bytes) && ttl == other.ttl &&
            revision == other.revision && originNode == other.originNode
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (ttl?.hashCode() ?: 0)
        result = 31 * result + revision.hashCode()
        result = 31 * result + originNode.hashCode()
        return result
    }
}

// ── DGeo ↔ Reddish GEO / ReddishGeo ──

data class GeoPoint(
    val longitude: Double,
    val latitude: Double,
    val member: CharSequence,
)

data class DGeo(
    override val id: CharSequence,
    val points: List<GeoPoint> = emptyList(),
    override val ttl: Long? = null,
    override val revision: Long = 0,
    override val originNode: CharSequence = "local",
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.GEO

    fun add(longitude: Double, latitude: Double, member: CharSequence): DGeo {
        val existing = points.indexOfFirst { it.member == member }
        val p = GeoPoint(longitude, latitude, member)
        val next = if (existing >= 0) points.toMutableList().also { it[existing] = p } else points + p
        return copy(points = next, revision = revision + 1)
    }

    fun find(member: CharSequence): GeoPoint? = points.find { it.member == member }

    fun distance(a: CharSequence, b: CharSequence): Double? {
        val pa = find(a) ?: return null
        val pb = find(b) ?: return null
        val dLon = (pb.longitude - pa.longitude) * 0.017453292519943295
        val dLat = (pb.latitude - pa.latitude) * 0.017453292519943295
        val sinLat = kotlin.math.sin(dLat / 2)
        val sinLon = kotlin.math.sin(dLon / 2)
        val av = sinLat * sinLat +
            kotlin.math.cos(pa.latitude * 0.017453292519943295) * kotlin.math.cos(pb.latitude * 0.017453292519943295) *
                sinLon * sinLon
        return 2.0 * 6371000.0 * kotlin.math.atan2(kotlin.math.sqrt(av), kotlin.math.sqrt(1.0 - av))
    }
}

// ── DStream ↔ Reddish STREAM / ReddishStream ──

data class StreamEntry(
    val id: CharSequence,
    val fields: Map<CharSequence, CharSequence> = emptyMap(),
)

data class DStream(
    override val id: CharSequence,
    val entries: List<StreamEntry> = emptyList(),
    val maxLen: Long? = null,
    override val ttl: Long? = null,
    override val revision: Long = 0,
    override val originNode: CharSequence = "local",
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.STREAM

    fun append(entryId: CharSequence, fields: Map<CharSequence, CharSequence>): DStream {
        val trimmed = if (maxLen != null && entries.size >= maxLen) entries.drop(entries.size - maxLen.toInt() + 1) else entries
        return copy(entries = trimmed + StreamEntry(entryId, fields), revision = revision + 1)
    }

    fun range(start: CharSequence, end: CharSequence): List<StreamEntry> =
        entries.filter { e -> e.id.toString() >= start.toString() && e.id.toString() <= end.toString() }

    val len: Int get() = entries.size
}

// ── DHyperLogLog ↔ Reddish HYPERLOGLOG / ReddishHyperLogLog ──

data class DHyperLogLog(
    override val id: CharSequence,
    val bytes: ByteArray = ByteArray(16384),
    val cardinality: Long = 0L,
    override val ttl: Long? = null,
    override val revision: Long = 0,
    override val originNode: CharSequence = "local",
) : DistributedObject {
    override val type: DistributedObjectType get() = DistributedObjectType.HYPERLOGLOG

    fun add(vararg elements: CharSequence): DHyperLogLog {
        val updated = cardinality + elements.size.toLong()
        return copy(cardinality = updated, revision = revision + 1)
    }

    fun merge(other: DHyperLogLog): DHyperLogLog =
        copy(cardinality = maxOf(cardinality, other.cardinality), revision = revision + 1)

    fun count(): Long = cardinality

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DHyperLogLog) return false
        return id == other.id && cardinality == other.cardinality && ttl == other.ttl &&
            revision == other.revision && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + cardinality.hashCode()
        result = 31 * result + (ttl?.hashCode() ?: 0)
        result = 31 * result + revision.hashCode()
        return result
    }
}

// ── Cluster config ───────────────────────────────────────────────────────────

data class HazelnutClusterNode(
    val nodeId: CharSequence,
    val transport: Transport,
    val address: CharSequence,
    val port: Int,
)

fun HazelnutClusterNode.fullName(): CharSequence =
    "${transport.scheme}://$address:$port"

data class HazelnutClusterConfig(
    val nodes: List<HazelnutClusterNode> = emptyList(),
    val binding: TransportBinding? = null,
    val localNodeId: CharSequence = "node-1",
)

// ── Couch grounding: every distributed object → DocRowVec ───────────────────

fun DistributedObject.toCouchRowVec(): DocRowVec {
    val base = listOf("objectId", "objectType", "revision", "ttl", "originNode")
    val (keys, cells) = when (this) {
        is DString -> listOf("value", "valueLength") to listOf(value, value.length)
        is DList -> listOf("elementCount") to listOf(elements.size)
        is DHash -> listOf("fieldCount") to listOf(fields.size)
        is DSet -> listOf("memberCount") to listOf(members.size)
        is DSortedSet -> listOf("entryCount") to listOf(entries.size)
        is DBitmap -> listOf("byteSize", "populateCount") to listOf(bytes.size, populateCount())
        is DGeo -> listOf("pointCount") to listOf(points.size)
        is DStream -> listOf("entryCount", "maxLen") to listOf(entries.size, maxLen?.toInt() ?: -1)
        is DHyperLogLog -> listOf("cardinality", "byteSize") to listOf(cardinality, bytes.size)
        else -> error("Unknown distributed object type: ${this::class}")
    }
    return DocRowVec(
        keys = base + keys,
        cells = listOf(id, type.name, revision, ttl?.let { it.toString() } ?: "-1", originNode) + cells,
    )
}

// ── Transport-backed client ──────────────────────────────────────────────────

class HazelnutTransportClient(
    private val binding: TransportBinding,
) : HazelnutClient {
    override fun upsert(envelope: HazelnutEnvelope): HazelnutWriteReceipt {
        val row = envelope.toRowVec()
        val payload = buildString {
            for (i in 0 until row.size) {
                if (i > 0) append(",")
                val v = row[i]
                append(v?.toString() ?: "")
            }
        }.encodeToByteArray()
        binding.enqueueSend(payload)
        binding.facade.submit()
        return HazelnutWriteReceipt(
            forum = envelope.forum,
            threadId = envelope.threadId,
            commentId = envelope.commentId,
            accepted = true,
        )
    }

    override fun query(query: HazelnutQuery): Series<HazelnutEnvelope> = Join.emptySeriesOf()
}

// ── Unified service ──────────────────────────────────────────────────────────

class HazelnutUnifiedService(
    private val cluster: HazelnutClusterConfig,
    private val client: HazelnutClient,
) {
    val binding: TransportBinding? get() = cluster.binding

    // Forum ops
    fun publish(envelope: HazelnutEnvelope): HazelnutWriteReceipt = client.upsert(envelope)
    fun sync(query: HazelnutQuery): Series<HazelnutEnvelope> = client.query(query)

    // Distributed objects — 9 types matching Redis taxonomy
    fun setString(k: CharSequence, v: CharSequence, t: Long? = null): DString =
        DString(k, v, t, originNode = cluster.localNodeId)
    fun lpush(k: CharSequence, vararg e: CharSequence): DList = DList(k).appendLeft(*e)
    fun rpush(k: CharSequence, vararg e: CharSequence): DList = DList(k).appendRight(*e)
    fun lpop(l: DList): Pair<CharSequence?, DList> = l.popLeft()
    fun rpop(l: DList): Pair<CharSequence?, DList> = l.popRight()
    fun hset(k: CharSequence, f: CharSequence, v: CharSequence): DHash = DHash(k).set(f, v)
    fun hget(h: DHash, f: CharSequence): CharSequence? = h.get(f)
    fun sadd(k: CharSequence, vararg m: CharSequence): DSet = DSet(k).add(*m)
    fun srem(s: DSet, vararg m: CharSequence): DSet = s.remove(*m)
    fun zadd(k: CharSequence, score: Double, member: CharSequence): DSortedSet = DSortedSet(k).add(member, score)
    fun zrem(s: DSortedSet, vararg m: CharSequence): DSortedSet = s.remove(*m)
    fun zrange(s: DSortedSet, min: Double, max: Double): List<SortedSetEntry> = s.rangeByScore(min, max)
    fun geoadd(k: CharSequence, lon: Double, lat: Double, m: CharSequence): DGeo = DGeo(k).add(lon, lat, m)
    fun geodist(g: DGeo, a: CharSequence, b: CharSequence): Double? = g.distance(a, b)
    fun xadd(k: CharSequence, entryId: CharSequence, fields: Map<CharSequence, CharSequence>): DStream =
        DStream(k).append(entryId, fields)
    fun pfadd(k: CharSequence, vararg e: CharSequence): DHyperLogLog = DHyperLogLog(k).add(*e)
    fun pfmerge(a: DHyperLogLog, b: DHyperLogLog): DHyperLogLog = a.merge(b)

    fun project(obj: DistributedObject): DocRowVec = obj.toCouchRowVec()
    fun project(envelope: HazelnutEnvelope): DocRowVec = envelope.toRowVec()
}
