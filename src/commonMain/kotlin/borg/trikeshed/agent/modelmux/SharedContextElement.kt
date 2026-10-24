package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.ContextBlockExchange
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kernel.KernelCapability
import borg.trikeshed.kernel.KernelElement
import borg.trikeshed.kernel.KernelId
import borg.trikeshed.userspace.nio.process.KernelSeat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Shared work failed in a way that was not the waiter's own cancellation (the owner of the work was cancelled or timed out). */
class SharedWorkAbandoned(message: String, cause: Throwable?) : IllegalStateException(message, cause)

/** What a [Coalescer] has done: requests made, operations actually executed, and requests that joined one already running. */
data class CoalescerCounts(val requests: Int, val executed: Int, val joined: Int)

/**
 * Runs one operation per key at a time, however many callers ask for it, on a seat that owns the work.
 *
 * Why the seat and not the first caller: the operation belongs to everyone waiting for it, so cancelling the caller
 * that happened to arrive first must not cancel it for the rest. The seat runs it to completion regardless, bounds how
 * many run at once, and drains what it admitted.
 *
 * Failures are shared with the callers waiting at that moment and are never remembered: the next caller starts afresh.
 * A caller that is cancelled before its operation is admitted hands the key over: a waiter takes it up rather than
 * waiting on work that will never start. The lock guards the table and counters only and is never held across
 * admission, because admission can wait for workers that need the lock to finish.
 */
internal class Coalescer<K : Any, V>(private val seat: KernelSeat) {
    private sealed interface Outcome<out T> {
        class Done<T>(val result: Result<T>) : Outcome<T>
        object Abandoned : Outcome<Nothing>
    }

    private val lock = Mutex()
    private val inflight = HashMap<K, CompletableDeferred<Outcome<V>>>()
    private var requests = 0
    private var executed = 0
    private var joined = 0

    suspend fun counts(): CoalescerCounts = lock.withLock { CoalescerCounts(requests, executed, joined) }

    suspend fun run(key: K, name: String, operation: suspend () -> V): V {
        lock.withLock { requests++ }
        while (true) {
            val mine = CompletableDeferred<Outcome<V>>()
            val running = lock.withLock {
                val existing = inflight[key]
                if (existing != null) joined++ else inflight[key] = mine
                existing
            }
            val outcome = if (running != null) running.await() else lead(key, name, mine, operation)
            when (outcome) {
                is Outcome.Done -> return outcome.result.getOrThrow()
                Outcome.Abandoned -> continue
            }
        }
    }

