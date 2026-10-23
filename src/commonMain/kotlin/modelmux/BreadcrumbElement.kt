package modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.ColumnCursor
import borg.trikeshed.job.CasStore
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

typealias BreadcrumbTransaction<T> = Join<suspend BreadcrumbElement.() -> T, CompletableDeferred<T>>

/** One owner writes complete session states; null durably removes a session. */
interface BreadcrumbStore {
    suspend fun load(): Series<Join<String, String>>
    suspend fun write(id: String, state: String?)
}

class BreadcrumbCapacityException : IllegalArgumentException("breadcrumb context capacity reached")
class BreadcrumbContextNotFoundException : IllegalArgumentException("breadcrumb context not found")

/** Explicit projection carries its admitted instance through dispatch without recreating deleted sessions. */
class BreadcrumbAdmission(val owner: BreadcrumbElement, val id: String, val context: BreadcrumbContext) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BreadcrumbAdmission>
}

/** Session-local context transactions; admitted work drains before its caller's scope exits. */
class BreadcrumbElement(
    factory: (() -> BreadcrumbContext)? = null,
    val capacity: Int = 250,
    val store: BreadcrumbStore? = null,
    val cas: CasStore = CasStore.inMemory(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BreadcrumbElement>
    val factory: () -> BreadcrumbContext = factory ?: { BreadcrumbContext(index = BreadcrumbIndex(cas)) }

    private val contexts = mutableMapOf<String, BreadcrumbContext>()
    private val states = mutableMapOf<String, String>()
    private val mutex = Mutex()
    private var loaded = false

    /**
     * Every context's breadcrumbs as the table `breadcrumbs` ([BreadcrumbCursor.union]), published by each
     * transaction that may have written, so a catalog scan reads it without entering the transaction owner.
     */
    @kotlin.concurrent.Volatile
    var table: ColumnCursor = BreadcrumbCursor.union(emptyMap())

    init { require(capacity > 0) }

    suspend fun <T> transact(id: String, create: Boolean = true, operation: (BreadcrumbContext) -> T): T {
        require(id.isNotBlank() && id.length <= 128) { "breadcrumb context must contain 1-128 characters" }
        return transact {
            val existing = contexts[id]
            val context = existing ?: run {
                if (!create) throw BreadcrumbContextNotFoundException()
                if (contexts.size >= capacity) throw BreadcrumbCapacityException()
                factory()
            }
            val before = existing?.copy()
            try {
                val result = operation(context)
                val state = store?.let { context.state() }
                if (state != null && (existing == null || state != states[id])) {
                    store?.write(id, state)
                }
                if (existing == null) contexts[id] = context
                if (state != null) states[id] = state
                table = BreadcrumbCursor.union(contexts)
                result
            } catch (failure: Throwable) {
                if (before != null) context.restore(before)
                throw failure
            }
        }
    }

    /** Enumeration shares the transaction owner and never admits a new context. */
    suspend fun snapshot(): Series<Map<String, Any?>> = transact {
        contexts.entries.map { (id, context) -> context.summary(id) }.toSeries()
    }

    private suspend fun hydrate() {
        if (loaded) return
        val restored = mutableMapOf<String, BreadcrumbContext>()
        val encoded = mutableMapOf<String, String>()
        store?.load()?.view?.forEach { entry ->
            val id = entry.a
            require(id.isNotBlank() && id.length <= 128) { "breadcrumb context must contain 1-128 characters" }
            require(id !in restored) { "duplicate stored breadcrumb context" }
            require(restored.size < capacity) { "stored breadcrumb context capacity exceeded" }
            val template = factory()
            val context = BreadcrumbContext.restore(entry.b, template.clock, template.index.cas)
            restored[id] = context
            encoded[id] = context.state()
        }
        contexts.putAll(restored)
        states.putAll(encoded)
        loaded = true
        table = BreadcrumbCursor.union(contexts)
    }

    private suspend fun <T> transact(operation: suspend BreadcrumbElement.() -> T): T = supervisorScope {
        currentCoroutineContext().ensureActive()
        val requests = Channel.buffered<BreadcrumbTransaction<T>>(1)
        val result = CompletableDeferred<T>()
        val worker = launch(this@BreadcrumbElement, start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                for (request in requests.kChannel) {
                    try {
                        request.b.complete(mutex.withLock {
                            hydrate()
                            request.a(currentCoroutineContext()[Key]!!)
                        })
                    } catch (failure: Throwable) { request.b.completeExceptionally(failure) }
                }
            }
        }
        try {
            requests.kChannel.send(operation j result)
        } finally { requests.close() }
        try { result.await() } finally { withContext(NonCancellable) { worker.join() } }
    }

    suspend fun remove(id: String) {
        require(id.isNotBlank() && id.length <= 128) { "breadcrumb context must contain 1-128 characters" }
        transact {
            if (id !in contexts) throw BreadcrumbContextNotFoundException()
            store?.write(id, null)
            contexts.remove(id)
            states.remove(id)
            table = BreadcrumbCursor.union(contexts)
        }
    }
}
