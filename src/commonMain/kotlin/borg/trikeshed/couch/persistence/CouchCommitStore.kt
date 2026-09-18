package borg.trikeshed.couch.persistence

import borg.trikeshed.cas.FileTreeManifest
import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchCommittedFrame
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.Document
import borg.trikeshed.isam.synchronizedLock
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.view
import borg.trikeshed.userspace.nio.file.spi.FileLease
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability

/**
 * A database commit boundary with explicit volatile and durable modes.
 * Durable commits add immutable CAS nodes and atomically replace one HEAD root;
 * the filesystem lease excludes another writer until this owner closes.
 * A failed root publication has an uncertain outcome and poisons this owner.
 */
class CouchCommitStore(
    private val cas: CasStore,
    val durability: StorageDurability,
    private val fileOps: FileOperations? = null,
    directory: String? = null,
) : AutoCloseable {
    private val gate = Any()
    private val rootPath: String?
    private var lease: FileLease? = null
    private var closed = false
    private var failure: Throwable? = null
    private var head: ContentId? = null
    private var count = 0L
    private var frameSequence = -1L
    private var localSequence = 0L
    private val locals = mutableMapOf<String, ByteArray>()
    private var recovered: List<CouchCommittedFrame> = emptyList()

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
        get() = synchronizedLock(gate) {
            operational()
            recovered.size j { index -> capture(recovered[index]) }
        }

    fun checkOpen(): Unit = synchronizedLock(gate) { operational() }

    fun commit(frame: CouchCommittedFrame): Unit = synchronizedLock(gate) {
        operational()
        require(frame.sequence >= 0 && frame.sequence > frameSequence) { "Couch commit sequence must increase" }
        val captured = capture(frame)
        if (durability == StorageDurability.DURABLE) {
            captured.doc?.let(::validateReferences)
            val body = captured.doc?.let { putVerified(CouchStoreFactory.canonicalBody(it)).value }
            append(mapOf(
                "kind" to "frame", "sequence" to captured.sequence,
                "id" to captured.docId, "rev" to captured.rev,
                "deleted" to captured.deleted, "body" to body,
            ))
        }
        frameSequence = captured.sequence
    }

    fun localGet(id: String): Map<String, Any?>? = synchronizedLock(gate) {
        operational()
        locals[id]?.let(::decode)
    }

    fun localPut(id: String, body: Map<String, Any?>): Map<String, Any?> = synchronizedLock(gate) {
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

    fun localDelete(id: String): Boolean = synchronizedLock(gate) {
        operational()
        if (id !in locals) return@synchronizedLock false
        if (durability == StorageDurability.DURABLE) append(mapOf("kind" to "local-delete", "id" to id))
        locals.remove(id)
        true
    }

    override fun close(): Unit = synchronizedLock(gate) {
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
        val events = mutableListOf<Map<String, Any?>>()
        var remaining = size
        while (remaining > 0) {
            val event = decode(readVerified(requireNotNull(cursor)))
            require(event["format"] == EVENT_FORMAT && integer(event, "index") == remaining) { "Invalid Couch history order" }
            events.add(event)
            cursor = nullableCid(event, "previous")
            remaining--
        }
        require(cursor == null) { "Couch history exceeds its declared root" }
        val frames = mutableListOf<CouchCommittedFrame>()
        for (event in events.asReversed()) {
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
                        decode(bodyBytes)
                        requireNotNull(CouchStoreFactory.documentFromBody(bodyBytes)) { "Invalid Couch body" }
                            .also(::validateReferences)
                    }
                    val frame = capture(CouchCommittedFrame(sequence, string(event, "id"), string(event, "rev"), deleted, doc))
                    frames.add(frame)
                    frameSequence = sequence
                }
                "local-put" -> {
                    require(event.keys == LOCAL_PUT_KEYS) { "Invalid local document schema" }
                    val sequence = integer(event, "sequence")
                    require(localSequence < Long.MAX_VALUE && sequence == localSequence + 1) { "Invalid local revision sequence" }
                    val id = string(event, "id")
                    val body = readVerified(requireNotNull(nullableCid(event, "body")))
                    val local = decode(body)
                    require(local["_id"] == "_local/$id" && local["_rev"] == "0-$sequence") { "Local document identity mismatch" }
                    locals[id] = body
                    localSequence = sequence
                }
                "local-delete" -> {
                    require(event.keys == LOCAL_DELETE_KEYS) { "Invalid local deletion schema" }
                    require(locals.remove(string(event, "id")) != null) { "Local deletion has no preceding document" }
                }
                else -> error("Unsupported Couch event")
            }
        }
        recovered = frames
        head = nullableCid(root, "head")
        count = size
    }

    private fun capture(frame: CouchCommittedFrame): CouchCommittedFrame {
        require(frame.docId.isNotEmpty() && frame.rev.isNotEmpty()) { "Couch frame identity is empty" }
        require(frame.deleted == (frame.doc == null)) { "Couch frame body does not match its deletion flag" }
        val doc = frame.doc?.let {
            require(it.id == frame.docId) { "Couch frame document identity mismatch" }
            require(it.fields.none { field -> field.name == "_id" } && it.fields.map { field -> field.name }.distinct().size == it.fields.size) {
                "Couch body contains an ambiguous field identity"
            }
            val bytes = CouchStoreFactory.canonicalBody(it)
            decode(bytes)
            requireNotNull(CouchStoreFactory.documentFromBody(bytes)).also { captured ->
                require(captured.id == frame.docId) { "Canonical Couch body identity mismatch" }
            }
        }
        return frame.copy(doc = doc)
    }

    private fun validateReferences(doc: Document) {
        val fields = doc.fields.associate { it.name to it.value }
        val reference = fields["contentId"] as? String ?: return
        if (!reference.startsWith("sha256:")) return
        val bytes = readVerified(ContentId(reference))
        fields["length"]?.let { length ->
            val size = when (length) {
                is Number -> exactInteger(length)
                is String -> length.toLongOrNull() ?: error("Invalid attachment length")
                else -> error("Invalid attachment length")
            }
            require(size == bytes.size.toLong()) { "Attachment length mismatch" }
        }
        if (fields["contentType"] == FileTreeManifest.CONTENT_TYPE) {
            val tree = FileTreeManifest.decode(bytes)
            for ((_, extent) in tree.entries.view) if (extent != null) {
                require(readVerified(extent.a).size.toLong() == extent.b) { "File-tree extent length mismatch" }
            }
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