    private suspend fun lead(key: K, name: String, mine: CompletableDeferred<Outcome<V>>, operation: suspend () -> V): Outcome<V> {
        try {
            seat.submit(name) {
                lock.withLock { executed++ }
                val result = try {
                    Result.success(operation())
                } catch (cancelled: CancellationException) {
                    Result.failure(SharedWorkAbandoned("shared work was cancelled: ${cancelled.message}", cancelled))
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
                withContext(NonCancellable) { lock.withLock { if (inflight[key] === mine) inflight.remove(key) } }
                mine.complete(Outcome.Done(result))
            }
        } catch (failure: Throwable) {
            // Never admitted: release the key so a waiter can take it, then surface the reason to this caller only.
            withContext(NonCancellable) {
                lock.withLock { if (inflight[key] === mine) inflight.remove(key) }
                mine.complete(Outcome.Abandoned)
            }
            throw failure
        }
        return mine.await()
    }
}

/** Peer fetches collapse to one transfer per block and ceiling; publishing and pinning are not reads and pass straight through. */
internal class CoalescingExchange(
    private val inner: ContextBlockExchange,
    private val fetches: Coalescer<String, ByteArray>,
) : ContextBlockExchange {
    /** The returned bytes are shared by every waiter and must be treated as immutable. */
    override suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray =
        fetches.run("$cid|$maxBytes", "fetch") { inner.fetch(cid, maxBytes) }

    override suspend fun publish(bytes: ByteArray): IpnsCid = inner.publish(bytes)
    override suspend fun pin(cid: IpnsCid) = inner.pin(cid)
}

/** The identity of a judge configuration. Two requests share a review only if they name the same one. */
class ReviewPolicy(val key: String, val judge: BreadcrumbOversight) {
    init {
        require(key.isNotBlank() && key.length <= 256) { "review key must be 1..256 characters" }
    }
}

/** A task's question about one pinned revision. [manifest] and [scope] are requests, not authority: the reader's policy decides. */
data class ContextRequest(
    val manifest: ContentId,
    val question: String,
    val scope: String,
    val budget: ProjectionBudget = ProjectionBudget(),
    val review: ReviewPolicy? = null,
) {
    init {
        require(question.isNotBlank() && question.length <= 4_096) { "question must be 1..4096 characters" }
        require(scope.isNotBlank() && scope.length <= 256) { "scope must be 1..256 characters" }
    }
}

/** An immutable answer: the verified snapshot, the lexical projection, the judge's receipt and the pruned view. */
class ContextAcquisition(
    val request: ContextRequest,
    val snapshot: ContextSnapshot,
    val projection: Projection,
    val receipt: OversightReceipt,
    val view: OversightedProjection,
)

/** Logical requests against physical work. A coalesced request is a request that did no work of its own. */
data class ElementCounters(
    val fetches: CoalescerCounts,
    val projections: CoalescerCounts,
    val projectionCacheHits: Int,
)

private data class Identity(
    val manifest: String,
    val scope: String,
    val question: String,
    val budget: ProjectionBudget,
    val policy: String,
    val review: String,
)

/**
 * The lifecycle owner of shared context for a process: one context element, bounded admission, coalesced work.
 *
 *  - Concurrent misses for one block become one physical peer fetch (by block identity); concurrent identical
 *    projections become one computation (by the FULL request identity: pinned revision, scope, question, budget,
 *    projector policy and review configuration). Bytes are shared across questions; projections are not.
 *  - A different revision is a different identity, so a projection can never outlive the source it was made from.
 *  - Successes are remembered in a bounded cache; failures and an UNAVAILABLE review never are.
 *  - The element owns a kernel with two seats (fetch, work) and drains them in dependency order: the work seat first,
 *    because its tasks fetch. It borrows its store, exchange and judge and closes none of them.
 *  - Once [drain] begins no new request is admitted; everything already admitted settles first.
 */
class SharedContextElement private constructor(
    /** Reads through the coalescing exchange, so every holder of this context benefits from the shared fetches. */
    val context: SharedContext,
    private val fetches: Coalescer<String, ByteArray>,
    private val projections: Coalescer<Identity, ContextAcquisition>,
    private val kernel: KernelElement,
    private val fetchSeat: KernelSeat,
    private val workSeat: KernelSeat,
    private val cacheLimit: Int,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SharedContextElement> {
        /**
         * Opens an element owned by the calling coroutine's Job. [parallelism] bounds simultaneous physical fetches and
         * projections; [capacity] bounds how many more may wait before callers are held back.
         */
        suspend fun open(
            id: String,
            cas: CasStore,
            exchange: ContextBlockExchange?,
            policy: ContextReadPolicy,
            parallelism: Int = 4,
            capacity: Int = 16,
            cacheLimit: Int = 64,
        ): SharedContextElement {
            require(cacheLimit > 0) { "cacheLimit must be positive" }
            val kernel = KernelElement(currentCoroutineContext(), KernelId(id))
            try {
                val fetchSeat = kernel.seat(KernelId("$id-fetch"), KernelCapability.COMPUTE, parallelism, capacity)
                val workSeat = kernel.seat(KernelId("$id-work"), KernelCapability.COMPUTE, parallelism, capacity)
                val fetches = Coalescer<String, ByteArray>(fetchSeat)
                val shared = SharedContext(cas, exchange?.let { CoalescingExchange(it, fetches) }, policy)
                return SharedContextElement(shared, fetches, Coalescer(workSeat), kernel, fetchSeat, workSeat, cacheLimit)
            } catch (failure: Throwable) {
                withContext(NonCancellable) { try { kernel.drain() } catch (_: Throwable) {} }
                throw failure
            }
        }
    }

    private val stateLock = Mutex()
    private var draining = false
    private val cache = LinkedHashMap<Identity, ContextAcquisition>()
    private var cacheHits = 0

    private suspend fun ensureOpen() {
        check(!stateLock.withLock { draining }) { "shared context element is draining" }
    }

    /** Resolves a pinned revision. Verification, scope and budgets are [SharedContext.acquire]'s; fetches are coalesced. */
    suspend fun acquire(manifest: ContentId, scope: String): ContextSnapshot {
        ensureOpen()
        return context.acquire(manifest, scope)
    }

    /** Answers a [ContextRequest]; identical requests, in flight or remembered, share one computation. */
    suspend fun project(request: ContextRequest): ContextAcquisition {
        ensureOpen()
        val identity = Identity(request.manifest.value, request.scope, request.question, request.budget,
            BreadcrumbProjector.POLICY, request.review?.key ?: "off")
        stateLock.withLock {
            val held = cache.remove(identity)
            if (held != null) {
                cache[identity] = held
                cacheHits++
                return held
            }
        }
        val computed = projections.run(identity, "project") {
            val snapshot = context.acquire(request.manifest, request.scope)
            val projection = BreadcrumbProjector.project(snapshot, request.question, request.budget, context.facets)
            val receipt = (request.review?.judge ?: NoOversight).review(request.question, projection)
            ContextAcquisition(request, snapshot, projection, receipt, applyOversight(projection, receipt))
        }
        // An unavailable judge may be back next time, so that answer is not remembered.
        if (computed.receipt.status != OversightStatus.UNAVAILABLE) {
            stateLock.withLock {
                cache.remove(identity)
                cache[identity] = computed
                while (cache.size > cacheLimit) cache.remove(cache.keys.first())
            }
        }
        return computed
    }

    suspend fun counters(): ElementCounters =
        ElementCounters(fetches.counts(), projections.counts(), stateLock.withLock { cacheHits })

    /** Stops admission, settles everything admitted, then closes what this element created. Safe to call again. */
    suspend fun drain(): Unit = withContext(NonCancellable) {
        stateLock.withLock { draining = true }
        var failure: Throwable? = null
        // Work first: its tasks fetch, so the fetch seat must outlive it.
        for (step in listOf<suspend () -> Unit>({ workSeat.drain() }, { fetchSeat.drain() }, { kernel.drain() })) {
            try { step() } catch (cause: Throwable) { if (failure == null) failure = cause else failure.addSuppressed(cause) }
        }
        failure?.let { throw it }
    }
}
