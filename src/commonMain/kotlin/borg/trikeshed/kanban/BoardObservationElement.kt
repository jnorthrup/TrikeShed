@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package borg.trikeshed.kanban

import borg.trikeshed.context.*
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.*
import kotlin.coroutines.CoroutineContext

/** Serial board projection and clock fan-in; a watermark follows every completed projection. */
class BoardObservationElement(
    private val store: BoardStoreElement,
    private val seed: suspend () -> Unit,
    private val committed: suspend (BoardCommitted) -> Unit,
    private val tick: suspend () -> Unit,
    private val intervalMs: Long = 60_000,
    capacity: Int = 64,
    parentJob: Job? = null,
) : CoroutineContext.Element, Drain {
    init { require(intervalMs > 0 && capacity > 0) }
    companion object Key : CoroutineContext.Key<BoardObservationElement>
    override val key: CoroutineContext.Key<*> get() = Key
    val supervisor: CompletableJob = SupervisorJob(parentJob)

    private data class Progress(val sequence: Long = 0, val failure: Throwable? = null, val closed: Boolean = false)
    private val progress = MutableStateFlow(Progress())
    private val events = Channel.buffered<BoardCommitted>(capacity)
    private val ticks = Channel.buffered<CompletableDeferred<Unit>>(1)
    private val stop = CompletableDeferred<Unit>()
    private val initialized = CompletableDeferred<Unit>()
    private val subscribed = CompletableDeferred<Unit>()
    private var collector: Job? = null
    private var consumer: Job? = null
    private var ticker: Job? = null

    private fun failed(cause: Throwable) {
        progress.update { before -> if (before.failure == null) before.copy(failure = cause) else before }
    }

    suspend fun open() {
        if (collector != null || stop.isCompleted) return
        supervisor.ensureActive()
        val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + supervisor + this)
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                store.committed.onSubscription { subscribed.complete(Unit) }.collect { events.kChannel.send(it) }
            } catch (cause: Throwable) {
                // Drain closes events before it detaches this reader.
                if (!events.isClosed()) {
                    failed(cause)
                    events.kChannel.close(cause)
                    subscribed.completeExceptionally(cause)
                }
            }
        }
        subscribed.await()
        val baseline = store.lastSequence
        consumer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var activeTick: CompletableDeferred<Unit>? = null
            try {
                seed()
                progress.update { it.copy(sequence = maxOf(it.sequence, baseline)) }
                initialized.complete(Unit)
                var eventOpen = true
                var tickOpen = true
                while (eventOpen || tickOpen) select<Unit> {
                    if (eventOpen) events.kChannel.onReceiveCatching { result ->
                        val event = result.getOrNull()
                        if (event == null) {
                            result.exceptionOrNull()?.let { throw it }
                            eventOpen = false
                        } else {
                            committed(event)
                            progress.update { it.copy(sequence = maxOf(it.sequence, event.sequence)) }
                        }
                    }
                    if (tickOpen) ticks.kChannel.onReceiveCatching { result ->
                        val reply = result.getOrNull()
                        if (reply == null) {
                            result.exceptionOrNull()?.let { throw it }
                            tickOpen = false
                        } else {
                            activeTick = reply
                            tick()
                            reply.complete(Unit)
                            activeTick = null
                        }
                    }
                }
            } catch (cause: Throwable) {
                failed(cause)
                initialized.completeExceptionally(cause)
                activeTick?.completeExceptionally(cause)
            } finally {
                val cause = progress.value.failure ?: IllegalStateException("Board observation closed")
                events.kChannel.close()
                ticks.kChannel.close()
                while (true) (ticks.kChannel.tryReceive().getOrNull() ?: break).completeExceptionally(cause)
                progress.update { it.copy(closed = true) }
            }
        }
        try {
            initialized.await()
            ticker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    while (!stop.isCompleted) {
                        val reply = CompletableDeferred<Unit>()
                        ticks.kChannel.send(reply)
                        reply.await()
                        if (!select<Boolean> {
                            stop.onAwait { false }
                            onTimeout(intervalMs) { true }
                        }) break
                    }
                } catch (cause: Throwable) { failed(cause) }
            }
        } catch (cause: Throwable) {
            // The caller performs the same joined drain used after a later processing failure.
            failed(cause)
            throw cause
        }
    }

    /** Finish the admitted tick, then stop producing clock work without cancelling its body. */
    suspend fun stopTicker() = withContext(NonCancellable) {
        stop.complete(Unit)
        ticker?.join()
    }

    /** A failed projection cannot leave an apparently successful or permanently waiting barrier. */
    suspend fun awaitSequence(sequence: Long) {
        val observed = progress.first { it.sequence >= sequence || it.failure != null || it.closed }
        observed.failure?.let { throw it }
        check(observed.sequence >= sequence) { "Board observation closed before sequence $sequence" }
    }

    override suspend fun drain(): Unit = withContext(NonCancellable) {
        var failure = progress.value.failure
        suspend fun release(action: suspend () -> Unit) {
            try { action() }
            catch (cause: Throwable) {
                val first = failure
                if (first == null) failure = cause else if (first !== cause) first.addSuppressed(cause)
            }
        }
        if (collector == null) {
            initialized.complete(Unit)
            subscribed.complete(Unit)
        }
        release { stopTicker() }
        if (consumer != null) release { awaitSequence(store.lastSequence) }
        // The watermark joins all delivered work before unsubscribing the idle SharedFlow reader.
        events.close()
        ticks.close()
        release { collector?.cancelAndJoin() }
        release { consumer?.join() }
        supervisor.complete()
        release { supervisor.join() }
        progress.value.failure?.let { if (failure == null) failure = it }
        failure?.let { throw it }
    }

    suspend fun close() = drain()
}
