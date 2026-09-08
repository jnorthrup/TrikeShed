package borg.trikeshed.lcnc

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
    private val phase = MutableStateFlow(ElementState.CREATED)
    val lifecycleState: ElementState get() = phase.value
    val state: ElementState get() = phase.value
    val fanoutSubscribers: List<LcncNodeElement> get() = emptyList()
    private val completion = MutableStateFlow<LcncNodeOutcome?>(null)
    val outcome: LcncNodeOutcome? get() = completion.value
    val result: Map<String, Any?>? get() = (outcome as? LcncNodeOutcome.Returned)?.outputs

    init {
        supervisor.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                completion.compareAndSet(null, LcncNodeOutcome.Cancelled(cause))
            }
            advance(ElementState.CLOSED)
        }
    }

    private fun advance(target: ElementState) {
        while (true) {
            val before = phase.value
            if (before >= target) return
            val after = when {
                before == ElementState.CREATED -> ElementState.OPEN
                before < ElementState.DRAINING && target >= ElementState.DRAINING -> ElementState.DRAINING
                else -> target
            }
            phase.compareAndSet(before, after)
        }
    }

    suspend fun open() {
        phase.compareAndSet(ElementState.CREATED, ElementState.OPEN)
        if (lifecycleState >= ElementState.DRAINING) {
            if (supervisor.isCancelled) supervisor.ensureActive()
            error("${node.id}: invocation is closed to new work")
        }
    }

    suspend fun execute(): Map<String, Any?> {
        open()
        if (!phase.compareAndSet(ElementState.OPEN, ElementState.ACTIVE)) {
            if (supervisor.isCancelled) supervisor.ensureActive()
            error("${node.id}: invocation already consumed")
        }
        try {
            currentCoroutineContext().ensureActive()
            val value = withContext(supervisor + this + LcncOwnerJob(parentJob)) {
                check(currentCoroutineContext()[key] === this@LcncNodeElement)
                runner.execute(node, inputs)
            }
            completion.value = LcncNodeOutcome.Returned(value)
            return value
        } catch (failure: Throwable) {
            completion.value = if (failure is CancellationException) LcncNodeOutcome.Cancelled(failure)
                else LcncNodeOutcome.Failed(failure)
            val cancellation = failure as? CancellationException
                ?: CancellationException("${node.id}: invocation failed").apply { initCause(failure) }
            supervisor.cancel(cancellation)
            throw failure
        } finally {
            withContext(NonCancellable) { drain() }
        }
    }

    /** Admission stops first; existing children finish before the element is closed. */
    suspend fun drain() {
        advance(ElementState.DRAINING)
        supervisor.complete()
        supervisor.join()
        advance(ElementState.CLOSED)
    }

    suspend fun close() = drain()
}
