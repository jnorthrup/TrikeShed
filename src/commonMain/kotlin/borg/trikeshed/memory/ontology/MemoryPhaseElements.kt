@file:Suppress("ObjectPropertyName")

package borg.trikeshed.memory.ontology

import kotlinx.coroutines.CompletableJob
import borg.trikeshed.context.Drain
import borg.trikeshed.cursor.query.Candidate
import borg.trikeshed.cursor.query.FusionMethod
import borg.trikeshed.cursor.query.HybridHit
import borg.trikeshed.cursor.query.fuseCandidates
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
 * Each element is a CoroutineContext.Element with a singleton Key companion so
 * that it composes into scopes via `+` and is looked up by reference equality —
 * never toString comparison.
 *
 * [drain] is graceful: it closes the fan-out channel, lets in-flight requests
 * finish and joins the supervisor — no hard child cancellation.
 */

// ── Retrieval phase element ─────────────────────────────────────────

/**
 * CCEK element for the Retrieval phase — "Calling Memory for the historical
 * information." Owns a fan-out channel that delivers retrieved memory traces
 * to subscribers. The phase anchor [Retrieval] is the marker this element
 * operationalizes. A retrieval asks every [candidates] source for its view of
 * the query (lexical postings, vector distances, …) and fuses them Evoke's way
 * ([fuseCandidates]); the trace carries the fused [HybridHit]s with each
 * source's rank and score kept.
 */
class MemoryRetrievalElement(
    parentJob: Job? = null,
    override val reactorContext: CoroutineContext = EmptyCoroutineContext,
    /** Each retrieval source's candidates for a query; none configured retrieves nothing. */
    private val candidates: suspend (query: Any) -> List<Candidate> = { emptyList() },
    private val k: Int = 10,
    private val method: FusionMethod = FusionMethod.RRF,
) : MemoryPhaseElement, Drain {

    companion object Key : CoroutineContext.Key<MemoryRetrievalElement>
    override val key: CoroutineContext.Key<MemoryRetrievalElement> = Key
    val supervisor: CompletableJob = SupervisorJob(parentJob)

    /** The phase anchor this element operationalizes. */
    val phase: SubstratePhase get() = Retrieval

    private val requestMutex: Mutex = Mutex()

    /** Buffered fan-out channel for delivering retrieval results to subscribers. */
    private val resultChannel: Channel<Join<SubstratePhase, Any>> = Channel(Channel.BUFFERED)

    /** Subscribers awaiting retrieval results. */
    private val subscriberScope: CoroutineScope = CoroutineScope(reactorContext + this + supervisor)

    /**
     * Submit a retrieval request. Returns a deferred that completes with the fused
     * hits once the trace is fanned out to subscribers. Refused after [drain].
     */
    suspend fun retrieve(query: Any): CompletableDeferred<Any> {
        check(!resultChannel.isClosedForSend) { "MemoryRetrievalElement is drained" }
        val deferred = CompletableDeferred<Any>()
        requestMutex.withLock {
            subscriberScope.launch {
                val hits: List<HybridHit> = fuseCandidates(candidates(query), k, method)
                // Fan-out the retrieval trace to all subscribers via the channel.
                val trace: Join<SubstratePhase, Any> = Retrieval j hits
                resultChannel.send(trace)
                deferred.complete(hits)
            }
        }
        return deferred
    }

    /** Subscribe to retrieval result fan-out. Returns the receive channel. */
    fun subscribe(): Channel<Join<SubstratePhase, Any>> = resultChannel

    /** Graceful drain: stop accepting new work, let in-flight requests finish. No hard child cancel. */
    override suspend fun drain() {
        // Close the channel to signal no more work; in-flight sends drain.
        resultChannel.close()
        // Let in-flight coroutines finish gracefully — complete supervisor, do not cancel.
        supervisor.complete()
        supervisor.join()
    }

    suspend fun close() = drain()
}

// ── Write-back phase element ────────────────────────────────────────

/**
 * CCEK element for the Write-back phase — "Based on the human feedback..."
 * Owns a fan-out channel that delivers write-back signals (preference logs,
 * episode records, weight updates) to subscribers. The phase anchor
 * [WriteBack] is the marker this element operationalizes.
 */
class MemoryWritebackElement(
    parentJob: Job? = null,
    override val reactorContext: CoroutineContext = EmptyCoroutineContext,
) : MemoryPhaseElement, Drain {

    companion object Key : CoroutineContext.Key<MemoryWritebackElement>
    override val key: CoroutineContext.Key<MemoryWritebackElement> = Key
    val supervisor: CompletableJob = SupervisorJob(parentJob)

    /** The phase anchor this element operationalizes. */
    val phase: SubstratePhase get() = WriteBack

    private val writeMutex: Mutex = Mutex()

    /** Buffered fan-out channel for delivering write-back signals to subscribers. */
    private val signalChannel: Channel<Join<SubstratePhase, Any>> = Channel(Channel.BUFFERED)

    /** Subscribers awaiting write-back signals. */
    private val subscriberScope: CoroutineScope = CoroutineScope(reactorContext + this + supervisor)

    /**
     * Submit a write-back signal (preference, episode, update). Returns a
     * deferred that completes when the signal is fanned out. Refused after [drain].
     */
    suspend fun writeBack(signal: Any): CompletableDeferred<Any> {
        check(!signalChannel.isClosedForSend) { "MemoryWritebackElement is drained" }
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

    /** Graceful drain: stop accepting new write-backs, let in-flight ones finish. No hard child cancel. */
    override suspend fun drain() {
        signalChannel.close()
        supervisor.complete()
        supervisor.join()
    }

    suspend fun close() = drain()
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
val phaseElementKeys: Series<CoroutineContext.Key<*>> = s_[
    MemoryRetrievalElement.Key,
    MemoryWritebackElement.Key,
]
