package borg.trikeshed.couch.persistence

import borg.trikeshed.cas.FileTreeManifest
import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchCommittedFrame
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.Document
import borg.trikeshed.context.*
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileLease
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability

/**
 * A database commit boundary with explicit volatile and durable modes.
 * Durable commits add immutable CAS nodes and atomically replace one HEAD root;
 * the filesystem lease excludes another writer until this owner closes.
 * A failed root publication has an uncertain outcome and poisons this owner.
 * Conversion consumers can require complete history instead of the legacy truncated-history recovery policy.
 * NONE requires the owner to serialize every access and close. CAS/file resource ownership and writer leases remain.
 */
class CouchCommitStore(
    private val cas: CasStore,
    val durability: StorageDurability,
    private val fileOps: FileOperations? = null,
    directory: String? = null,
    private val requireCompleteHistory: Boolean = false,
    val synchronization: Synchronization = Synchronization.MONITOR,
) : AutoCloseable {
    private val gate = synchronization.lock()
    private val rootPath: String?
    private var lease: FileLease? = null
    private var closed = false
    private var failure: Throwable? = null
    private var head: ContentId? = null
    private var count = 0L
    private var frameSequence = -1L
    private var localSequence = 0L
    private val locals = mutableMapOf<String, ByteArray>()
    private var recovered: List<Join<CouchCommittedFrame, ContentId?>> = emptyList()

    init {
        if (durability == StorageDurability.DURABLE) {
            require(cas.durability == StorageDurability.DURABLE) { "Durable Couch requires a durable CAS" }
            val files = requireNotNull(fileOps) { "Durable Couch requires filesystem operations" }
            require(files.durability == StorageDurability.DURABLE) { "Filesystem does not support durable publication" }
            require(!directory.isNullOrBlank()) { "Durable Couch requires a directory" }
            files.mkdirs(directory)
            rootPath = files.resolvePath(directory, "HEAD")
            lease = files.acquireExclusiveLease(files.resolvePath(directory, ".lock"))
            try {
                if (files.exists(rootPath)) recover(files.readAllBytes(rootPath))
                else publishRoot(null, 0)
            } catch (cause: Throwable) {
                try { lease?.close() } catch (cleanup: Throwable) { cause.addSuppressed(cleanup) }
                lease = null
                throw cause
            }
        } else {
            rootPath = null
        }
    }

    /** Frames present when this owner opened, ordered for projection replay. */
    val recoveredFrames: Series<CouchCommittedFrame>
        get() = synchronize(gate) {
            operational()
            recovered.size j { index ->
                val retained = recovered[index]
                val doc = retained.b?.let { body ->
                    requireNotNull(CouchStoreFactory.documentFromBody(decode(readVerified(body)))) { "Invalid Couch body" }
                }
                retained.a.copy(doc = doc)
            }
        }

    fun checkOpen(): Unit = synchronize(gate) { operational() }

    fun commit(frame: CouchCommittedFrame): Unit = synchronize(gate) {
        operational()
        require(frame.sequence >= 0 && frame.sequence > frameSequence) { "Couch commit sequence must increase" }
        val captured = capture(frame)
        if (durability == StorageDurability.DURABLE) {
            val body = captured.doc?.let { putVerified(CouchStoreFactory.canonicalBody(it)).value }
            append(mapOf(
                "kind" to "frame", "sequence" to captured.sequence,
                "id" to captured.docId, "rev" to captured.rev,
                "deleted" to captured.deleted, "body" to body,
            ))
        }
        frameSequence = captured.sequence
    }

    fun localGet(id: String): Map<String, Any?>? = synchronize(gate) {
        operational()
        locals[id]?.let(::decode)
    }

    fun localPut(id: String, body: Map<String, Any?>): Map<String, Any?> = synchronize(gate) {
        operational()
        require(id.isNotEmpty()) { "Local document id is empty" }
        check(localSequence < Long.MAX_VALUE) { "Local revision sequence exhausted" }
        val next = localSequence + 1
        val value = body.filterKeys { it != "_id" && it != "_rev" } +
            mapOf("_id" to "_local/$id", "_rev" to "0-$next")
        val bytes = CanonicalCbor.encodeMap(value)
        decode(bytes)
        if (durability == StorageDurability.DURABLE) {
            append(mapOf("kind" to "local-put", "id" to id, "sequence" to next,
                "body" to putVerified(bytes).value))
        }
        locals[id] = bytes
        localSequence = next
        decode(bytes)
    }

    fun localDelete(id: String): Boolean = synchronize(gate) {
        operational()
        if (id !in locals) return@synchronize false
        if (durability == StorageDurability.DURABLE) append(mapOf("kind" to "local-delete", "id" to id))
        locals.remove(id)
        true
    }

    /** Offline representation conversion, not replication: preserve local revision/deletion history too. */
    internal fun replayInto(destination: CouchCommitStore, frame: (CouchCommittedFrame) -> Unit) = synchronize(gate) {
        operational()
        require(destination !== this && durability == StorageDurability.DURABLE)
        synchronize(destination.gate) {
            destination.operational()
            require(destination.count == 0L) { "Conversion destination is not empty" }
            val events = mutableListOf<ContentId>()
            var cursor = head
            var remaining = count
            while (remaining > 0) {
                val cid = requireNotNull(cursor) { "Incomplete Couch history" }
                val event = decode(readVerified(cid))
                require(event["format"] == EVENT_FORMAT && integer(event, "index") == remaining)
                events.add(cid)
                cursor = nullableCid(event, "previous")
                remaining--
            }
            require(cursor == null) { "Couch history exceeds its root" }
            for (cid in events.asReversed()) {
                val event = decode(readVerified(cid))
                when (event["kind"]) {
                    "frame" -> {
                        val doc = nullableCid(event, "body")?.let {
                            requireNotNull(CouchStoreFactory.documentFromBody(decode(readVerified(it))))
                        }
                        frame(capture(CouchCommittedFrame(integer(event, "sequence"), string(event, "id"),
                            string(event, "rev"), event["deleted"] as Boolean, doc)))
                    }
                    "local-put" -> {
                        val bytes = readVerified(requireNotNull(nullableCid(event, "body")))
                        val copied = destination.localPut(string(event, "id"), decode(bytes))
                        check(CanonicalCbor.encodeMap(copied).contentEquals(bytes)) { "Local revision changed during conversion" }
                    }
                    "local-delete" -> check(destination.localDelete(string(event, "id"))) { "Local deletion changed during conversion" }
                    else -> error("Unsupported Couch event")
                }
            }
            check(destination.count == count) { "Couch event count changed during conversion" }
            verifyLocals(destination)
        }
    }

    internal fun verifyLocals(destination: CouchCommitStore): Int = synchronize(gate) {
        operational()
        synchronize(destination.gate) {
            destination.operational()
            check(localSequence == destination.localSequence && locals.keys == destination.locals.keys &&
                locals.all { (id, bytes) -> bytes.contentEquals(destination.locals.getValue(id)) }) {
                "Couch local state changed during conversion"
            }
            locals.size
        }
    }

    override fun close(): Unit = synchronize(gate) {
        if (!closed) {
            closed = true
            val owned = lease
            lease = null
            owned?.close()
        }
    }

    private fun operational() {
        check(!closed) { "Couch commit store is closed" }
        failure?.let { throw IllegalStateException("Couch root publication is uncertain; close and reopen", it) }
    }

    private fun append(event: Map<String, Any?>) {
        check(count < Int.MAX_VALUE) { "Couch commit history exceeds replay capacity" }
        // The home was removed under this writer: its history is gone from the CAS. Linking onto it would
        // publish a HEAD whose chain cannot be read back. Stop instead; the next open starts clean.
        head?.let { prior ->
            if (cas.get(prior) == null) {
                val lost = IllegalStateException("Couch home lost its history ($prior missing): directory removed under a running writer")
                failure = lost
                throw lost
            }
        }
        val next = count + 1
        val cid = putVerified(CanonicalCbor.encodeMap(event + mapOf(
            "format" to EVENT_FORMAT, "index" to next, "previous" to head?.value,
        )))
        publishRoot(cid, next)
        head = cid
        count = next
    }

    private fun publishRoot(cid: ContentId?, size: Long) {
        val bytes = CanonicalCbor.encodeMap(mapOf("format" to ROOT_FORMAT, "head" to cid?.value, "count" to size))
        try {
            cas.sync()
            requireNotNull(fileOps).writeAtomically(requireNotNull(rootPath), bytes)
            check(fileOps.readAllBytes(rootPath).contentEquals(bytes)) { "Couch root publication did not retain its bytes" }
        } catch (cause: Throwable) {
            failure = cause
            throw cause
        }
    }

    private fun recover(bytes: ByteArray) {
        val root = decode(bytes)
        require(root.keys == setOf("format", "head", "count") && root["format"] == ROOT_FORMAT) { "Invalid Couch root" }
        val size = integer(root, "count")
        require(size in 0..Int.MAX_VALUE.toLong()) { "Invalid Couch history size" }
        var cursor = nullableCid(root, "head")
        require((cursor == null) == (size == 0L)) { "Couch root does not identify its history" }
        val events = mutableListOf<ContentId>()
        var remaining = size
        var truncated = false
        while (remaining > 0) {
            // A home removed under a live writer leaves a HEAD whose older links are gone. Every frame and
            // local event carries its whole body, so the readable newest events are a consistent store.
            val eventCid = requireNotNull(cursor)
            val bytes = cas.get(eventCid)
            if (bytes == null) {
                require(!requireCompleteHistory) { "Missing Couch history object $cursor at index $remaining" }
                truncated = true; break
            }
            require(ContentId.of(bytes) == cursor) { "Corrupt Couch object $cursor" }
            val event = decode(bytes)
            require(event["format"] == EVENT_FORMAT && integer(event, "index") == remaining) { "Invalid Couch history order" }
            events.add(eventCid)
            cursor = nullableCid(event, "previous")
            remaining--
        }
        if (truncated) println("[COUCH] history truncated below index ${remaining + 1}: ${events.size} of $size events readable from ${nullableCid(root, "head")}")
        else require(cursor == null) { "Couch history exceeds its declared root" }
        val frames = mutableListOf<Join<CouchCommittedFrame, ContentId?>>()
        for (eventCid in events.asReversed()) {
            val event = decode(readVerified(eventCid))
            when (event["kind"]) {
                "frame" -> {
                    require(event.keys == FRAME_KEYS) { "Invalid Couch frame schema" }
                    val sequence = integer(event, "sequence")
                    require(sequence >= 0 && sequence > frameSequence) { "Couch frame sequence is not increasing" }
                    val deleted = event["deleted"] as? Boolean ?: error("Invalid Couch deletion flag")
                    val bodyCid = nullableCid(event, "body")
                    require(deleted == (bodyCid == null)) { "Invalid Couch frame body" }
                    val doc = bodyCid?.let { body ->
                        val bodyBytes = readVerified(body)
                        requireNotNull(CouchStoreFactory.documentFromBody(decode(bodyBytes))) { "Invalid Couch body" }
                            .also(::validateReferences)
                    }
                    // These values are newly decoded and owned by this recovery. Only
                    // frames crossing the public boundary need capture's defensive copy.
                    val frame = CouchCommittedFrame(sequence, string(event, "id"), string(event, "rev"), deleted, doc)
                    validateFrame(frame)
                    frames.add(frame.copy(doc = null) j bodyCid)
                    frameSequence = sequence
                }
                "local-put" -> {
                    require(event.keys == LOCAL_PUT_KEYS) { "Invalid local document schema" }
                    val sequence = integer(event, "sequence")
                    require(localSequence < Long.MAX_VALUE && (sequence == localSequence + 1 || truncated && sequence > localSequence)) { "Invalid local revision sequence" }
                    val id = string(event, "id")
                    val body = readVerified(requireNotNull(nullableCid(event, "body")))
                    val local = decode(body)
                    require(local["_id"] == "_local/$id" && local["_rev"] == "0-$sequence") { "Local document identity mismatch" }
                    locals[id] = body
                    localSequence = sequence
                }
                "local-delete" -> {
                    require(event.keys == LOCAL_DELETE_KEYS) { "Invalid local deletion schema" }
                    require(locals.remove(string(event, "id")) != null || truncated) { "Local deletion has no preceding document" }
                }
                else -> error("Unsupported Couch event")
            }
        }
        recovered = frames
        if (truncated && events.isEmpty()) {
            // Nothing of the history is readable: the home was removed and HEAD names a head that is gone.
            // The store is empty; the next commit publishes a HEAD that starts a readable chain again.
            head = null; count = 0
        } else {
            head = nullableCid(root, "head")
            count = size
        }
    }

    /* ⚡ Bolt: Use HashSet iteration to check uniqueness and _id without intermediate List/Set allocations */
    private fun validateFrame(frame: CouchCommittedFrame) {
        require(frame.docId.isNotEmpty() && frame.rev.isNotEmpty()) { "Couch frame identity is empty" }
        require(frame.deleted == (frame.doc == null)) { "Couch frame body does not match its deletion flag" }
        frame.doc?.let { doc ->
            require(doc.id == frame.docId) { "Couch frame document identity mismatch" }
            val fields = doc.fields
            val seen = HashSet<String>(fields.size)
            for (i in fields.indices) {
                val name = fields[i].name
                require(name != "_id" && seen.add(name)) {
                    "Couch body contains an ambiguous field identity"
                }
            }
        }
    }

    /** Own and validate the body before another durable representation publishes it. */
    internal fun capture(frame: CouchCommittedFrame): CouchCommittedFrame = synchronize(gate) {
        operational()
        validateFrame(frame)
        val doc = frame.doc?.let {
            val bytes = CouchStoreFactory.canonicalBody(it)
            requireNotNull(CouchStoreFactory.documentFromBody(decode(bytes))).also { captured ->
                require(captured.id == frame.docId) { "Canonical Couch body identity mismatch" }
            }
        }
        if (durability == StorageDurability.DURABLE) doc?.let(::validateReferences)
        frame.copy(doc = doc)
    }

    /**
     * An attachment's content must be named by the CAS; its bytes are not read back. A named source
     * edited since stands until the next reconcile supersedes it. Only a file tree is opened, for its extents.
     */
    /* ⚡ Bolt: Search doc.fields directly to avoid intermediate Map and Pair allocations */
    private fun validateReferences(doc: Document) {
        var contentId: String? = null
        var contentType: Any? = null
        var length: Any? = null
        val docFields = doc.fields
        for (i in docFields.indices) {
            val f = docFields[i]
            when (f.name) {
                "contentId" -> contentId = f.value as? String
                "contentType" -> contentType = f.value
                "length" -> length = f.value
            }
        }
        val reference = contentId ?: return
        if (!reference.startsWith("sha256:")) return
        val cid = ContentId(reference)
        if (contentType != FileTreeManifest.CONTENT_TYPE) {
            require(cas.holds(cid)) { "Missing Couch object $cid" }
            return
        }
        val bytes = readVerified(cid)
        length?.let { lengthVal ->
            val size = when (lengthVal) {
                is Number -> exactInteger(lengthVal)
                is String -> lengthVal.toLongOrNull() ?: error("Invalid attachment length")
                else -> error("Invalid attachment length")
            }
            require(size == bytes.size.toLong()) { "Attachment length mismatch" }
        }
        val tree = FileTreeManifest.decode(bytes)
        for ((_, extent) in tree.entries.view) if (extent != null) {
            require(cas.holds(extent.a)) { "Missing Couch object ${extent.a}" }
        }
    }

    private fun putVerified(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        check(cas.put(bytes) == cid && readVerified(cid).contentEquals(bytes)) { "CAS publication did not retain its bytes" }
        return cid
    }

    private fun readVerified(cid: ContentId): ByteArray {
        val bytes = requireNotNull(cas.get(cid)) { "Missing Couch object $cid" }
        require(ContentId.of(bytes) == cid) { "Corrupt Couch object $cid" }
        return bytes
    }

    private fun decode(bytes: ByteArray): Map<String, Any?> {
        val value = CanonicalCbor.decodeMap(bytes)
        require(CanonicalCbor.encodeMap(value).contentEquals(bytes)) { "Noncanonical Couch object" }
        return value
    }

    private fun string(value: Map<String, Any?>, key: String): String =
        (value[key] as? String)?.takeIf { it.isNotEmpty() } ?: error("Missing Couch $key")

    private fun integer(value: Map<String, Any?>, key: String): Long =
        exactInteger(value[key] as? Number ?: error("Missing Couch $key"))

    private fun exactInteger(value: Number): Long {
        val number = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == number.toDouble()) { "Noninteger Couch value" }
        return number
    }

    private fun nullableCid(value: Map<String, Any?>, key: String): ContentId? {
        require(key in value) { "Missing Couch $key" }
        return value[key]?.let { ContentId(it as? String ?: error("Invalid Couch $key")) }
    }

    companion object {
        private const val ROOT_FORMAT = "couch-root-v1"
        private const val EVENT_FORMAT = "couch-commit-v1"
        private val BASE_KEYS = setOf("format", "index", "previous", "kind", "id")
        private val FRAME_KEYS = BASE_KEYS + setOf("sequence", "rev", "deleted", "body")
        private val LOCAL_PUT_KEYS = BASE_KEYS + setOf("sequence", "body")
        private val LOCAL_DELETE_KEYS = BASE_KEYS
    }
}

/** The commit store as a [Couch] `_local` backing: checkpoints recover with the chain. */
fun CouchCommitStore.asLocalBacking(): Couch.LocalBacking = object : Couch.LocalBacking {
    override fun get(id: String): Map<String, Any?>? = localGet(id)
    override fun put(id: String, body: Map<String, Any?>): Map<String, Any?> = localPut(id, body)
    override fun delete(id: String): Boolean = localDelete(id)
}
