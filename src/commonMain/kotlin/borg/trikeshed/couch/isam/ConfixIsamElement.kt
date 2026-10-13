package borg.trikeshed.couch.isam

import borg.trikeshed.couch.*
import borg.trikeshed.isam.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

/** A bounded, owned projection lane. Couch stays authoritative, including its local checkpoints.
 * Reads never create a destination. Only an explicit import request advances the dense projection.
 */
class ConfixIsamElement(
    parent: CoroutineScope,
    private val source: CouchChangesProjection,
    private val location: String,
    private val files: FileOperations,
    private val cas: CasStore,
    private val operations: IsamOperations = UringIsamOperations(parent, leafBytes = 64 * 1024),
    capacity: Int = 8,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ConfixIsamElement>
    override val key: CoroutineContext.Key<*> get() = Key

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
    private var store: ConfixIsamCursorBridge? = null
    private val completion = CompletableDeferred<Result<Unit>>()
    private val consumer = CoroutineScope(parent.coroutineContext + this + job).launch {
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
            try { store?.close() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
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
            store = ConfixIsamCursorBridge(location, files, cas, operations)
        }
        return store
    }

    private suspend fun <T> submit(action: ConfixIsamElement.() -> T): T {
        val result = CompletableDeferred<T>()
        input.send(Request(action, result))
        return result.await()
    }

    suspend fun catalog(offset: Int = 0, limit: Int = 32): Map<String, Any?> = submit {
        require(offset >= 0 && limit in 1..128) { "Invalid revision window" }
        val revisions = open()?.revisions ?: emptySeriesOf()
        val frames = source.afterSequence(-1)
        val end = minOf(revisions.size.toLong(), offset.toLong() + limit).toInt()
        mapOf("available" to true, "indexedRevisions" to revisions.size, "sourceRevisions" to frames.size,
            "offset" to offset, "next" to if (end < revisions.size) end else null,
            "revisions" to (maxOf(0, end - offset) j { i: Int ->
                revisions[revisions.size - 1 - offset - i].descriptor()
            }).view.toList())
    }

    /** A point-in-time batch, not a background subscription or a database replacement. */
    suspend fun importCouch(limit: Int = 32): Map<String, Any?> = submit {
        require(limit in 1..128) { "Import limit must be in 1..128" }
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
        if (count > 0) requireNotNull(open(true)).importFrames(count j { frames[existing.size + it] })
        mapOf("imported" to count, "indexedRevisions" to existing.size + count,
            "sourceRevisions" to frames.size, "remaining" to frames.size - existing.size - count,
            "through" to if (frames.size == 0) null else frames[frames.size - 1].sequence,
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
