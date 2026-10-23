package borg.trikeshed.skinnerbox

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.toAny
import borg.trikeshed.context.Synchronization
import borg.trikeshed.couch.*
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

enum class MeshPersistenceError { UNAUTHORIZED, INVALID, CONFLICT, NOT_FOUND, CAPACITY, CLOSED, IO }

class MeshPersistenceException(val reason: MeshPersistenceError, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class MeshPersistenceLimits(
    val queueCapacity: Int = 32,
    val maxRows: Int = 1024,
    val maxRecords: Int = 4096,
    val retainedBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(queueCapacity in 1..1024 && maxRows in 1..65_536 && maxRecords in 1..65_536)
        require(retainedBytes in 1..1024L * 1024 * 1024)
    }
}

/** Bounded, canonical task maps; the row JSON parser has its own independent work limit. */
object MeshPersistenceCodec {
    const val TASK_BYTES = 128 * 1024
    const val ROW_BYTES = 16 * 1024
    const val OBJECT_BYTES = 64 * 1024
    const val PACKET_BYTES = 96 * 1024
    const val REPLY_BYTES = 112 * 1024

    @Suppress("UNCHECKED_CAST")
    fun decode(bytes: ByteArray, limit: Int = TASK_BYTES): Map<String, Any?> {
        require(bytes.size in 1..limit) { "Persistence packet size" }
        val reader = Cbor.Reader(bytes, 0, maxDepth = 16, maxItems = 2048)
        val item = reader.readItem()
        require(reader.pos == bytes.size && item is Item.Map) { "Persistence packet shape" }
        val value = item.toAny() as Map<String, Any?>
        require(CanonicalCbor.encodeMap(value).contentEquals(bytes)) { "Persistence packet is not canonical" }
        return value
    }

    fun encode(value: Map<String, Any?>): ByteArray = CanonicalCbor.encodeMap(value).also {
        require(it.size <= REPLY_BYTES) { "Persistence reply size" }
    }
}

/**
 * A single Couch/CAS owner. Mutations and their dedupe receipt are one immutable Couch document;
 * the row index is reconstructed from those documents. All filesystem effects stay behind the
 * supplied common FileOperations/CAS providers. The worker is separate from a caller's reactor.
 */
