package borg.trikeshed.couch.isam

import borg.trikeshed.couch.*
import borg.trikeshed.context.*
import borg.trikeshed.couch.persistence.CouchCommitStore
import borg.trikeshed.couch.persistence.asLocalBacking
import borg.trikeshed.collections.associative.toAny
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.isam.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.lib.get
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability

class ConfixIsamStoreBuilder {
    var dataFileLocation: String = ""
    var fileOps: FileOperations? = null
    var cas: CasStore? = null
    var operations: IsamOperations? = null
    var synchronization: Synchronization = Synchronization.MONITOR

    fun build(): ConfixIsamCursorBridge {
        require(dataFileLocation.isNotBlank()) { "dataFileLocation must be set" }
        return ConfixIsamCursorBridge(dataFileLocation, requireNotNull(fileOps), requireNotNull(cas), operations, synchronization)
    }
}

data class ConfixIsamRevision(
    val sequence: Long,
    val id: String,
    val rev: String,
    val deleted: Boolean,
    val syntax: Syntax?,
    val source: ContentId?,
    val dataFile: String?,
    val files: Map<String, ContentId>,
)

/**
 * Immutable dense parse geometry + exact CAS source, published through the existing Couch commit boundary.
 * Confix remains a tree of cursors; ISAM columns describe its tokens, not its application field schema.
 * This is an explicit conversion destination: opening it never edits another Couch store or its WAL.
 * NONE requires owner-serialized access and close; injected writers and shared CAS retain their own ownership policy.
 */
