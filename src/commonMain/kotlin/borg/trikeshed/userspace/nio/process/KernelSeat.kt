@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.userspace.nio.process

import borg.trikeshed.kernel.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

typealias KernelCompletion = Join<KernelReceipt, (Throwable?) -> Unit>
typealias KernelRequest = suspend () -> KernelCompletion

/** Bounded userspace dispatch → parallel execution → joined completion, owned by one seat handle. */
class KernelSeat internal constructor(
    context: CoroutineContext,
    override val id: KernelId,
    kernel: KernelId,
    parent: KernelId?,
    capability: KernelCapability,
    parallelism: Int,
    capacity: Int,
    private val pointcut: KernelPointcut?,
) : AbstractCoroutineContextElement(Key), KernelLifecycle {
    companion object Key : CoroutineContext.Key<KernelSeat>

    init { require(parallelism > 0 && capacity > 0) }
    val execution = KernelExecution(kernel, parent, id, capability)
    private val supervisor = SupervisorJob(requireNotNull(context[Job]).also { it.ensureActive() })
    private val scope = CoroutineScope(context + supervisor + this + execution)
    private val accepting = AtomicBoolean(true)
    private val sequence = AtomicLong(0)
    private val input = Channel.buffered<KernelRequest>(capacity)
    private val admission = run {
        lateinit var permits: Channel<Unit>
        permits = Channel.buffered(capacity) { permits.kChannel.trySend(Unit) }
        repeat(capacity) { check(permits.kChannel.trySend(Unit).isSuccess) }
        permits
    }
    private val output = Channel.buffered<KernelCompletion>(capacity)
    private val closing = Mutex()
    private var closed = false
    private var closeFailure: Throwable? = null

    private val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable) {
            for ((receipt, settle) in output.kChannel) {
                val failure = try { pointcut?.complete(receipt); null } catch (cause: Throwable) { cause }
                settle(failure)
            }
        }
    }
    private val workers = (0 until parallelism).map {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                for (request in input.kChannel) {
                    admission.kChannel.trySend(Unit)
                    output.kChannel.send(request())
                }
            }
        }
    }

    suspend fun <T> submit(name: String, operation: suspend () -> T): Deferred<Result<T>> {
        currentCoroutineContext().ensureActive()
        require(name.isNotBlank() && name.length <= 256)
        check(accepting.load() && supervisor.isActive) { "Kernel seat is draining" }
        val turn = KernelTurn(execution.kernel, execution.parent, id, sequence.fetchAndAdd(1), name, execution.capability)
        val result = CompletableDeferred<Result<T>>()
        val request: KernelRequest = {
            val started = TimeSource.Monotonic.markNow()
            val value = try {
                pointcut?.dispatch(turn)
                Result.success(operation())
            } catch (cause: Throwable) { Result.failure(cause) }
            val receipt = KernelReceipt(turn,
                if (value.isSuccess) KernelOutcome.SUCCESS else KernelOutcome.FAILURE,
                started.elapsedNow().inWholeMilliseconds, value.exceptionOrNull()?.let { it::class.simpleName })
            receipt j { failure: Throwable? ->
                result.complete(if (failure == null) value else Result.failure(failure))
                Unit
            }
        }
        // A suspended work-channel send survives close(). Wait on permits instead, so drain
        // rejects unadmitted callers while retaining every item already in the work channel.
        admission.kChannel.receive()
        try {
            check(accepting.load() && supervisor.isActive) { "Kernel seat is draining" }
            input.kChannel.trySend(request).getOrThrow()
        } catch (failure: Throwable) {
            admission.kChannel.trySend(Unit)
            throw failure
        }
        return result
    }

    suspend fun <T> execute(name: String, operation: suspend () -> T): T = submit(name, operation).await().getOrThrow()

    /** Close admission, await every admitted result and its pointcut, then close the guest and owner. */
    override suspend fun drain(): Unit = withContext(NonCancellable) {
        accepting.store(false)
        admission.close()
        input.close()
        closing.withLock {
            if (!closed) {
                workers.joinAll()
                output.close()
                collector.join()
                try { pointcut?.close() } catch (cause: Throwable) { closeFailure = cause }
                supervisor.complete()
                supervisor.join()
                closed = true
            }
            closeFailure?.let { throw it }
        }
    }
}
