package borg.trikeshed.agent.modelmux

import borg.trikeshed.context.Drain
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.CoroutineContext

/** Explicit physical concurrency pool shared by the bindings assigned to its id. */
data class MuxPool(val id: String, val capacity: Int)

/** Bounded transport work for one reactor. Keys are logical binding identities. */
internal class MuxAdmission(
    parent: Job,
    workers: Int = 8,
    capacity: Int = 64,
    pools: Map<String, MuxPool> = emptyMap(),
) : Drain {
    private class Work(
        val binding: String,
        val context: CoroutineContext,
        val caller: Job?,
        val action: suspend () -> Any?,
        val result: CompletableDeferred<Result<Any?>>,
        val started: CompletableDeferred<Unit>,
    )
    private class Gate(val permit: Semaphore = Semaphore(1), var count: Int = 0)

    private val poolByBinding = pools.toMap()
    private val poolPermits: Map<String, Semaphore> = run {
        require(workers > 0 && capacity > 0 && capacity <= Int.MAX_VALUE - workers)
        val limits = mutableMapOf<String, Int>()
        for ((binding, pool) in poolByBinding) {
            require(binding.isNotBlank() && pool.id.isNotBlank() && pool.capacity > 0)
            val previous = limits.put(pool.id, pool.capacity)
            require(previous == null || previous == pool.capacity) { "Conflicting capacities for pool ${pool.id}" }
        }
        limits.mapValues { Semaphore(it.value) }
    }
    private val supervisor = SupervisorJob(parent)
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)
    // Bound both queued calls and the coroutines waiting for a binding. A binding
    // waiter must not occupy one of the independently shared transport permits.
    private val slots = Semaphore(capacity + workers)
    private val transports = Semaphore(workers)
    private val input = Channel.buffered<Work>(capacity) { work ->
        work.result.cancel(CancellationException("Model admission stopped"))
        slots.release()
    }
    private val gateMutex = Mutex()
    private val gates = mutableMapOf<String, Gate>()
    private val worker: Job

    init {
        worker = scope.launch {
            try {
                coroutineScope {
                    for (work in input.kChannel) launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            val gate = gateMutex.withLock { gates.getOrPut(work.binding) { Gate() }.also { it.count++ } }
                            try {
                                val result = runCatching {
                                    val parent = SupervisorJob(work.caller)
                                    val action = CoroutineScope(work.context.minusKey(Job) + parent).async {
                                        gate.permit.withPermit {
                                            val transport: suspend () -> Any? = { transports.withPermit {
                                                work.caller?.ensureActive()
                                                if (!work.started.complete(Unit)) throw CancellationException("Model admission revoked")
                                                work.caller?.ensureActive()
                                                work.action()
                                            } }
                                            val pool = poolByBinding[work.binding]?.let { poolPermits.getValue(it.id) }
                                            if (pool == null) transport() else pool.withPermit { transport() }
                                        }
                                    }
                                    // Join cleanup before releasing the binding, even for an
                                    // uncancellable submitted exchange.
                                    try { action.await() } finally {
                                        action.cancel()
                                        parent.complete()
                                        withContext(NonCancellable) { parent.join() }
                                    }
                                }
                                work.result.complete(result)
                            } finally {
                                withContext(NonCancellable) { gateMutex.withLock {
                                    if (--gate.count == 0) gates.remove(work.binding)
                                } }
                            }
                        } catch (failure: Throwable) {
                            work.result.complete(Result.failure(failure))
                        } finally {
                            slots.release()
                        }
                    }
                }
            } finally {
                // A parent failure stops admission and gives every queued caller a terminal result.
                input.close()
                while (true) {
                    val queued = input.kChannel.tryReceive().getOrNull() ?: break
                    queued.result.cancel(CancellationException("Model admission stopped"))
                    slots.release()
                }
            }
        }.also { job -> job.invokeOnCompletion { failure ->
                if (failure != null) {
                    input.close()
                    while (true) {
                        val queued = input.kChannel.tryReceive().getOrNull() ?: break
                        queued.result.cancel(CancellationException("Model admission stopped", failure))
                        slots.release()
                    }
                }
        } }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(binding: String, action: suspend () -> T): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        check(slots.tryAcquire()) { "Model admission queue full" }
        val result = CompletableDeferred<Result<Any?>>()
        val started = CompletableDeferred<Unit>()
        val sent = input.kChannel.trySend(Work(binding, context, context[Job], action, result, started))
        if (!sent.isSuccess) slots.release()
        check(sent.isSuccess) { if (sent.isClosed) "Model admission closed" else "Model admission queue full" }
        try {
            return result.await().getOrThrow() as T
        } catch (cancelled: CancellationException) {
            // Whichever side completes this deferred first owns the decision:
            // cancellation prevents the action, or an admitted action is joined.
            if (!started.completeExceptionally(cancelled)) withContext(NonCancellable) { result.join() }
            throw cancelled
        }
    }

    override suspend fun drain() = withContext(NonCancellable) {
        input.close()
        worker.join()
        supervisor.complete()
        supervisor.join()
    }
}
