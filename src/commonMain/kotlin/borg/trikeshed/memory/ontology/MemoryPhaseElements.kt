@file:Suppress("ObjectPropertyName")

package borg.trikeshed.memory.ontology

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.ElementState.CREATED
import borg.trikeshed.context.ElementState.OPEN
import borg.trikeshed.context.ElementState.ACTIVE
import borg.trikeshed.context.ElementState.DRAINING
import borg.trikeshed.context.ElementState.CLOSED
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.α
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * CCEK elements for the two substrate phases (VAL-TAX-CCEK).
 *
 * Each element extends [AsyncContextElement] (CoroutineContext.Element) with a
 * singleton [AsyncContextKey] companion so that it composes into scopes via
 * `+` and is looked up by reference equality — never toString comparison.
 *
 * Lifecycle honored: CREATED → OPEN → ACTIVE → DRAINING → CLOSED.
 * [drain] is graceful: it signals no-more-work, lets in-flight requests
 * finish, then transitions to CLOSED — no hard child cancellation.
 */

// ── Retrieval phase element ─────────────────────────────────────────

/**
 * CCEK element for the Retrieval phase — "Calling Memory for the historical
 * information." Owns a fan-out channel that delivers retrieved memory traces
 * to subscribers. The phase anchor [Retrieval] is the marker this element
 * operationalizes.
 *
 * Lifecycle:
 *   CREATED  ⇒ element exists, not wired.
 *   OPEN     ⇒ channel open, accepting subscribe/register.
 *   ACTIVE   ⇒ serving retrieval requests; fan-out delivers to subscribers.
 *   DRAINING ⇒ no new work accepted; in-flight requests complete gracefully.
 *   CLOSED   ⇒ channel closed, supervisor completed, resources released.
 */
class MemoryRetrievalElement(
    parentJob: Job? = null,
    override val reactorContext: CoroutineContext = EmptyCoroutineContext,
) : AsyncContextElement(CREATED, parentJob), MemoryPhaseElement {

    companion object Key : AsyncContextKey<MemoryRetrievalElement>()
    override val key: AsyncContextKey<MemoryRetrievalElement> = Key

    /** The phase anchor this element operationalizes. */
    val phase: SubstratePhase get() = Retrieval

    private val requestMutex: Mutex = Mutex()

    /** Buffered fan-out channel for delivering retrieval results to subscribers. */
    private val resultChannel: Channel<Join<SubstratePhase, Any>> = Channel(Channel.BUFFERED)

    /** Subscribers awaiting retrieval results. */
    private val subscriberScope: CoroutineScope = CoroutineScope(supervisor + reactorContext)

    override suspend fun open() {
        if (state == CREATED) state = OPEN
    }

    /** Promote OPEN → ACTIVE. Idempotent if already ACTIVE or later. */
    suspend fun activate() {
        if (state == OPEN) state = ACTIVE
    }

    /**
     * Submit a retrieval request. Returns a deferred that completes when the
     * result is fanned out to subscribers. Requires ACTIVE state.
     */
    suspend fun retrieve(query: Any): CompletableDeferred<Any> {
        check(state == ACTIVE) { "MemoryRetrievalElement must be ACTIVE (was $state)" }
        val deferred = CompletableDeferred<Any>()
        requestMutex.withLock {
            subscriberScope.launch {
                // Fan-out the retrieval trace to all subscribers via the channel.
                val trace: Join<SubstratePhase, Any> = Retrieval j query
                resultChannel.send(trace)
                deferred.complete(query)
            }
        }
        return deferred
    }

    /** Subscribe to retrieval result fan-out. Returns the receive channel. */
    fun subscribe(): Channel<Join<SubstratePhase, Any>> = resultChannel

    /**
     * Graceful drain — DRAINING: stop accepting new work, let in-flight
     * requests finish, then CLOSED. No hard child cancel.
     */
    override suspend fun drain() {
        if (state.isAtLeast(OPEN) && state.isLessThan(DRAINING)) {
            state = DRAINING
            // Close the channel to signal no more work; in-flight sends drain.
            resultChannel.close()
            // Let in-flight coroutines finish gracefully — complete supervisor, do not cancel.
            supervisor.complete()
            supervisor.join()
            state = CLOSED
        }
    }

    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            resultChannel.close()
            supervisor.complete()
            supervisor.join()
            state = CLOSED
        }
    }
}

