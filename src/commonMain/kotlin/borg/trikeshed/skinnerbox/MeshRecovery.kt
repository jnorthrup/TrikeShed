package borg.trikeshed.skinnerbox

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.toAny
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import kotlinx.coroutines.yield

/** An explicit retained snapshot, not a statement that no later distributed mutation exists. */
class MeshSnapshot private constructor(
    val namespace: String,
    val epoch: ContentId,
    private val records: Array<ContentId>,
    val cutoffSequence: Long,
    private val encoded: ByteArray,
) {
    val id: ContentId = ContentId.of(encoded)
    val packets: Series<ContentId> get() = records.size j { records[it] }
    fun bytes(): ByteArray = encoded.copyOf()

    companion object {
        const val FORMAT = "skinnerbox.snapshot/v1"
        const val MAX_BYTES = 512 * 1024
        const val MAX_PACKETS = 4096
        const val MAX_SEQUENCE = 65_536L

        @Suppress("UNCHECKED_CAST")
        fun decode(bytes: ByteArray, cell: MeshCell): MeshSnapshot {
            require(bytes.size in 1..MAX_BYTES) { "Mesh snapshot byte bound" }
            val owned = bytes.copyOf()
            val reader = Cbor.Reader(owned, 0, maxDepth = 4, maxItems = MAX_PACKETS + 32)
            val item = reader.readItem()
            require(reader.pos == owned.size && item is Item.Map) { "Mesh snapshot map" }
            val fields = item.toAny() as Map<String, Any?>
            require(CanonicalCbor.encodeMap(fields).contentEquals(owned)) { "Mesh snapshot canonical encoding" }
            require(fields.keys == setOf("format", "namespace", "epoch", "cell", "packets", "cutoffSequence") &&
                fields["format"] == FORMAT && fields["namespace"] == cell.namespace && fields["epoch"] == cell.epoch.value &&
                (fields["cell"] as? ByteArray)?.contentEquals(cell.bytes()) == true) { "Mesh snapshot pinned cell differs" }
            val values = fields["packets"] as? List<*> ?: error("Mesh snapshot packet list")
            require(values.size <= MAX_PACKETS) { "Mesh snapshot packet bound" }
            val packets = Array(values.size) { ContentId(values[it] as? String ?: error("Mesh snapshot packet identity")) }
            require(packets.toSet().size == packets.size) { "Duplicate mesh snapshot packet" }
            val cutoff = fields["cutoffSequence"] as? Long ?: error("Mesh snapshot cutoff integer")
            require(if (packets.isEmpty()) cutoff == 0L else cutoff in 2..MAX_SEQUENCE) { "Mesh snapshot cutoff bound" }
            return MeshSnapshot(cell.namespace, cell.epoch, packets, cutoff, owned)
        }

        internal fun create(cell: MeshCell, packets: Series<ContentId>, cutoff: Long): MeshSnapshot = decode(
            CanonicalCbor.encodeMap(mapOf("format" to FORMAT, "namespace" to cell.namespace, "epoch" to cell.epoch.value,
                "cell" to cell.bytes(), "packets" to packets.view.map { it.value }, "cutoffSequence" to cutoff)), cell)
    }
}

data class MeshRecoveryResult(val manifest: ContentId, val events: Int, val cutoffSequence: Long)

/** Offline composition over the existing persistence channels. Sources and destination remain caller-owned. */
object MeshRecovery {
    private data class Row(val kind: String, val collection: String, val key: String)
    private data class Head(val revision: ContentId, val deleted: Boolean)
    private class Event(val sequence: Long, val row: Row, val previous: ContentId?,
        val revision: ContentId, val request: String, val actor: String, val deleted: Boolean,
        val retainedBytes: Long, val bytes: ByteArray)

    suspend fun export(cell: MeshCell, source: MeshPersistence, reader: String): MeshSnapshot {
        validateSource(cell, source, reader)
        val packets = mutableListOf<Pair<Long, ContentId>>()
        var since = 0L
        var pages = 0
        var foundCell = false
        val maxCursor = MeshSnapshot.MAX_SEQUENCE + source.limits.maxRecords + 2
        do {
            require(++pages <= maxCursor / 64 + 2) { "Mesh snapshot enumeration bound" }
            val page = call(source, reader, cell.namespace, "replica.list", mapOf("since" to since, "limit" to 64L))
            require(page.keys == setOf("replicas", "since", "more")) { "Mesh replica inventory fields" }
            val entries = page["replicas"] as? List<*> ?: error("Mesh replica inventory")
            require(entries.size <= 64)
            for (entry in entries) {
                val value = entry as? Map<*, *> ?: error("Mesh replica entry")
                require(value.keys == setOf("contentId"))
                val cid = ContentId(value["contentId"] as? String ?: error("Mesh replica identity"))
                val bytes = packet(source, reader, cell.namespace, cid)
                if (bytes.contentEquals(cell.bytes())) { foundCell = true; continue }
                require(packets.size < MeshSnapshot.MAX_PACKETS) { "Mesh snapshot event bound" }
                val event = event(cell, cid, bytes)
                packets.add(event.sequence to cid)
                yield()
            }
            val next = page["since"] as? Long ?: error("Mesh replica cursor")
            val more = page["more"] as? Boolean ?: error("Mesh replica continuation")
            require(next >= since && next <= maxCursor && (!more || next > since)) { "Mesh replica cursor bound" }
            since = next
        } while (more)
        require(foundCell) { "Retained cell decision is absent" }
        packets.sortBy { it.first }
        val snapshot = MeshSnapshot.create(cell, packets.size j { packets[it].second }, packets.lastOrNull()?.first ?: 0L)
        inspect(snapshot, cell, source, reader)
        return snapshot
    }