class MeshPersistence private constructor(
    parent: CoroutineScope,
    private val storage: CouchStorage,
    private val cas: CasStore,
    grants: Map<String, Set<String>>,
    val durability: StorageDurability,
    val limits: MeshPersistenceLimits,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<MeshPersistence> {
        suspend fun open(
            scope: CoroutineScope,
            cas: CasStore,
            files: FileOperations,
            location: String,
            grants: Map<String, Set<String>>,
            limits: MeshPersistenceLimits = MeshPersistenceLimits(),
        ): MeshPersistence {
            require(cas.durability == StorageDurability.DURABLE && files.durability == StorageDurability.DURABLE) {
                "Mesh persistence requires durable CAS and filesystem providers; use volatile explicitly otherwise"
            }
            return create(scope, cas, files, location, grants, limits, StorageDurability.DURABLE)
        }

        suspend fun volatile(
            scope: CoroutineScope,
            grants: Map<String, Set<String>>,
            limits: MeshPersistenceLimits = MeshPersistenceLimits(),
        ): MeshPersistence = create(scope, CasStore.inMemory(), InMemoryFileOperations(), "/mesh",
            grants, limits, StorageDurability.VOLATILE)

        private suspend fun create(
            scope: CoroutineScope, cas: CasStore, files: FileOperations, location: String,
            grants: Map<String, Set<String>>, limits: MeshPersistenceLimits, durability: StorageDurability,
        ): MeshPersistence {
            val captured = grants.mapValues { (_, tenants) -> tenants.toSet() }.toMap()
            var created: MeshPersistence? = null
            try {
                return withContext(Dispatchers.Default) {
                    requireNotNull(scope.coroutineContext[Job]) { "Persistence requires an owning Job" }.ensureActive()
                    require(captured.isNotEmpty() && captured.size <= 4096)
                    captured.forEach { (peer, tenants) ->
                        require(peer.isNotBlank() && peer.length <= 256 && tenants.size <= 1024)
                        tenants.forEach(::meshPersistenceToken)
                    }
                    val backing = CouchStorage.open("skinnerbox", cas, files, location,
                        synchronization = Synchronization.NONE, requireCompleteHistory = true)
                    try { MeshPersistence(scope, backing, cas, captured, durability, limits).also { created = it } }
                    catch (failure: Throwable) { backing.close(); throw failure }
                }
            } catch (failure: Throwable) {
                // withContext may cancel on dispatch back after construction succeeded. Keep ownership
                // until the caller actually receives it; a long-lived supplied scope must not leak a lease.
                withContext(NonCancellable) {
                    try { created?.drain() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }

        const val EVENT_FORMAT = "skinnerbox.event/v1"
        const val RECORD_FORMAT = "skinnerbox.persistence/v1"
        const val CELL_FORMAT = "skinnerbox.cell/v1"
        const val CUSTODY_FORMAT = "skinnerbox.custody-ack/v1"
        const val RECOVERY_FORMAT = "skinnerbox.recovery/v1"
        const val MAX_RECOVERY_SEQUENCE = 65_536L
    }

    override val key: CoroutineContext.Key<*> get() = Key
    private val grants = grants.mapValues { (_, tenants) -> tenants.toSet() }.toMap()
    private val couch = storage.couch
    private data class RowKey(val tenant: String, val kind: String, val collection: String, val key: String)
    private data class CollectionKey(val tenant: String, val kind: String, val collection: String)
    private data class Head(val revision: ContentId, val sequence: Long, val content: ContentId?, val deleted: Boolean)
    private val rows = mutableMapOf<RowKey, Head>()
    private val collections = mutableMapOf<CollectionKey, MutableList<String>>()
    private val events = mutableMapOf<ContentId, String>()
    private val epochs = mutableMapOf<String, ContentId>()
    private val pending = mutableMapOf<String, MutableList<ContentId>>()
    private val acknowledged = mutableSetOf<ContentId>()
    private val recovering = mutableSetOf<String>()
    private val recovered = mutableSetOf<String>()
    private data class Recovery(val manifest: ContentId, val events: Long, val cutoff: Long, var imported: Long = 0, var last: Long = 0)
    private val recovery = mutableMapOf<String, Recovery>()
    private var recoveryGaps = 0
    private var records = 0
    private var retainedBytes = 0L
    private var failure: Throwable? = null
    private var closeFailure: Throwable? = null

    // Replay before admitting any caller. Bodies are read one at a time; only row heads remain hot.
    init { replay() }

    private class Request(val peer: String, val task: MeshTask) {
        val result = CompletableDeferred<ByteArray>()
        val settled = CompletableDeferred<Unit>()
        fun fail(cause: Throwable) { result.completeExceptionally(cause); settled.complete(Unit) }
    }

    private val supervisor = SupervisorJob(parent.coroutineContext[Job])
    private val input = Channel<Request>(limits.queueCapacity, onUndeliveredElement = {
        it.fail(CancellationException("Persistence request was not admitted"))
    })
    private val worker = CoroutineScope(parent.coroutineContext + supervisor + Dispatchers.Default + this)
        .launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (request in input) {
                    withContext(NonCancellable) {
                        try { request.result.complete(process(request.peer, request.task)) }
                        catch (cause: Throwable) { request.result.completeExceptionally(cause) }
                        finally { request.settled.complete(Unit) }
                    }
                    yield()
                }
            } finally {
                input.close()
                while (true) {
                    val request = input.tryReceive().getOrNull() ?: break
                    request.fail(CancellationException("Persistence owner stopped"))
                }
                try { storage.close() } catch (cause: Throwable) { closeFailure = cause }
                finally { supervisor.complete() }
            }
        }

    suspend fun execute(peerId: String, task: MeshTask): ByteArray {
        currentCoroutineContext().ensureActive()
        val request = Request(peerId, task)
        val admission = input.trySend(request)
        if (!admission.isSuccess) {
            val cause = MeshPersistenceException(
                if (admission.isClosed) MeshPersistenceError.CLOSED else MeshPersistenceError.CAPACITY,
                if (admission.isClosed) "Persistence admission is closed" else "Persistence admission is full",
                admission.exceptionOrNull(),
            )
            request.fail(cause)
            throw cause
        }
        try {
            return request.result.await()
        } finally {
            withContext(NonCancellable) { request.settled.await() }
        }
    }

    suspend fun drain() = withContext(NonCancellable) {
        input.close()
        worker.join()
        supervisor.complete()
        supervisor.join()
        closeFailure?.let { throw MeshPersistenceException(MeshPersistenceError.IO, "Persistence close failed", it) }
    }

    fun authorize(peerId: String, namespace: String) {
        if (namespace !in grants[peerId].orEmpty()) deny(MeshPersistenceError.UNAUTHORIZED, "Peer has no tenant grant")
    }

    internal fun authorizedPeer(namespace: String): String? = grants.entries.firstOrNull { namespace in it.value }?.key

    private fun process(peer: String, task: MeshTask): ByteArray {
        authorize(peer, task.namespace)
        failure?.let { throw MeshPersistenceException(MeshPersistenceError.IO, "Publication uncertain; reopen persistence", it) }
        if (recovering.isNotEmpty() && task.verb !in setOf("recovery.advance", "recovery.event", "recovery.end"))
            deny(MeshPersistenceError.IO, "Offline recovery is incomplete; normal admission is closed")
        val body = try { MeshPersistenceCodec.decode(task.payload) }
        catch (cause: Exception) { throw MeshPersistenceException(MeshPersistenceError.INVALID, "Invalid persistence payload", cause) }
        val reply = when (task.verb) {
            "cell.bind" -> bind(task.namespace, body)
            "row.put", "row.delete", "object.put", "object.delete" -> mutate(peer, task, body)
            "row.get", "object.get" -> get(task.namespace, task.verb.substringBefore('.'), body)
            "row.list", "object.list" -> list(task.namespace, task.verb.substringBefore('.'), body)
            "changes" -> changes(task.namespace, body)
            "event.get" -> event(task.namespace, body)
            "replica.retain" -> retain(task.namespace, body)
            "replica.read" -> replica(task.namespace, body)
            "replica.list" -> replicas(task.namespace, body)
            "custody.pending" -> pending(task.namespace, body)
            "custody.ack" -> acknowledge(task.namespace, body)
            "recovery.begin" -> recoveryBegin(task.namespace, body)
            "recovery.advance" -> recoveryAdvance(task.namespace, body)
            "recovery.event" -> recoveryEvent(task.namespace, body)
            "recovery.end" -> recoveryEnd(task.namespace, body)
            else -> deny(MeshPersistenceError.INVALID, "Unknown persistence operation")
        }
        return MeshPersistenceCodec.encode(reply)
    }

    private fun bind(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("epoch"))
        val epoch = requireNotNull(cid(body, "epoch"))
        val bound = epochs[tenant]
        if (bound != null && bound != epoch) deny(MeshPersistenceError.CONFLICT,
            "Namespace is bound to another cell epoch; explicit handoff is required")
        if (bound == null) {
            val bytes = CanonicalCbor.encodeMap(mapOf("format" to CELL_FORMAT, "tenant" to tenant, "epoch" to epoch.value))
            capacity(bytes.size.toLong())
            try {
                publish("cells/$tenant", tenant, "cell", bytes)
                epochs[tenant] = epoch
                records++
                retainedBytes += bytes.size
            } catch (cause: Throwable) { uncertain(cause) }
        }
        return mapOf("epoch" to epoch.value, "durable" to durable())
    }

    private fun mutate(peer: String, task: MeshTask, body: Map<String, Any?>): Map<String, Any?> {
        val deleted = task.verb.endsWith(".delete")
        fields(body, setOf("requestId", "collection", "key", "expectedRevision") + if (deleted) emptySet() else setOf("value"))
        val request = token(body, "requestId")
        val collection = token(body, "collection")
        val key = token(body, "key")
        val expected = cid(body, "expectedRevision", nullable = true)
        val kind = task.verb.substringBefore('.')
        val identity = "events/${task.namespace}/$request"
        val digest = ContentId.of(CanonicalCbor.encodeMap(mapOf(
            "tenant" to task.namespace, "verb" to task.verb, "payload" to task.payload,
        )))
        loadRecord(identity)?.let { record ->
            val bytes = packet(record)
            val previous = MeshPersistenceCodec.decode(bytes, MeshPersistenceCodec.PACKET_BYTES)
            if (previous["requestDigest"] != digest.value) deny(MeshPersistenceError.CONFLICT, "Request identity already names another mutation")
            return receipt(bytes, previous)
        }
        val row = RowKey(task.namespace, kind, collection, key)
        val previous = rows[row]
        if (previous?.revision != expected) deny(MeshPersistenceError.CONFLICT, "Expected revision does not match row head")
        if (deleted && (previous == null || previous.deleted)) deny(MeshPersistenceError.NOT_FOUND, "Row is absent")
        if (previous == null && rows.size >= limits.maxRows) deny(MeshPersistenceError.CAPACITY, "Row index limit")
        val value = if (deleted) ByteArray(0) else bytes(body, "value",
            if (kind == "row") MeshPersistenceCodec.ROW_BYTES else MeshPersistenceCodec.OBJECT_BYTES)
        if (kind == "row" && !deleted) {
            val parsed = try { TwinJson.parse(value, MeshPersistenceCodec.ROW_BYTES) }
            catch (cause: Exception) { throw MeshPersistenceException(MeshPersistenceError.INVALID, "Invalid row JSON", cause) }
            if (parsed !is Map<*, *>) deny(MeshPersistenceError.INVALID, "A row must be a JSON object")
        }
        val content = if (deleted) null else ContentId.of(value)
        val event = mapOf(
            "format" to EVENT_FORMAT, "tenant" to task.namespace, "peer" to peer,
            "requestId" to request, "requestDigest" to digest.value, "kind" to kind,
            "collection" to collection, "key" to key, "previous" to expected?.value,
            "sequence" to couch.updateSeq + 1, "deleted" to deleted,
            "contentId" to content?.value, "value" to value,
        )
        val eventBytes = CanonicalCbor.encodeMap(event)
        val retained = eventBytes.size.toLong() + value.size
        capacity(retained)
        try {
            if (content != null) check(cas.put(value) == content)
            publish(identity, task.namespace, "event", eventBytes)
            applyEvent(eventBytes, event, couch.updateSeq)
            records++
            retainedBytes += retained
        } catch (cause: Throwable) { uncertain(cause) }
        return receipt(eventBytes, event)
    }

    private fun get(tenant: String, kind: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("collection", "key"))
        val row = RowKey(tenant, kind, token(body, "collection"), token(body, "key"))
        val head = rows[row]?.takeUnless { it.deleted } ?: deny(MeshPersistenceError.NOT_FOUND, "Row is absent")
        return row(row, head)
    }

    private fun row(key: RowKey, head: Head): Map<String, Any?> = mapOf(
        "key" to key.key, "collection" to key.collection, "revision" to head.revision.value,
        "sequence" to head.sequence, "contentId" to head.content?.value, "deleted" to head.deleted,
        "value" to readVerified(requireNotNull(head.content)),
    )

    private fun list(tenant: String, kind: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("collection"), setOf("after", "limit"))
        val collection = token(body, "collection")
        val after = body["after"]?.let { token(body, "after") }
        val limit = pageLimit(body)
        val candidates = collections[CollectionKey(tenant, kind, collection)].orEmpty()
        var index = if (after == null) 0 else candidates.binarySearch(after).let { if (it >= 0) it + 1 else -it - 1 }
        val output = mutableListOf<Map<String, Any?>>()
        var cursor = after
        var scanned = 0
        var encodedBytes = 0
        while (index < candidates.size && scanned < 64 && output.size < limit) {
            val key = candidates[index]
            val candidate = RowKey(tenant, kind, collection, key)
            val head = rows.getValue(candidate)
            if (head.deleted) { cursor = key; index++; scanned++; continue }
            val next = row(candidate, head)
            val size = CanonicalCbor.encodeMap(next).size
            if (encodedBytes + size > MeshPersistenceCodec.REPLY_BYTES - 1024) break
            output.add(next)
            encodedBytes += size
            cursor = key
            index++
            scanned++
        }
        return mapOf("rows" to output, "after" to cursor, "more" to (index < candidates.size))
    }

    private fun changes(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, emptySet(), setOf("since", "limit"))
        val since = integer(body, "since", 0)
        if (since < 0 || since > couch.updateSeq) deny(MeshPersistenceError.INVALID, "Change cursor out of range")
        val limit = pageLimit(body)
        val output = mutableListOf<Map<String, Any?>>()
        var cursor = since
        val frames = couch.store.changes.afterSequence(since - 1)
        var index = 0
        while (index < frames.size && index < 64 && output.size < limit) {
            val frame = frames[index++]
            val record = requireNotNull(loadRecord(frame.docId))
            if (record["tenant"] == tenant && record["category"] == "event") {
                val bytes = packet(record)
                val event = MeshPersistenceCodec.decode(bytes, MeshPersistenceCodec.PACKET_BYTES)
                output.add(event.filterKeys { it in setOf("sequence", "collection", "key", "kind", "contentId", "deleted") } +
                    ("revision" to ContentId.of(bytes).value))
            }
            cursor = frame.sequence + 1
        }
        return mapOf("changes" to output, "since" to cursor, "more" to (index < frames.size))
    }

    private fun event(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("revision"))
        val cid = requireNotNull(cid(body, "revision"))
        if (events[cid] != tenant) deny(MeshPersistenceError.NOT_FOUND, "Event is absent")
        return mapOf("revision" to cid.value, "value" to readVerified(cid))
    }

    private fun retain(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("contentId", "value"))
        val content = requireNotNull(cid(body, "contentId"))
        val value = bytes(body, "value", MeshPersistenceCodec.PACKET_BYTES)
        if (ContentId.of(value) != content) deny(MeshPersistenceError.INVALID, "Replica packet digest mismatch")
        val identity = "replicas/$tenant/${content.hex}"
        val previous = loadRecord(identity)
        if (previous != null) {
            if (!packet(previous).contentEquals(value)) deny(MeshPersistenceError.IO, "Replica packet changed")
        } else {
            capacity(value.size.toLong())
            try { publish(identity, tenant, "replica", value) }
            catch (cause: Throwable) { uncertain(cause) }
            records++
            retainedBytes += value.size
        }
        return mapOf("contentId" to content.value, "bytes" to value.size.toLong(), "durable" to durable())
    }

    private fun replica(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("contentId"))
        val content = requireNotNull(cid(body, "contentId"))
        val record = loadRecord("replicas/$tenant/${content.hex}")
            ?: deny(MeshPersistenceError.NOT_FOUND, "Replica packet is absent")
        return mapOf("contentId" to content.value, "value" to packet(record), "durable" to durable())
    }

    /** Offline inventory; like changes, one call examines at most 64 commit positions. */
    private fun replicas(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, emptySet(), setOf("since", "limit"))
        val since = integer(body, "since", 0)
        if (since !in 0..couch.updateSeq) deny(MeshPersistenceError.INVALID, "Replica cursor out of range")
        val limit = pageLimit(body)
        val output = mutableListOf<Map<String, Any?>>()
        val frames = couch.store.changes.afterSequence(since - 1)
        var index = 0
        var cursor = since
        while (index < frames.size && index < 64 && output.size < limit) {
            val frame = frames[index++]
            val record = requireNotNull(loadRecord(frame.docId))
            if (record["tenant"] == tenant && record["category"] == "replica")
                output.add(mapOf("contentId" to record["contentId"]))
            cursor = frame.sequence + 1
        }
        return mapOf("replicas" to output, "since" to cursor, "more" to (index < frames.size))
    }

    /** The namespace owner repairs the oldest durable mutation before admitting a later one. */
    private fun pending(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, emptySet())
        val waiting = pending[tenant].orEmpty()
        return mapOf("events" to waiting.firstOrNull()?.let { listOf(readVerified(it)) }.orEmpty(),
            "more" to (waiting.size > 1))
    }

    /** Internal owner operation, never exposed by MeshNode's public verb allowlist. */
    private fun acknowledge(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("epoch", "revision"))
        val epoch = requireNotNull(cid(body, "epoch"))
        val revision = requireNotNull(cid(body, "revision"))
        if (epochs[tenant] != epoch) deny(MeshPersistenceError.CONFLICT, "Custody epoch differs")
        if (events[revision] != tenant) deny(MeshPersistenceError.NOT_FOUND, "Custody event is absent")
        if (revision !in acknowledged) {
            if (pending[tenant]?.firstOrNull() != revision)
                deny(MeshPersistenceError.CONFLICT, "Custody must complete the oldest pending event")
            val bytes = CanonicalCbor.encodeMap(mapOf("format" to CUSTODY_FORMAT, "tenant" to tenant,
                "epoch" to epoch.value, "revision" to revision.value))
            capacity(bytes.size.toLong())
            try {
                publish("custody/$tenant/${revision.hex}", tenant, "custody", bytes)
                applyAcknowledgment(tenant, revision)
                records++
                retainedBytes += bytes.size
            } catch (cause: Throwable) { uncertain(cause) }
        }
        return mapOf("revision" to revision.value, "durable" to durable())
    }

    private fun applyAcknowledgment(tenant: String, revision: ContentId) {
        require(events[revision] == tenant && pending[tenant]?.firstOrNull() == revision && acknowledged.add(revision)) {
            "Custody acknowledgment does not complete the pending prefix"
        }
        pending.getValue(tenant).removeAt(0)
    }

    /** Explicit offline import into a fresh original-owner store. A partial import cannot serve traffic. */
    private fun recoveryBegin(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("epoch", "manifest", "events", "cutoff"))
        val epoch = requireNotNull(cid(body, "epoch"))
        val expected = recovery(body)
        if (records != 0 || couch.updateSeq != 0L) deny(MeshPersistenceError.CONFLICT, "Recovery requires a fresh destination")
        val bytes = CanonicalCbor.encodeMap(mapOf("format" to RECOVERY_FORMAT, "tenant" to tenant, "epoch" to epoch.value,
            "manifest" to expected.manifest.value, "events" to expected.events, "cutoff" to expected.cutoff))
        capacity(bytes.size.toLong())
        try {
            publish("recovery/$tenant/start", tenant, "recovery-start", bytes)
            epochs[tenant] = epoch
            recovery[tenant] = expected
            recovering.add(tenant)
            records++
            retainedBytes += bytes.size
        } catch (cause: Throwable) { uncertain(cause) }
        return mapOf("epoch" to epoch.value, "durable" to durable())
    }

    private fun recoveryEvent(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("epoch", "value"))
        val epoch = requireNotNull(cid(body, "epoch"))
        if (tenant !in recovering || epochs[tenant] != epoch)
            deny(MeshPersistenceError.CONFLICT, "Recovery is not active for this epoch")
        val value = bytes(body, "value", MeshPersistenceCodec.PACKET_BYTES)
        val event = MeshPersistenceCodec.decode(value, MeshPersistenceCodec.PACKET_BYTES)
        if (event["tenant"] != tenant) deny(MeshPersistenceError.INVALID, "Recovery event tenant differs")
        val peer = event["peer"] as? String ?: deny(MeshPersistenceError.INVALID, "Recovery event author is absent")
        authorize(peer, tenant)
        val sequence = event["sequence"] as? Long ?: deny(MeshPersistenceError.INVALID, "Recovery sequence is absent")
        if (sequence !in 2..MAX_RECOVERY_SEQUENCE) deny(MeshPersistenceError.INVALID, "Recovery sequence bound")
        val request = token(event, "requestId")
        val identity = "events/$tenant/$request"
        loadRecord(identity)?.let { retained ->
            if (!packet(retained).contentEquals(value)) deny(MeshPersistenceError.CONFLICT, "Recovery request identity differs")
            return receipt(value, event)
        }
        if (sequence != couch.updateSeq + 1) deny(MeshPersistenceError.CONFLICT, "Advance recovery to the original event sequence first")
        val content = cid(event, "contentId", nullable = true)
        val contentBytes = bytes(event, "value", MeshPersistenceCodec.OBJECT_BYTES)
        if (content != null) {
            if (ContentId.of(contentBytes) != content) deny(MeshPersistenceError.INVALID, "Recovery content identity differs")
            check(cas.put(contentBytes) == content)
        }
        // Validate the complete predecessor chain and schema before publishing an event.
        applyEvent(value, event, sequence, commit = false)
        val retained = value.size.toLong() + contentBytes.size
        capacity(retained)
        try {
            publish(identity, tenant, "event", value)
            applyEvent(value, event, couch.updateSeq)
            records++
            retainedBytes += retained
        } catch (cause: Throwable) { uncertain(cause) }
        return receipt(value, event)
    }

    /** Gaps preserve original receipt sequences without inventing lost global commit records. */
    private fun recoveryAdvance(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("epoch", "sequence"))
        val epoch = requireNotNull(cid(body, "epoch"))
        val sequence = integer(body, "sequence", 0)
        if (tenant !in recovering || epochs[tenant] != epoch)
            deny(MeshPersistenceError.CONFLICT, "Recovery is not active for this epoch")
        if (sequence !in 2..MAX_RECOVERY_SEQUENCE || sequence <= couch.updateSeq)
            deny(MeshPersistenceError.INVALID, "Recovery sequence bound/order")
        var advanced = 0
        try {
            while (couch.updateSeq + 1 < sequence && advanced < 8) {
                val next = couch.updateSeq + 1
                val gap = CanonicalCbor.encodeMap(mapOf("format" to RECOVERY_FORMAT, "tenant" to tenant,
                    "epoch" to epoch.value, "sequence" to next))
                capacity(gap.size.toLong(), record = false)
                require(recoveryGaps < MAX_RECOVERY_SEQUENCE)
                publish("recovery/$tenant/gap-$next", tenant, "recovery-gap", gap)
                recoveryGaps++
                retainedBytes += gap.size
                advanced++
            }
        } catch (cause: Throwable) { uncertain(cause) }
        return mapOf("sequence" to couch.updateSeq, "ready" to (couch.updateSeq + 1 == sequence), "durable" to durable())
    }

    private fun recoveryEnd(tenant: String, body: Map<String, Any?>): Map<String, Any?> {
        fields(body, setOf("epoch", "manifest"))
        val epoch = requireNotNull(cid(body, "epoch"))
        if (epochs[tenant] != epoch || tenant !in recovering && tenant !in recovered)
            deny(MeshPersistenceError.CONFLICT, "Recovery is not active for this epoch")
        val expected = recovery.getValue(tenant)
        if (cid(body, "manifest") != expected.manifest || expected.imported != expected.events || expected.last != expected.cutoff)
            deny(MeshPersistenceError.CONFLICT, "Recovery has not imported the declared manifest cutoff")
        if (tenant in recovering) {
            val bytes = CanonicalCbor.encodeMap(mapOf("format" to RECOVERY_FORMAT, "tenant" to tenant, "epoch" to epoch.value,
                "manifest" to expected.manifest.value))
            capacity(bytes.size.toLong())
            try {
                publish("recovery/$tenant/end", tenant, "recovery-end", bytes)
                recovering.remove(tenant)
                recovered.add(tenant)
                records++
                retainedBytes += bytes.size
            } catch (cause: Throwable) { uncertain(cause) }
        }
        return mapOf("epoch" to epoch.value, "durable" to durable())
    }

    private fun recovery(fields: Map<String, Any?>): Recovery {
        val count = integer(fields, "events", -1)
        val cutoff = integer(fields, "cutoff", -1)
        if (count !in 0..4096L || (count == 0L && cutoff != 0L) || (count > 0 && cutoff !in 2..MAX_RECOVERY_SEQUENCE))
            deny(MeshPersistenceError.INVALID, "Recovery manifest bounds")
        return Recovery(requireNotNull(cid(fields, "manifest")), count, cutoff)
    }

    private fun publish(identity: String, tenant: String, category: String, bytes: ByteArray) {
        val content = cas.put(bytes)
        check(content == ContentId.of(bytes)) { "CAS returned a different content identity" }
        cas.sync()
        val reply = couch.put(identity, mapOf("format" to RECORD_FORMAT, "category" to category,
            "tenant" to tenant, "contentId" to content.value, "contentType" to "application/octet-stream"), null)
        check(reply["ok"] == true) { "Immutable persistence record already exists" }
    }

    private fun loadRecord(identity: String): Map<String, Any?>? = couch.store.get(identity)?.let { doc ->
        doc.fields.associate { it.name to it.value } + ("_id" to doc.id)
    }

    private fun packet(record: Map<String, Any?>): ByteArray = readVerified(
        ContentId(record["contentId"] as? String ?: error("Persistence record has no content identity")))

    private fun readVerified(content: ContentId): ByteArray {
        val bytes = cas.get(content) ?: error("Missing persistence object $content")
        check(ContentId.of(bytes) == content) { "Corrupt persistence object $content" }
        return bytes
    }

    private fun receipt(bytes: ByteArray, event: Map<String, Any?>): Map<String, Any?> = mapOf(
        "requestId" to event["requestId"], "revision" to ContentId.of(bytes).value,
        "sequence" to event["sequence"], "contentId" to event["contentId"],
        "deleted" to event["deleted"], "durable" to durable(), "event" to bytes,
    )

    private fun replay() {
        for (frame in couch.store.changes.series().view) {
            require(!frame.deleted) { "Persistence history contains deleted immutable records" }
            val record = requireNotNull(loadRecord(frame.docId))
            require(record["format"] == RECORD_FORMAT && record.keys == setOf(
                "_id", "format", "category", "tenant", "contentId", "contentType")) { "Unknown persistence record" }
            val tenant = record["tenant"] as? String ?: error("Missing persistence tenant")
            meshPersistenceToken(tenant)
            val bytes = packet(record)
            val retained = when (record["category"]) {
                "event" -> {
                    val event = MeshPersistenceCodec.decode(bytes, MeshPersistenceCodec.PACKET_BYTES)
                    require(event["tenant"] == tenant && frame.docId == "events/$tenant/${event["requestId"]}")
                    applyEvent(bytes, event, frame.sequence + 1)
                    bytes.size.toLong() + (event["value"] as ByteArray).size
                }
                "replica" -> {
                    require(bytes.size <= MeshPersistenceCodec.PACKET_BYTES &&
                        frame.docId == "replicas/$tenant/${ContentId.of(bytes).hex}")
                    bytes.size.toLong()
                }
                "cell" -> {
                    val cell = MeshPersistenceCodec.decode(bytes)
                    require(cell.keys == setOf("format", "tenant", "epoch") && cell["format"] == CELL_FORMAT &&
                        cell["tenant"] == tenant && frame.docId == "cells/$tenant") { "Invalid cell epoch binding" }
                    require(epochs.put(tenant, requireNotNull(cid(cell, "epoch"))) == null) { "Cell epoch was rewritten" }
                    bytes.size.toLong()
                }
                "custody" -> {
                    val ack = MeshPersistenceCodec.decode(bytes)
                    require(ack.keys == setOf("format", "tenant", "epoch", "revision") && ack["format"] == CUSTODY_FORMAT &&
                        ack["tenant"] == tenant && ack["epoch"] == epochs[tenant]?.value) { "Invalid custody acknowledgment" }
                    val revision = requireNotNull(cid(ack, "revision"))
                    require(frame.docId == "custody/$tenant/${revision.hex}")
                    applyAcknowledgment(tenant, revision)
                    bytes.size.toLong()
                }
                "recovery-start", "recovery-end", "recovery-gap" -> {
                    val marker = MeshPersistenceCodec.decode(bytes)
                    val extra = when (record["category"]) {
                        "recovery-start" -> setOf("manifest", "events", "cutoff")
                        "recovery-end" -> setOf("manifest")
                        else -> setOf("sequence")
                    }
                    require(marker.keys == setOf("format", "tenant", "epoch") + extra)
                    require(marker["format"] == RECOVERY_FORMAT && marker["tenant"] == tenant)
                    val epoch = requireNotNull(cid(marker, "epoch"))
                    when (record["category"]) {
                        "recovery-start" -> {
                            require(frame.sequence == 0L && frame.docId == "recovery/$tenant/start" && epochs.isEmpty())
                            epochs[tenant] = epoch
                            recovery[tenant] = recovery(marker)
                            recovering.add(tenant)
                        }
                        "recovery-end" -> {
                            val expected = recovery.getValue(tenant)
                            require(marker["manifest"] == expected.manifest.value && expected.imported == expected.events &&
                                expected.last == expected.cutoff) { "Incomplete recovery manifest" }
                            require(epochs[tenant] == epoch && recovering.remove(tenant) && frame.docId == "recovery/$tenant/end")
                            recovered.add(tenant)
                        }
                        else -> {
                            require(epochs[tenant] == epoch && tenant in recovering &&
                                marker["sequence"] == frame.sequence + 1 && frame.sequence + 1 <= MAX_RECOVERY_SEQUENCE &&
                                frame.docId == "recovery/$tenant/gap-${frame.sequence + 1}" && recoveryGaps < MAX_RECOVERY_SEQUENCE)
                            recoveryGaps++
                        }
                    }
                    bytes.size.toLong()
                }
                else -> error("Unknown persistence record category")
            }
            val semantic = record["category"] != "recovery-gap"
            capacity(retained, record = semantic)
            if (semantic) records++
            retainedBytes += retained
        }
    }

    private fun applyEvent(packetBytes: ByteArray, event: Map<String, Any?>, sequence: Long, commit: Boolean = true) {
        require(event.keys == setOf("format", "tenant", "peer", "requestId", "requestDigest", "kind", "collection",
            "key", "previous", "sequence", "deleted", "contentId", "value") && event["format"] == EVENT_FORMAT)
        val tenant = token(event, "tenant")
        token(event, "requestId")
        require((event["peer"] as? String)?.let { it.isNotBlank() && it.length <= 256 } == true)
        cid(event, "requestDigest")
        val kind = event["kind"] as? String
        require(kind == "row" || kind == "object")
        val row = RowKey(tenant, kind, token(event, "collection"), token(event, "key"))
        require(event["sequence"] == sequence && sequence > 0)
        val previous = cid(event, "previous", nullable = true)
        require(rows[row]?.revision == previous) { "Persistence predecessor chain mismatch" }
        val deleted = event["deleted"] as? Boolean ?: error("Missing deletion flag")
        val value = bytes(event, "value", if (kind == "row") MeshPersistenceCodec.ROW_BYTES else MeshPersistenceCodec.OBJECT_BYTES)
        val content = cid(event, "contentId", nullable = true)
        if (deleted) require(content == null && value.isEmpty() && rows[row]?.deleted == false)
        else {
            require(content == ContentId.of(value) && readVerified(requireNotNull(content)).contentEquals(value))
            if (kind == "row") require(TwinJson.parse(value, MeshPersistenceCodec.ROW_BYTES) is Map<*, *>)
        }
        require(row in rows || rows.size < limits.maxRows) { "Persistence row index bound" }
        val revision = ContentId.of(packetBytes)
        require(revision !in events) { "Duplicate persistence event" }
        val expectedRecovery = if (tenant in recovering) recovery.getValue(tenant) else null
        require(expectedRecovery == null || expectedRecovery.imported < expectedRecovery.events && sequence <= expectedRecovery.cutoff) {
            "Recovery exceeds the declared manifest"
        }
        if (!commit) return
        events[revision] = tenant
        pending.getOrPut(tenant) { mutableListOf() }.add(revision)
        if (expectedRecovery != null) {
            expectedRecovery.imported++
            expectedRecovery.last = sequence
        }
        if (row !in rows) {
            val keys = collections.getOrPut(CollectionKey(tenant, kind, row.collection)) { mutableListOf() }
            val insertion = keys.binarySearch(row.key)
            require(insertion < 0)
            keys.add(-insertion - 1, row.key)
        }
        rows[row] = Head(revision, sequence, content, deleted)
    }

    private fun capacity(bytes: Long, record: Boolean = true) {
        if (record && records >= limits.maxRecords || bytes < 0 || retainedBytes > limits.retainedBytes - bytes)
            deny(MeshPersistenceError.CAPACITY, "Persistence retention limit")
    }

    private fun durable() = durability == StorageDurability.DURABLE
    private fun uncertain(cause: Throwable): Nothing {
        failure = cause
        throw MeshPersistenceException(MeshPersistenceError.IO, "Persistence publication failed; reopen before retry", cause)
    }

    private fun fields(body: Map<String, Any?>, required: Set<String>, optional: Set<String> = emptySet()) {
        if (!body.keys.containsAll(required) || body.keys.any { it !in required && it !in optional })
            deny(MeshPersistenceError.INVALID, "Persistence operation fields")
    }
    private fun token(body: Map<String, Any?>, name: String): String {
        val value = body[name] as? String ?: deny(MeshPersistenceError.INVALID, "Missing $name")
        try { meshPersistenceToken(value) } catch (cause: IllegalArgumentException) {
            throw MeshPersistenceException(MeshPersistenceError.INVALID, "Invalid $name", cause)
        }
        return value
    }
    private fun cid(body: Map<String, Any?>, name: String, nullable: Boolean = false): ContentId? {
        val value = body[name]
        if (value == null && nullable) return null
        return try { ContentId(value as? String ?: error("Missing content identity")) }
        catch (cause: Exception) { throw MeshPersistenceException(MeshPersistenceError.INVALID, "Invalid $name", cause) }
    }
    private fun bytes(body: Map<String, Any?>, name: String, limit: Int): ByteArray {
        val value = body[name] as? ByteArray ?: deny(MeshPersistenceError.INVALID, "Missing $name bytes")
        if (value.size > limit) deny(MeshPersistenceError.INVALID, "$name byte limit")
        return value
    }
    private fun integer(body: Map<String, Any?>, name: String, default: Long): Long =
        if (name !in body) default else body[name] as? Long ?: deny(MeshPersistenceError.INVALID, "Invalid $name integer")
    private fun pageLimit(body: Map<String, Any?>): Int = integer(body, "limit", 32).let {
        if (it !in 1..64) deny(MeshPersistenceError.INVALID, "Page limit must be 1 through 64")
        it.toInt()
    }
    private fun deny(reason: MeshPersistenceError, message: String): Nothing = throw MeshPersistenceException(reason, message)
}

internal fun meshPersistenceToken(value: String) {
    require(value.length in 1..128 && value.first().let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } &&
        value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._-" }) { "Invalid persistence token" }
}
