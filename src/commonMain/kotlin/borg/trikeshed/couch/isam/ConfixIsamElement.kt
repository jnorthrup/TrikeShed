package borg.trikeshed.couch.isam

import borg.trikeshed.couch.*
import borg.trikeshed.btrfs.VmWorldTeleport
import borg.trikeshed.cas.FileTreeManifest
import borg.trikeshed.collections.associative.toAny
import borg.trikeshed.isam.*
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

/** A bounded inspection lane, borrowing primary dense storage or owning a separate projection.
 * Projection reads never create a destination; only explicit imports advance it.
 * Primary inspection shares the database writer and never imports or closes that borrowed owner.
 */
class ConfixIsamElement(
    parent: CoroutineScope,
    private val source: CouchChangesProjection,
    location: String,
    private val files: FileOperations,
    private val cas: CasStore,
    private val dictionaryCodec: ((ByteArray) -> IsamLeafCodec)? = ::DictionaryLeafCodec,
    capacity: Int = 8,
    private val primary: CouchStorage? = null,
    val synchronization: borg.trikeshed.context.Synchronization = primary?.synchronization ?: borg.trikeshed.context.Synchronization.MONITOR,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ConfixIsamElement>
    override val key: CoroutineContext.Key<*> get() = Key
    private val location = files.canonicalPath(location)

    init {
        if (primary != null) require(primary.dense != null && primary.couch.cas === cas &&
            primary.couch.store.changes === source) { "Dense inspection must share the primary Couch and CAS" }
    }

    private class Request<T>(val action: ConfixIsamElement.() -> T, val result: CompletableDeferred<T>) {
        fun execute(owner: ConfixIsamElement) {
            try { result.complete(owner.action()) }
            catch (failure: Throwable) {
                result.completeExceptionally(failure)
                if (failure is CancellationException || failure !is Exception) throw failure
            }
        }
    }

    private val input = Channel<Request<*>>(capacity.also { require(it > 0) })
    val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + this + job)
    // Reads resolve the identity in each file, never the next batch's write selection.
    private val operations = UringIsamOperations(scope, leafBytes = 64 * 1024,
        leafCodec = dictionaryCodec?.let { CasLeafCodec(cas, it) } ?: ZstdLeafCodec, synchronization = synchronization)
    private var store: ConfixIsamCursorBridge? = primary?.dense
    private var fileTrees: ConfixIsamCursorBridge? = null
    private val completion = CompletableDeferred<Result<Unit>>()
    private val consumer = scope.launch {
        val owner = requireNotNull(currentCoroutineContext()[Key])
        var failure: Throwable? = null
        try { for (request in input) request.execute(owner) }
        catch (caught: Throwable) { failure = caught }
        finally {
            input.close(failure)
            while (true) {
                val request = input.tryReceive().getOrNull() ?: break
                request.result.completeExceptionally(failure ?: IllegalStateException("Confix index closed"))
            }
            for (storage in listOf(fileTrees, if (primary == null) store else null)) {
                try { storage?.close() } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure.addSuppressed(caught)
                }
            }
            completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
        }
    }.also { worker ->
        // Also settle requests when a cancelled parent prevents the worker from starting.
        worker.invokeOnCompletion { cause ->
            input.close(cause)
            while (true) {
                val request = input.tryReceive().getOrNull() ?: break
                request.result.completeExceptionally(cause ?: IllegalStateException("Confix index closed"))
            }
            completion.complete(cause?.let { Result.failure(it) } ?: Result.success(Unit))
        }
    }

    private fun open(create: Boolean = false): ConfixIsamCursorBridge? {
        if (store == null && (create || files.exists(files.resolvePath("$location.store", "HEAD")))) {
            store = ConfixIsamCursorBridge(location, files, cas, operations, synchronization)
        }
        return store
    }

    private suspend fun <T> submit(action: ConfixIsamElement.() -> T): T {
        val result = CompletableDeferred<T>()
        input.send(Request(action, result))
        return result.await()
    }

    private fun trees(create: Boolean = false): ConfixIsamCursorBridge? {
        val treeLocation = "$location.file-trees"
        if (fileTrees == null && (create || files.exists(files.resolvePath("$treeLocation.store", "HEAD")))) {
            fileTrees = ConfixIsamCursorBridge(treeLocation, files, cas, operations, synchronization)
        }
        return fileTrees
    }

    private fun writer(dictionary: ContentId?): IsamOperations = dictionary?.let { cid ->
        val codec = CasLeafCodec(cas, requireNotNull(dictionaryCodec) { "Zstd dictionary codec unavailable on this host" }, cid)
            .also { it.prepareWrite() }
        UringIsamOperations(scope, leafBytes = 64 * 1024, leafCodec = codec, synchronization = synchronization)
    } ?: operations

    /** Exact published metadata only: no live mount, file reads, restoration or recursive CAS traversal. */
    private fun treeReference(id: String, rev: String): Join<ContentId, Long>? {
        require(rev.isNotEmpty()) { "File-tree inspection requires an exact Couch revision" }
        val doc = requireNotNull(open()?.document(id, rev)) { "Publication revision not indexed" }
        fun field(name: String): Any? = doc.docAt(name)?.let { CanonicalCbor.item(it).toAny() }
        if (field("kind") != VmWorldTeleport.KIND || field("contentType") != FileTreeManifest.CONTENT_TYPE) return null
        require(field("guest") is String && id == VmWorldTeleport.PREFIX + field("guest")) { "Invalid VM-world publication identity" }
        val cid = ContentId(requireNotNull(field("contentId") as? String) { "Missing file-tree CID" })
        val length = requireNotNull(field("length") as? Long) { "Invalid file-tree length" }
        require(length > 0) { "Invalid file-tree length" }
        return cid j length
    }

    suspend fun fileTree(id: String, rev: String, read: Boolean = false): Join<Map<String, Any?>, ConfixDoc?>? = submit {
        val (cid, length) = treeReference(id, rev) ?: return@submit null
        val storage = trees()
        val indexed = storage?.revision(cid.value, cid.value) != null
        val doc = if (read && indexed) storage.document(cid.value, cid.value) else null
        if (doc != null) require(doc.src.size.toLong() == length) { "File-tree metadata length mismatch" }
        mapOf("cid" to cid.value, "bytes" to length, "byteLimit" to 1_048_576, "indexed" to indexed) j doc
    }

    /** Explicit content-addressed metadata projection; shared roots are indexed only once. */
    suspend fun importFileTree(id: String, rev: String, dictionary: ContentId? = null): Map<String, Any?> = submit {
        val (cid, length) = requireNotNull(treeReference(id, rev)) { "Revision is not a canonical VM-world publication" }
        require(length <= 1_048_576L) { "File-tree metadata exceeds the 1 MiB inspection limit" }
        val writer = writer(dictionary)
        val bytes = requireNotNull(cas.get(cid)) { "File-tree metadata missing: ${cid.value}" }
        require(bytes.size.toLong() == length && ContentId.of(bytes) == cid) { "File-tree metadata identity or length mismatch" }
        val prior = trees()?.document(cid.value, cid.value)
        if (prior != null) return@submit mapOf("imported" to 0, "cid" to cid.value, "source" to "unchanged")
        val doc = confixDoc(bytes, Syntax.CBOR)
        require(doc.index.facet(ConfixIndexK.Spans).size <= 65_536 &&
            (doc.index.facet(ConfixIndexK.Depths).view.maxOrNull() ?: 0) <= 64) { "File-tree metadata exceeds inspection limits" }
        FileTreeManifest.decode(bytes) // Validate canonical paths/extents, without opening their payloads.
        val destination = requireNotNull(trees(true))
        destination.put(destination.revisions.size.toLong(), cid.value, cid.value, bytes, Syntax.CBOR, writer)
        mapOf("imported" to 1, "cid" to cid.value, "source" to "unchanged",
            "compression" to mapOf("format" to "zstd", "dictionaryCid" to dictionary?.value))
    }

    suspend fun catalog(offset: Int = 0, limit: Int = 32): Map<String, Any?> = submit {
        require(offset >= 0 && limit in 1..128) { "Invalid revision window" }
        val revisions = open()?.revisions ?: emptySeriesOf()
        val frames = source.afterSequence(-1)
        val end = minOf(revisions.size.toLong(), offset.toLong() + limit).toInt()
        mapOf("available" to true, "role" to if (primary == null) "projection" else "primary",
            "importAvailable" to (primary == null),
            "indexedRevisions" to revisions.size, "sourceRevisions" to frames.size,
            "compression" to mapOf("format" to "zstd", "dictionaryAvailable" to (dictionaryCodec != null),
                "selection" to if (primary == null) "per-batch" else "storage-owner", "defaultDictionaryCid" to null),
            "offset" to offset, "next" to if (end < revisions.size) end else null,
            "revisions" to (maxOf(0, end - offset) j { i: Int ->
                revisions[revisions.size - 1 - offset - i].descriptor()
            }).view.toList())
    }

    /** A point-in-time batch, not a background subscription or a database replacement. */
    suspend fun importCouch(limit: Int = 32, dictionary: ContentId? = null): Map<String, Any?> = submit {
        require(primary == null) { "Primary Couch already publishes dense revisions; projection import is unavailable" }
        require(limit in 1..128) { "Import limit must be in 1..128" }
        val writer = writer(dictionary)
        val frames = source.afterSequence(-1)
        val existing = open()?.revisions ?: emptySeriesOf()
        require(existing.size <= frames.size) { "Couch history is shorter than the dense projection" }
        // Detect a replaced/truncated source before advancing this destination, even if sequences were reused.
        for (i in 0 until existing.size) {
            val entry = existing[i]
            val frame = frames[i]
            require(entry.sequence == frame.sequence && entry.id == frame.docId && entry.rev == frame.rev &&
                entry.deleted == frame.deleted && entry.source == frame.doc?.let { ContentId.of(CouchStoreFactory.canonicalBody(it)) }) {
                "Couch history differs from the dense projection at ${entry.sequence}"
            }
        }
        val count = minOf(limit, frames.size - existing.size)
        if (count > 0) requireNotNull(open(true)).importFrames(count j { frames[existing.size + it] }, writer)
        val indexed = existing.size + count
        mapOf("imported" to count, "indexedRevisions" to indexed,
            "compression" to mapOf("format" to "zstd", "dictionaryCid" to dictionary?.value),
            "sourceRevisions" to frames.size, "remaining" to frames.size - indexed,
            "through" to if (indexed == 0) null else frames[indexed - 1].sequence,
            "checkpoints" to "unchanged", "source" to "unchanged")
    }

    suspend fun document(id: String, rev: String? = null): Join<ConfixIsamRevision, ConfixDoc?>? = submit {
        require(id.isNotEmpty()) { "Document id is empty" }
        val storage = open() ?: return@submit null
        val entry = storage.revision(id, rev) ?: return@submit null
        entry j storage.document(entry.id, entry.rev)
    }

    suspend fun drain() = withContext(NonCancellable) {
        input.close()
        consumer.join()
        job.complete()
        job.join()
        completion.await().getOrThrow()
    }
}

fun ConfixIsamRevision.descriptor(): Map<String, Any?> = mapOf(
    "id" to id, "rev" to rev, "sequence" to sequence, "deleted" to deleted,
    "syntax" to syntax?.name, "sourceCid" to source?.value,
)