// ── Write-back phase element ────────────────────────────────────────

/**
 * CCEK element for the Write-back phase — "Based on the human feedback..."
 * Owns a fan-out channel that delivers write-back signals (preference logs,
 * episode records, weight updates) to subscribers. The phase anchor
 * [WriteBack] is the marker this element operationalizes.
 *
 * Lifecycle:
 *   CREATED  ⇒ element exists, not wired.
 *   OPEN     ⇒ channel open, accepting subscribe/register.
 *   ACTIVE   ⇒ serving write-back requests; fan-out delivers to subscribers.
 *   DRAINING ⇒ no new work accepted; in-flight write-backs complete gracefully.
 *   CLOSED   ⇒ channel closed, supervisor completed, resources released.
 */
class MemoryWritebackElement(
    parentJob: Job? = null,
    override val reactorContext: CoroutineContext = EmptyCoroutineContext,
) : AsyncContextElement(CREATED, parentJob), MemoryPhaseElement {

    companion object Key : AsyncContextKey<MemoryWritebackElement>()
    override val key: AsyncContextKey<MemoryWritebackElement> = Key

    /** The phase anchor this element operationalizes. */
    val phase: SubstratePhase get() = WriteBack

    private val writeMutex: Mutex = Mutex()

    /** Buffered fan-out channel for delivering write-back signals to subscribers. */
    private val signalChannel: Channel<Join<SubstratePhase, Any>> = Channel(Channel.BUFFERED)

    /** Subscribers awaiting write-back signals. */
    private val subscriberScope: CoroutineScope = CoroutineScope(supervisor + reactorContext)

    override suspend fun open() {
        if (state == CREATED) state = OPEN
    }

    /** Promote OPEN → ACTIVE. Idempotent if already ACTIVE or later. */
    suspend fun activate() {
        if (state == OPEN) state = ACTIVE
    }

    /**
     * Submit a write-back signal (preference, episode, update). Returns a
     * deferred that completes when the signal is fanned out. Requires ACTIVE.
     */
    suspend fun writeBack(signal: Any): CompletableDeferred<Any> {
        check(state == ACTIVE) { "MemoryWritebackElement must be ACTIVE (was $state)" }
        val deferred = CompletableDeferred<Any>()
        writeMutex.withLock {
            subscriberScope.launch {
                val trace: Join<SubstratePhase, Any> = WriteBack j signal
                signalChannel.send(trace)
                deferred.complete(signal)
            }
        }
        return deferred
    }

    /** Subscribe to write-back signal fan-out. Returns the receive channel. */
    fun subscribe(): Channel<Join<SubstratePhase, Any>> = signalChannel

    /**
     * Graceful drain — DRAINING: stop accepting new work, let in-flight
     * write-backs finish, then CLOSED. No hard child cancel.
     */
    override suspend fun drain() {
        if (state.isAtLeast(OPEN) && state.isLessThan(DRAINING)) {
            state = DRAINING
            signalChannel.close()
            supervisor.complete()
            supervisor.join()
            state = CLOSED
        }
    }

    override suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) state = DRAINING
            signalChannel.close()
            supervisor.complete()
            supervisor.join()
            state = CLOSED
        }
    }
}

// ── Shared phase-element interface ──────────────────────────────────

/**
 * Marker interface for CCEK elements that operationalize a [SubstratePhase].
 * Both [MemoryRetrievalElement] and [MemoryWritebackElement] implement it,
 * letting a scope carry either or both via `+` composition.
 */
interface MemoryPhaseElement : CoroutineContext.Element {
    /** Reactor context propagated into the element's internal scope. */
    val reactorContext: CoroutineContext
}

/**
 * Series of the two phase-element companion keys, for lookup by reference.
 * Navigated with [α], never toString comparison.
 */
val phaseElementKeys: Series<AsyncContextKey<*>> = s_[
    MemoryRetrievalElement.Key,
    MemoryWritebackElement.Key,
]
