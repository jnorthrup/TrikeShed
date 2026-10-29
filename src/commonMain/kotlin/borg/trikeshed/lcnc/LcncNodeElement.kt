package borg.trikeshed.lcnc

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

sealed interface LcncNodeOutcome {
    data class Returned(val outputs: Map<String, Any?>) : LcncNodeOutcome
    data class Failed(val cause: Throwable) : LcncNodeOutcome
    data class Cancelled(val cause: CancellationException) : LcncNodeOutcome
}

/** A single invocation owns its inputs, result and structured child work. */
class LcncNodeElement internal constructor(
    override val key: LcncNodeKey,
    val node: LcncNode,
    val inputs: Map<String, Any?>,
    private val runner: LcncNodeRunner,
    parent: Job,
) : CoroutineContext.Element {
    private val parentJob = parent
    val supervisor = SupervisorJob(parent)
    /** Completes at [drain] or supervisor completion; the invocation refuses new work after it. */
    val admission: CompletableJob = Job()
    /** Completes when [execute] claims the single invocation. */
    val consumed: CompletableJob = Job()
    val fanoutSubscribers: List<LcncNodeElement> get() = emptyList()
    private val completion = MutableStateFlow<LcncNodeOutcome?>(null)
    val outcome: LcncNodeOutcome? get() = completion.value
    val result: Map<String, Any?>? get() = (outcome as? LcncNodeOutcome.Returned)?.outputs

    init {
        supervisor.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                completion.compareAndSet(null, LcncNodeOutcome.Cancelled(cause))
            }
            admission.complete()
        }
    }

    suspend fun open() {
        if (!admission.isActive) {
            if (supervisor.isCancelled) supervisor.ensureActive()
            error("${node.id}: invocation is closed to new work")
        }
    }

    suspend fun execute(): Map<String, Any?> {
        open()
        if (!consumed.complete()) {
            if (supervisor.isCancelled) supervisor.ensureActive()
            error("${node.id}: invocation already consumed")
        }
        try {
            currentCoroutineContext().ensureActive()
            val outcome = withContext(supervisor + this + LcncOwnerJob(parentJob)) {
                check(currentCoroutineContext()[key] === this@LcncNodeElement)
                try {
                    LcncNodeOutcome.Returned(runner.execute(node, inputs))
                } catch (failure: Throwable) {
                    val cancellation = cancellationFor(failure)
                    currentCoroutineContext()[Job]?.children?.forEach { it.cancel(cancellation) }
                    if (failure is CancellationException) LcncNodeOutcome.Cancelled(failure)
                    else LcncNodeOutcome.Failed(failure)
                }
            }
            return when (outcome) {
                is LcncNodeOutcome.Returned -> {
                    completion.value = outcome
                    outcome.outputs
                }
                is LcncNodeOutcome.Cancelled -> {
                    completion.value = outcome
                    supervisor.cancel(outcome.cause)
                    throw outcome.cause
                }
                is LcncNodeOutcome.Failed -> {
                    completion.value = outcome
                    val cancellation = cancellationFor(outcome.cause)
                    supervisor.cancel(cancellation)
                    throw outcome.cause
                }
            }
        } catch (failure: Throwable) {
            completion.value = if (failure is CancellationException) LcncNodeOutcome.Cancelled(failure)
                else LcncNodeOutcome.Failed(failure)
            supervisor.cancel(cancellationFor(failure))
            throw failure
        } finally {
            withContext(NonCancellable) { drain() }
        }
    }

    /** Admission stops first; existing children finish before the element is closed. */
    suspend fun drain() {
        admission.complete()
        supervisor.complete()
        supervisor.join()
    }

    suspend fun close() = drain()

    private fun cancellationFor(failure: Throwable): CancellationException =
        failure as? CancellationException ?: CancellationException("${node.id}: invocation failed", failure)
}
