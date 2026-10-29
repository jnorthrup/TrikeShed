package borg.trikeshed.kanban

import borg.trikeshed.context.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

typealias BoardAction = suspend () -> Unit

/** Board effects transfer through a bounded stage; producer and action completion remain owned. */
@OptIn(ExperimentalAtomicApi::class)
class BoardActionElement(
    parentJob: Job? = null,
    capacity: Int = 64,
    private val workers: Int = 3,
    private val dependency: suspend () -> Boolean = { false },
) : CoroutineContext.Element, Drain {
    init {
        require(capacity > 0)
        require(workers > 0)
    }
    companion object Key : CoroutineContext.Key<BoardActionElement>
    override val key: CoroutineContext.Key<*> get() = Key
    val supervisor: CompletableJob = SupervisorJob(parentJob)
    class AdmissionFailure(message: String) : IllegalStateException(message)

    private class Admission(val action: BoardAction, val reservation: CompletableJob) {
        val completion = CompletableDeferred<Unit>()
        // Producer/pending, executing, settled. A cancelled send cannot settle a running action.
        val phase = AtomicInt(0)
    }

    private class Preparation(val owner: BoardActionElement, val reservation: Job) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Preparation>
    }

    private class Execution(val owner: BoardActionElement) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Execution>
        /** Completes when the action returns; a retained carrier then holds no authority. */
        val active: CompletableJob = Job()
    }

    /** Completes at [stopPreparations]; afterwards only executing actions and the dependency prepare. */
    private val preparing: CompletableJob = Job()
    private val actions = SupervisorJob(supervisor)
    private val preparations = SupervisorJob(supervisor)
    private val input = Channel.buffered<Admission>(capacity)
    private val failures = MutableStateFlow<Series<Throwable>>(emptySeriesOf())
    /** Published once by the [open] that launches the workers, children of [supervisor]. */
    private val scope = AtomicReference<CoroutineScope?>(null)

    suspend fun open() {
        if (scope.load() != null || input.isClosed()) return
        supervisor.ensureActive()
        val opened = CoroutineScope(currentCoroutineContext().minusKey(Job) + supervisor + this)
        if (!scope.compareAndSet(null, opened)) return
        repeat(workers) { index ->
            opened.launch(CoroutineName("board-actions:$index"), start = CoroutineStart.UNDISPATCHED) {
                var stopped: Throwable? = null
                try {
                    for (admission in input.kChannel) {
                        if (!admission.phase.compareAndSet(0, 1)) continue
                        val execution = Execution(this@BoardActionElement)
                        val result = try {
                            withContext(execution) {
                                try { admission.action(); Result.success(Unit) }
                                catch (failure: Throwable) { Result.failure(failure) }
                            }
                        } catch (failure: Throwable) { Result.failure(failure) }
                        finally { execution.active.complete() }
                        settle(admission, result, executing = true)
                        currentCoroutineContext().ensureActive()
                    }
                } catch (failure: Throwable) {
                    stopped = failure
                    record(failure)
                } finally {
                    if (stopped != null) input.kChannel.close(stopped)
                    while (true) {
                        val pending = input.kChannel.tryReceive().getOrNull() ?: break
                        settle(pending, Result.failure(stopped ?: CancellationException("Board action worker closed")), executing = false)
                    }
                }
            }
        }
    }

    /** Null refuses admission. An accepted result settles only after the actual action finishes. */
    fun submit(action: BoardAction): Deferred<Unit>? {
        // Reserve before checking admission: stop/drain cannot miss a producer awaiting launch.
        val reservation = Job(actions)
        val owner = scope.load()
        if (owner == null || !supervisor.isActive || input.isClosed()) {
            reservation.complete()
            return null
        }
        val admission = Admission(action, reservation)
        owner.launch(reservation, start = CoroutineStart.UNDISPATCHED) {
            try { input.kChannel.send(admission) }
            catch (failure: Throwable) { settle(admission, Result.failure(failure), executing = false) }
        }
        return admission.completion
    }

    private fun settle(admission: Admission, result: Result<Unit>, executing: Boolean) {
        if (!admission.phase.compareAndSet(if (executing) 1 else 0, 2)) return
        result.exceptionOrNull()?.let(::record)
        admission.reservation.complete()
        result.fold(admission.completion::complete, admission.completion::completeExceptionally)
    }

    private fun record(failure: Throwable) = failures.update { previous ->
        if (previous.view.any { it === failure }) previous
        else Array(previous.size + 1) { index -> if (index < previous.size) previous[index] else failure }.toSeries()
    }

    /** External route admission stops separately from accepted effects and their derived actions. */
    fun stopPreparations() { preparing.complete() }

    suspend fun <T> prepare(action: suspend () -> T): T {
        val current = currentCoroutineContext()
        val inherited = current[Preparation]
        if (inherited != null && inherited.owner === this && inherited.reservation.isActive) {
            // A nested invocation remains a child of its actual executing coroutine.
            return if (current[BoardActionElement] === this) action() else withContext(this) { action() }
        }
        // Reserve before validation or launch so drain includes every accepted preparation.
        val reservation = Job(preparations)
        try {
            if (scope.load() == null || input.isClosed() || !supervisor.isActive)
                throw AdmissionFailure("Board action owner is closed")
            val execution = current[Execution]
            if (!(preparing.isActive || execution?.let { it.owner === this && it.active.isActive } == true || dependency()))
                throw AdmissionFailure("Board route admission stopped")
        } catch (failure: Throwable) {
            reservation.complete()
            throw failure
        }
        val owned = CoroutineScope(current.minusKey(Job) + reservation + this + Preparation(this, reservation))
            .async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val result = try {
                        coroutineScope {
                            try { Result.success(action()) }
                            catch (failure: Throwable) { Result.failure(failure) }
                        }
                    } catch (failure: Throwable) { Result.failure(failure) }
                    result.exceptionOrNull()?.let(::record)
                    result
                } finally {
                    // Completion still joins this owned coroutine and all its structured children.
                    reservation.complete()
                }
            }
        // The waiter owns no preparation child: its cancellation only relinquishes this result.
        return owned.await().getOrThrow()
    }

    suspend fun awaitPreparations() {
        val inherited = currentCoroutineContext()[Preparation]
        check(inherited == null || inherited.owner !== this || !inherited.reservation.isActive) { "A board route cannot join its own preparation" }
        awaitChildren(preparations)
    }

    /** A momentary idle barrier. The module joins receipt watermarks to reach cascade quiescence. */
    suspend fun awaitIdle() {
        checkExecution()
        awaitChildren(actions)
    }

    private suspend fun awaitChildren(owner: Job) {
        while (true) {
            val admitted = owner.children.toList()
            if (admitted.isEmpty()) return
            admitted.joinAll()
        }
    }

    /** Closing the stage refuses new actions; producers already suspended in it are still delivered. */
    fun stopAdmission() {
        preparing.complete()
        input.close()
    }

    /** The module first joins external preparations and receipt cascades, then closes action admission. */
    override suspend fun drain(): Unit = withContext(NonCancellable) {
        checkExecution()
        val inherited = currentCoroutineContext()[Preparation]
        check(inherited == null || inherited.owner !== this@BoardActionElement || !inherited.reservation.isActive) {
            "A board route cannot join its own owner"
        }
        stopAdmission()
        preparations.complete()
        preparations.join()
        actions.complete()
        actions.join()
        supervisor.complete()
        supervisor.join()
        val errors = failures.value
        if (errors.size > 0) throw IllegalStateException("Board actions failed", errors[0]).also { aggregate ->
            for (index in 1 until errors.size) aggregate.addSuppressed(errors[index])
        }
    }

    suspend fun close() = drain()

    private suspend fun checkExecution() {
        val execution = currentCoroutineContext()[Execution]
        check(execution == null || execution.owner !== this || !execution.active.isActive) { "A board action cannot join its own owner" }
    }
}