    suspend fun restore(snapshot: MeshSnapshot, cell: MeshCell, source: MeshPersistence, reader: String,
        destination: MeshPersistence, ownerId: String): MeshRecoveryResult {
        validateSource(cell, source, reader)
        require(destination !== source && destination.durability == StorageDurability.DURABLE) { "Recovery requires a separate durable destination" }
        require(ownerId == cell.owner.id) { "Recovery cannot promote a replacement owner" }
        destination.authorize(ownerId, cell.namespace)
        // Validate all packets and per-row predecessor chains before the first destination effect.
        inspect(snapshot, cell, source, reader, destination)
        val epoch = mapOf("epoch" to cell.epoch.value)
        bound(call(destination, ownerId, cell.namespace, "recovery.begin", epoch + mapOf(
            "manifest" to snapshot.id.value, "events" to snapshot.packets.size.toLong(), "cutoff" to snapshot.cutoffSequence)), cell)
        for (cid in snapshot.packets.view) {
            // Fetch immutable content a second time; payloads never accumulate in the coordinator.
            val event = event(cell, cid, packet(source, reader, cell.namespace, cid))
            var advanced = 0L
            do {
                val progress = call(destination, ownerId, cell.namespace, "recovery.advance", epoch + ("sequence" to event.sequence))
                require(progress.keys == setOf("sequence", "ready", "durable") && progress["durable"] == true)
                val sequence = progress["sequence"] as? Long ?: error("Recovery advance sequence")
                val ready = progress["ready"] as? Boolean ?: error("Recovery advance completion")
                require(sequence in advanced..event.sequence - 1 && (ready || sequence > advanced)) { "Recovery advance did not progress" }
                require(!ready || sequence == event.sequence - 1) { "Recovery advance is incomplete" }
                advanced = sequence
                yield()
            } while (!ready)
            val reply = call(destination, ownerId, cell.namespace, "recovery.event", epoch + ("value" to event.bytes))
            require(reply["durable"] == true && reply["revision"] == event.revision.value && reply["sequence"] == event.sequence &&
                (reply["event"] as? ByteArray)?.contentEquals(event.bytes) == true) { "Recovery did not retain the original event" }
            yield()
        }
        bound(call(destination, ownerId, cell.namespace, "recovery.end", epoch + ("manifest" to snapshot.id.value)), cell)
        return MeshRecoveryResult(snapshot.id, snapshot.packets.size, snapshot.cutoffSequence)
    }

    private fun validateSource(cell: MeshCell, persistence: MeshPersistence, reader: String) {
        require(persistence.durability == StorageDurability.DURABLE && cell.member(reader) != null) { "Snapshot source must be a durable configured custodian" }
        persistence.authorize(reader, cell.namespace)
    }

    private suspend fun inspect(snapshot: MeshSnapshot, cell: MeshCell, source: MeshPersistence, reader: String,
        destination: MeshPersistence? = null) {
        MeshSnapshot.decode(snapshot.bytes(), cell)
        require(destination == null || snapshot.packets.size + 2 <= destination.limits.maxRecords) { "Snapshot record limit" }
        val heads = mutableMapOf<Row, Head>()
        val requests = mutableSetOf<String>()
        var previousSequence = 0L
        val marker = mapOf("format" to MeshPersistence.RECOVERY_FORMAT, "tenant" to cell.namespace, "epoch" to cell.epoch.value)
        val gaps = if (snapshot.packets.size == 0) 0L else snapshot.cutoffSequence - snapshot.packets.size - 1
        require(gaps >= 0) { "Snapshot event count exceeds its cutoff" }
        val gapBytes = CanonicalCbor.encodeMap(marker + ("sequence" to MeshSnapshot.MAX_SEQUENCE)).size
        var retained = gaps * gapBytes +
            CanonicalCbor.encodeMap(marker + mapOf("manifest" to snapshot.id.value, "events" to snapshot.packets.size.toLong(),
                "cutoff" to snapshot.cutoffSequence)).size + CanonicalCbor.encodeMap(marker + ("manifest" to snapshot.id.value)).size
        require(destination == null || retained <= destination.limits.retainedBytes) { "Snapshot recovery metadata exceeds retention bounds" }
        for (cid in snapshot.packets.view) {
            val event = event(cell, cid, packet(source, reader, cell.namespace, cid))
            require(event.sequence > previousSequence) { "Snapshot events must have distinct increasing original sequences" }
            require(requests.add(event.request)) { "Snapshot contains a repeated request identity" }
            val previous = heads[event.row]
            require(previous?.revision == event.previous && (!event.deleted || previous?.deleted == false)) { "Snapshot predecessor is absent or differs" }
            heads[event.row] = Head(event.revision, event.deleted)
            previousSequence = event.sequence
            retained += event.retainedBytes
            destination?.let {
                it.authorize(event.actor, cell.namespace)
                require(heads.size <= it.limits.maxRows &&
                    retained <= it.limits.retainedBytes) { "Snapshot exceeds destination retention bounds" }
            }
            yield()
        }
        require(previousSequence == snapshot.cutoffSequence) { "Snapshot cutoff differs from its final event" }
    }

