@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.userspace.nio.process

import borg.trikeshed.lib.*
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

typealias ProcessCallReply = Join<Int, Result<Teleported>>

class ProcessCallTimeout(val operation: String, val timeoutMillis: Long) :
    IllegalStateException("process call '$operation' exceeded ${timeoutMillis}ms")

/** One direction of process calls: bounded admission, independent deadlines, and joined reply fan-in. */
class ProcessCalls(
    context: CoroutineContext,
    concurrency: Int = 4,
    capacity: Int = 16,
    private val clock: TimeSource = TimeSource.Monotonic,
    private val reply: suspend (ProcessCallReply) -> Unit,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ProcessCalls>
    override val key: CoroutineContext.Key<*> get() = Key

    init { require(concurrency > 0 && capacity > 0 && capacity <= Int.MAX_VALUE - concurrency) }
    private class Call(val id: Int, val name: String, val timeoutMillis: Long?, val admitted: TimeMark,
                       val operation: suspend () -> Teleported) {
        val result = CompletableDeferred<ProcessCallReply>()
    }
    private sealed interface Admission {
        data object Open : Admission
        data object Draining : Admission
        data object Closed : Admission
        class Final(val call: Call) : Admission
    }
    private val admission = AtomicReference<Admission>(Admission.Open)
    private val supervisor = SupervisorJob(context[Job])
    private val scope = CoroutineScope(context + supervisor + this)
    private val slots = Semaphore(concurrency + capacity)
    private val execution = Semaphore(concurrency)
    private val completion = CompletableDeferred<Result<Unit>>()
    private val input = Channel<Call>(concurrency + capacity, onUndeliveredElement = {
        it.result.completeExceptionally(CancellationException("Process calls closed before dispatch"))
        slots.release()
    })
    private val output = Channel<ProcessCallReply>(concurrency + capacity)
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var failure: Throwable? = null
        try {
            coroutineScope {
                val writer = launch { for (value in output) reply(value) }
                try {
                    coroutineScope {
                        for (call in input) {
                            // Register permit waiters in admission order, including serial guest operations.
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                val value = execute(call)
                                call.result.complete(value)
                                output.send(value)
                            }.invokeOnCompletion { cause ->
                                if (cause != null) call.result.completeExceptionally(cause)
                                slots.release()
                            }
                        }
                    }
                    (admission.load() as? Admission.Final)?.call?.let { call ->
                        val value = execute(call)
                        call.result.complete(value)
                        output.send(value)
                    }
                } finally { output.close() }
                writer.join()
            }
        } catch (cause: Throwable) {
            failure = cause
        } finally {
            val final = admission.exchange(Admission.Closed) as? Admission.Final
            val stopped = CancellationException("Process calls closed", failure)
            input.cancel(stopped)
            output.cancel()
            final?.call?.result?.completeExceptionally(failure ?: stopped)
            completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
        }
    }.also { it.invokeOnCompletion { supervisor.complete() } }

    /** Never suspend the process reader. A null result means no effect was admitted. */
    fun trySubmit(id: Int, name: String, timeoutMillis: Long?, operation: suspend () -> Teleported): Deferred<ProcessCallReply>? {
        require(timeoutMillis == null || timeoutMillis > 0)
        if (admission.load() !== Admission.Open || !supervisor.isActive || !slots.tryAcquire()) return null
        val call = Call(id, name, timeoutMillis, clock.markNow(), operation)
        if (input.trySend(call).isSuccess) return call.result
        slots.release()
        return null
    }

    /** Reserve one final operation after all admitted calls, even when ordinary capacity is full. */
    fun finish(id: Int, name: String, operation: suspend () -> Teleported): Deferred<ProcessCallReply>? {
        val call = Call(id, name, null, clock.markNow(), operation)
        if (!supervisor.isActive || !admission.compareAndSet(Admission.Open, Admission.Final(call))) return null
        input.close()
        return call.result
    }

    private suspend fun execute(call: Call): ProcessCallReply = call.id j try {
        suspend fun invoke() = execution.withPermit {
            currentCoroutineContext().ensureActive()
            call.operation()
        }
        val value = if (call.timeoutMillis == null) invoke()
        else {
            val remaining = call.timeoutMillis - call.admitted.elapsedNow().inWholeMilliseconds
            // Only this lease's timeout becomes null; a nested deadline retains its own failure.
            withTimeoutOrNull(remaining) { invoke() }
        }
        if (value == null) Result.failure(ProcessCallTimeout(call.name, checkNotNull(call.timeoutMillis)))
        else Result.success(value)
    } catch (cause: Throwable) {
        currentCoroutineContext().ensureActive()
        Result.failure(cause)
    }

    /** Close admission, finish callbacks and their cleanup, deliver replies, and join every owned child. */
    suspend fun drain(): Unit = withContext(NonCancellable) {
        if (admission.compareAndSet(Admission.Open, Admission.Draining)) input.close()
        worker.join()
        supervisor.join()
        completion.await().getOrThrow()
    }
}