class ConfixIsamCursorBridge(
    dataFileLocation: String,
    private val files: FileOperations,
    private val cas: CasStore,
    operations: IsamOperations? = null,
    val synchronization: Synchronization = Synchronization.MONITOR,
) : AutoCloseable {
    private val gate = synchronization.lock()
    private val operations = operations ?: UringIsamOperations(leafBytes = 64 * 1024, synchronization = synchronization)
    private val directory = files.canonicalPath(dataFileLocation.also {
        require(it.isNotBlank()) { "dataFileLocation must be set" }
    }) + ".store"
    private val commits = CouchCommitStore(cas, StorageDurability.DURABLE, files, directory,
        requireCompleteHistory = true, synchronization = synchronization)
    private val history = linkedMapOf<Long, ConfixIsamRevision>()
    private val heads = linkedMapOf<String, ConfixIsamRevision>()
    private var lastSequence = -1L
    private var couchStore: CouchStore? = null
    private var couchCommit = false

    init {
        try { for (frame in commits.recoveredFrames.view) retain(decode(frame)) }
        catch (failure: Throwable) {
            try { commits.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    val revisions: Series<ConfixIsamRevision> get() = synchronize(gate) {
        commits.checkOpen()
        val snapshot = history.values.map { it.copy(files = it.files.toMap()) }.toTypedArray()
        snapshot.size j { snapshot[it] }
    }

    fun revision(id: String, rev: String? = null): ConfixIsamRevision? = synchronize(gate) {
        commits.checkOpen()
        val entry = if (rev == null) heads[id] else history.values.firstOrNull { it.id == id && it.rev == rev }
        entry?.copy(files = entry.files.toMap())
    }

    fun document(id: String, rev: String? = null): ConfixDoc? = synchronize(gate) {
        val entry = revision(id, rev) ?: return@synchronize null
        if (entry.deleted) return@synchronize null
        val bytes = readSource(requireNotNull(entry.source))
        val src: Series<Byte> = bytes.size j { bytes[it] }
        val index = if (entry.dataFile == null) {
            ConfixIsamIsomorphism.index(emptySeriesOf(), src, requireNotNull(entry.syntax))
        } else {
            verifyFiles(entry)
            val data = IsamDataFile(path(entry.dataFile), operations = operations)
            try {
                data.open()
                ConfixIsamIsomorphism.index(data, src, requireNotNull(entry.syntax))
            } finally { data.close() }
        }
        index j src
    }

    /** Idempotent replay requires every identity component to agree, not merely the sequence number. */
    fun put(sequence: Long, id: String, rev: String, source: ByteArray, syntax: Syntax,
            writer: IsamOperations = operations): ConfixIsamRevision = synchronize(gate) {
        commits.checkOpen()
        check(couchStore == null || couchCommit) { "Couch owns mutation of this dense store" }
        val bytes = source.copyOf()
        val cid = ContentId.of(bytes)
        replay(sequence, id, rev, cid, syntax)?.let { return@synchronize it }
        validate(sequence, id, rev)
        val doc = confixDoc(bytes, syntax)
        val geometry = ConfixIsamIsomorphism.geometry(doc.index)
        check(cas.put(bytes) == cid && readSource(cid).contentEquals(bytes)) { "Confix source publication failed" }
        val dataFile = if (geometry.size == 0) null else allocate(sequence)
        val artifacts = if (dataFile == null) emptyMap() else {
            val dataPath = path(dataFile)
            writer.write(geometry, dataPath, emptyMap(), false)
            // Data groups were forced by ISAM. Atomic metadata publication also fsyncs their shared directory.
            files.writeAtomically("$dataPath.meta", files.readAllBytes("$dataPath.meta"))
            artifactNames(dataFile).associateWith { ContentId.of(files.readAllBytes(path(it))) }
        }
        val entry = ConfixIsamRevision(sequence, id, rev, false, syntax, cid, dataFile, artifacts)
        // Verify physical projection before acknowledging it, including custom operation/codec owners.
        if (dataFile != null) {
            val data = IsamDataFile(path(dataFile), operations = writer)
            try {
                data.open()
                require(data.size == geometry.size) { "Confix geometry row count changed during publication" }
                for (i in 0 until data.size) {
                    val row = data[i]
                    val expected = geometry[i]
                    for (c in 0 until expected.size) require(row[c].a == expected[c].a) { "Confix geometry publication mismatch" }
                }
            } finally { data.close() }
        }
        commits.commit(encode(entry))
        retain(entry)
        entry
    }

    fun delete(sequence: Long, id: String, rev: String): ConfixIsamRevision = synchronize(gate) {
        commits.checkOpen()
        check(couchStore == null || couchCommit) { "Couch owns mutation of this dense store" }
        replay(sequence, id, rev, null, null)?.let { return@synchronize it }
        validate(sequence, id, rev)
        val entry = ConfixIsamRevision(sequence, id, rev, true, null, null, null, emptyMap())
        commits.commit(encode(entry))
        retain(entry)
        entry
    }

    /** Explicit Couch conversion preserves committed canonical bodies, sequences, revisions and tombstones. */
    fun importFrames(frames: Series<CouchCommittedFrame>, writer: IsamOperations = operations) {
        for (frame in frames.view) {
            require(frame.deleted == (frame.doc == null)) { "Invalid Couch frame body" }
            if (frame.deleted) delete(frame.sequence, frame.docId, frame.rev)
            else {
                val doc = requireNotNull(frame.doc)
                require(doc.id == frame.docId) { "Couch frame identity mismatch" }
                put(frame.sequence, frame.docId, frame.rev, CouchStoreFactory.canonicalBody(doc), Syntax.CBOR, writer)
            }
        }
    }

    /** Same-database offline conversion; unlike replication, this retains destination-local history. */
    internal fun importDatabase(source: CouchCommitStore) = synchronize(gate) {
        check(couchStore == null) { "Couch already owns this destination" }
        source.replayInto(commits) { importFrames(s_[it]) }
    }

    /** Compare a freshly reopened destination with the exclusively owned source. */
    internal fun verifyDatabase(source: CouchCommitStore): Int = synchronize(gate) {
        val frames = source.recoveredFrames
        val entries = revisions
        check(frames.size == entries.size) { "Couch revision count changed during conversion" }
        for (i in 0 until frames.size) {
            val frame = frames[i]
            val entry = entries[i]
            check(frame.sequence == entry.sequence && frame.docId == entry.id && frame.rev == entry.rev &&
                frame.deleted == entry.deleted) { "Couch revision identity changed during conversion" }
            frame.doc?.let {
                check(CouchStoreFactory.canonicalBody(it).contentEquals(requireNotNull(document(entry.id, entry.rev)).src.toArray())) {
                    "Couch body changed during conversion"
                }
            }
        }
        source.verifyLocals(commits)
    }

    /**
     * Couch protocol over this durable destination, not a migration of another database.
     * Existing entries must be canonical Couch CBOR bodies; replay uses their dense Confix geometry.
     * After binding, writes go through the shared Couch ingress, never the raw revision API.
     * Heads remain memory projections. Closing this owner releases the durable writer lease.
     */
    fun couch(name: String): Couch = synchronize(gate) {
        commits.checkOpen()
        val capture = commits::capture
        val store = couchStore ?: CouchStoreFactory.committed(cas, revisions α { entry ->
            val body = if (entry.deleted) null else {
                require(entry.syntax == Syntax.CBOR) { "Couch requires canonical CBOR revisions" }
                val doc = requireNotNull(document(entry.id, entry.rev))
                val value = CanonicalCbor.item(requireNotNull(doc.rootCell)).toAny()
                @Suppress("UNCHECKED_CAST")
                val fields = requireNotNull(value as? Map<String, Any?>) { "Couch body must be an object" }
                val body = requireNotNull(CouchStoreFactory.documentFromBody(fields)) { "Couch body requires _id" }
                require(CouchStoreFactory.canonicalBody(body).contentEquals(doc.src.toArray())) { "Noncanonical Couch body" }
                body
            }
            capture(CouchCommittedFrame(entry.sequence, entry.id, entry.rev, entry.deleted, body))
        }, synchronization) { frame -> synchronize(gate) {
            val captured = commits.capture(frame)
            couchCommit = true
            try { importFrames(s_[captured]) } finally { couchCommit = false }
            captured
        } }.also { couchStore = it }
        Couch(name, store, cas, commits.asLocalBacking())
    }

    /** Each row's final cell is a nested cursor, not a JSON string masquerading as packed ISAM bytes. */
    fun toCursor(): Cursor = synchronize(gate) {
        commits.checkOpen()
        val snapshot = heads.values.filter { !it.deleted }.toTypedArray()
        val metadata = s_[ColumnMeta("id", IOMemento.IoString), ColumnMeta("rev", IOMemento.IoString),
            ColumnMeta("sequence", IOMemento.IoLong), ColumnMeta("source", IOMemento.IoString),
            ColumnMeta("roots", IOMemento.IoArray)]
        snapshot.size j { i ->
            val entry = snapshot[i]
            val roots by lazy { requireNotNull(document(entry.id, entry.rev)).roots }
            metadata.size j { c ->
                val value: Any = when (c) {
                    0 -> entry.id; 1 -> entry.rev; 2 -> entry.sequence
                    3 -> requireNotNull(entry.source).value; else -> roots
                }
                value j metadata[c].leftIdentity
            }
        }
    }

    override fun close() = synchronize(gate) { commits.close() }

    private fun path(name: String) = files.resolvePath(directory, name)
    private fun artifactNames(name: String): Set<String> = setOf(name, "$name.meta") +
        ConfixIsamIsomorphism.schema.view.filter { it.groupName != "tag" }.map { getGroupFilename(name, it.groupName) }

    private fun allocate(sequence: Long): String {
        var attempt = 0
        while (true) {
            val name = "$sequence-$attempt.bin"
            if (artifactNames(name).none { files.exists(path(it)) }) return name
            check(attempt < Int.MAX_VALUE) { "Confix generation namespace exhausted" }
            attempt++
        }
    }

    private fun readSource(cid: ContentId): ByteArray = requireNotNull(cas.get(cid)) { "Missing Confix source $cid" }.also {
        require(ContentId.of(it) == cid) { "Corrupt Confix source $cid" }
    }

    private fun verifyFiles(entry: ConfixIsamRevision) {
        for ((name, cid) in entry.files) require(ContentId.of(files.readAllBytes(path(name))) == cid) {
            "Corrupt Confix geometry $name"
        }
    }

    private fun replay(sequence: Long, id: String, rev: String, source: ContentId?, syntax: Syntax?): ConfixIsamRevision? =
        history[sequence]?.let {
            require(it.id == id && it.rev == rev && it.source == source && it.syntax == syntax && it.deleted == (source == null)) {
                "Conflicting Confix replay at sequence $sequence"
            }
            it.copy(files = it.files.toMap())
        }

    private fun validate(sequence: Long, id: String, rev: String) {
        require(sequence >= 0 && sequence > lastSequence && id.isNotEmpty() && rev.isNotEmpty()) { "Invalid Confix revision identity" }
        require(history.values.none { it.id == id && it.rev == rev }) { "Duplicate Confix revision" }
    }

    private fun retain(entry: ConfixIsamRevision) {
        validate(entry.sequence, entry.id, entry.rev)
        val captured = entry.copy(files = entry.files.toMap())
        history[entry.sequence] = captured
        heads[entry.id] = captured
        lastSequence = entry.sequence
    }

    private fun encode(entry: ConfixIsamRevision): CouchCommittedFrame {
        val doc = if (entry.deleted) null else Document(entry.id, listOf(
            Field("format", "confix-isam-v1"), Field("syntax", requireNotNull(entry.syntax).name),
            Field("contentId", requireNotNull(entry.source).value), Field("data", entry.dataFile ?: ""),
            Field("files", entry.files.mapValues { it.value.value }),
        ))
        return CouchCommittedFrame(entry.sequence, entry.id, entry.rev, entry.deleted, doc)
    }

    private fun decode(frame: CouchCommittedFrame): ConfixIsamRevision {
        if (frame.deleted) return ConfixIsamRevision(frame.sequence, frame.docId, frame.rev, true, null, null, null, emptyMap())
        val fields = requireNotNull(frame.doc).fields.associate { it.name to it.value }
        require(fields.keys == setOf("format", "syntax", "contentId", "data", "files") && fields["format"] == "confix-isam-v1") {
            "Invalid Confix ISAM manifest"
        }
        val name = (fields["data"] as String).ifEmpty { null }
        require(name == null || Regex("[0-9]+-[0-9]+\\.bin").matches(name)) { "Invalid Confix data path" }
        val artifacts = (fields["files"] as Map<*, *>).entries.associate { (k, v) -> (k as String) to ContentId(v as String) }
        val expected: Set<String> = if (name == null) emptySet() else artifactNames(name)
        require(artifacts.keys == expected) { "Invalid Confix artifact set" }
        return ConfixIsamRevision(frame.sequence, frame.docId, frame.rev, false,
            Syntax.valueOf(fields["syntax"] as String), ContentId(fields["contentId"] as String), name, artifacts)
    }
}

fun confixIsamStore(block: ConfixIsamStoreBuilder.() -> Unit): ConfixIsamCursorBridge =
    ConfixIsamStoreBuilder().apply(block).build()