    private fun event(cell: MeshCell, cid: ContentId, bytes: ByteArray): Event {
        require(ContentId.of(bytes) == cid)
        val packet = MeshPersistenceCodec.decode(bytes, MeshPersistenceCodec.PACKET_BYTES)
        require(packet.keys == setOf("format", "epoch", "namespace", "actor", "task", "event") &&
            packet["format"] == "skinnerbox.retention/v1" && packet["epoch"] == cell.epoch.value &&
            packet["namespace"] == cell.namespace) { "Snapshot retention packet binding" }
        val actor = packet["actor"] as? String ?: error("Snapshot event author")
        MeshRouting.requireId(actor)
        val eventBytes = packet["event"] as? ByteArray ?: error("Snapshot event bytes")
        val fields = MeshPersistenceCodec.decode(eventBytes, MeshPersistenceCodec.PACKET_BYTES)
        require(fields.keys == setOf("format", "tenant", "peer", "requestId", "requestDigest", "kind", "collection", "key",
            "previous", "sequence", "deleted", "contentId", "value") && fields["format"] == MeshPersistence.EVENT_FORMAT &&
            fields["tenant"] == cell.namespace && fields["peer"] == actor) { "Snapshot mutation event binding" }
        fun token(name: String) = (fields[name] as? String ?: error("Snapshot event $name")).also(::meshPersistenceToken)
        val kind = fields["kind"] as? String ?: error("Snapshot event kind")
        require(kind == "row" || kind == "object")
        val request = token("requestId")
        val row = Row(kind, token("collection"), token("key"))
        val previous = fields["previous"]?.let { ContentId(it as? String ?: error("Snapshot predecessor")) }
        val sequence = fields["sequence"] as? Long ?: error("Snapshot event sequence")
        require(sequence in 2..MeshSnapshot.MAX_SEQUENCE) { "Snapshot sequence bound" }
        val deleted = fields["deleted"] as? Boolean ?: error("Snapshot event deletion")
        val value = fields["value"] as? ByteArray ?: error("Snapshot event value")
        require(value.size <= if (kind == "row") MeshPersistenceCodec.ROW_BYTES else MeshPersistenceCodec.OBJECT_BYTES)
        val content = fields["contentId"]?.let { ContentId(it as? String ?: error("Snapshot value identity")) }
        require(if (deleted) content == null && value.isEmpty() else content == ContentId.of(value)) { "Snapshot value differs from its identity" }
        if (kind == "row" && !deleted) require(TwinJson.parse(value, MeshPersistenceCodec.ROW_BYTES) is Map<*, *>)
        val body = linkedMapOf<String, Any?>("requestId" to request, "collection" to row.collection, "key" to row.key,
            "expectedRevision" to previous?.value)
        if (!deleted) body["value"] = value
        val verb = "$kind.${if (deleted) "delete" else "put"}"
        val digest = ContentId.of(CanonicalCbor.encodeMap(mapOf("tenant" to cell.namespace, "verb" to verb,
            "payload" to CanonicalCbor.encodeMap(body))))
        require(fields["requestDigest"] == digest.value && packet["task"] == digest.value) { "Snapshot mutation digest differs" }
        return Event(sequence, row, previous, ContentId.of(eventBytes), request, actor, deleted,
            eventBytes.size.toLong() + value.size, eventBytes)
    }

    private suspend fun packet(source: MeshPersistence, reader: String, namespace: String, cid: ContentId): ByteArray {
        val reply = call(source, reader, namespace, "replica.read", mapOf("contentId" to cid.value))
        require(reply.keys == setOf("contentId", "value", "durable") && reply["contentId"] == cid.value && reply["durable"] == true)
        return (reply["value"] as? ByteArray ?: error("Snapshot packet bytes")).also {
            require(it.size <= MeshPersistenceCodec.PACKET_BYTES && ContentId.of(it) == cid) { "Snapshot packet is corrupt" }
        }
    }

    private fun bound(reply: Map<String, Any?>, cell: MeshCell) {
        require(reply.keys == setOf("epoch", "durable") && reply["epoch"] == cell.epoch.value && reply["durable"] == true)
    }

    private suspend fun call(persistence: MeshPersistence, peer: String, namespace: String, verb: String,
        fields: Map<String, Any?>): Map<String, Any?> = MeshPersistenceCodec.decode(persistence.execute(peer,
        MeshTask(namespace, verb, CanonicalCbor.encodeMap(fields))), MeshPersistenceCodec.REPLY_BYTES)
}
