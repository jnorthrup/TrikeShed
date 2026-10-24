package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

typealias BreadcrumbTransaction<T> = Join<suspend borg.trikeshed.agent.modelmux.BreadcrumbElement.() -> T, CompletableDeferred<T>>

/** One owner writes complete session states; null durably removes a session. */
interface BreadcrumbStore {
    suspend fun load(): Series<Join<String, String>>
    suspend fun write(id: String, state: String?)
}

class BreadcrumbCapacityException : IllegalArgumentException("breadcrumb context capacity reached")
class BreadcrumbContextNotFoundException : IllegalArgumentException("breadcrumb context not found")

/** Explicit projection carries its admitted instance through dispatch without recreating deleted sessions. */
class BreadcrumbAdmission(val owner: borg.trikeshed.agent.modelmux.BreadcrumbElement, val id: String, val context: borg.trikeshed.agent.modelmux.BreadcrumbContext) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BreadcrumbAdmission>
}

/** Session-local context transactions; admitted work drains before its caller's scope exits. */
class BreadcrumbElement(
    factory: (() -> borg.trikeshed.agent.modelmux.BreadcrumbContext)? = null,
    val capacity: Int = 250,
    val store: borg.trikeshed.agent.modelmux.BreadcrumbStore? = null,
    val cas: CasStore = CasStore.inMemory(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BreadcrumbElement>
    val factory: () -> borg.trikeshed.agent.modelmux.BreadcrumbContext = factory ?: {
        borg.trikeshed.agent.modelmux.BreadcrumbContext(
            index = borg.trikeshed.agent.modelmux.BreadcrumbIndex(cas)
        )
    }

    private val contexts = mutableMapOf<String, borg.trikeshed.agent.modelmux.BreadcrumbContext>()
    private val states = mutableMapOf<String, String>()
    private val mutex = Mutex()
    private var loaded = false

    init { require(capacity > 0) }

    suspend fun <T> transact(id: String, create: Boolean = true, operation: (borg.trikeshed.agent.modelmux.BreadcrumbContext) -> T): T {
        require(id.isNotBlank() && id.length <= 128) { "breadcrumb context must contain 1-128 characters" }
        return transact {
            val existing = contexts[id]
            val context = existing ?: run {
                if (!create) throw borg.trikeshed.agent.modelmux.BreadcrumbContextNotFoundException()
                if (contexts.size >= capacity) throw borg.trikeshed.agent.modelmux.BreadcrumbCapacityException()
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
        val restored = mutableMapOf<String, borg.trikeshed.agent.modelmux.BreadcrumbContext>()
        val encoded = mutableMapOf<String, String>()
        store?.load()?.view?.forEach { entry ->
            val id = entry.a
            require(id.isNotBlank() && id.length <= 128) { "breadcrumb context must contain 1-128 characters" }
            require(id !in restored) { "duplicate stored breadcrumb context" }
            require(restored.size < capacity) { "stored breadcrumb context capacity exceeded" }
            val template = factory()
            val context = borg.trikeshed.agent.modelmux.BreadcrumbContext.restore(entry.b, template.clock, template.index.cas)
            restored[id] = context
            encoded[id] = context.state()
        }
        contexts.putAll(restored)
        states.putAll(encoded)
        loaded = true
    }

    private suspend fun <T> transact(operation: suspend BreadcrumbElement.() -> T): T = supervisorScope {
        currentCoroutineContext().ensureActive()
        val requests = Channel.buffered<borg.trikeshed.agent.modelmux.BreadcrumbTransaction<T>>(1)
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
            if (id !in contexts) throw borg.trikeshed.agent.modelmux.BreadcrumbContextNotFoundException()
            store?.write(id, null)
            contexts.remove(id)
            states.remove(id)
        }
    }
}
