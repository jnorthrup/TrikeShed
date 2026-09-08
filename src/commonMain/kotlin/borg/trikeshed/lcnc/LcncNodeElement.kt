package borg.trikeshed.lcnc

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** A single invocation owns its inputs, result and structured child work. */
class LcncNodeElement internal constructor(
    override val key: LcncNodeKey,
    val node: LcncNode,
    val inputs: Map<String, Any?>,
    private val runner: LcncNodeRunner,
    parent: Job,
) : CoroutineContext.Element {
    val supervisor = SupervisorJob(parent)
    private val phase = MutableStateFlow(ElementState.CREATED)
    val lifecycleState: ElementState get() = phase.value
    val state: ElementState get() = phase.value
    val fanoutSubscribers: List<LcncNodeElement> get() = emptyList()
    private val output = MutableStateFlow<Map<String, Any?>?>(null)
    val result: Map<String, Any?>? get() = output.value

    init {
        supervisor.invokeOnCompletion { advance(ElementState.CLOSED) }
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
        check(lifecycleState < ElementState.DRAINING) { "${node.id}: invocation is closed to new work" }
    }

    suspend fun execute(): Map<String, Any?> {
        open()
        check(phase.compareAndSet(ElementState.OPEN, ElementState.ACTIVE)) { "${node.id}: invocation already consumed" }
        try {
            val value = withContext(supervisor + this) {
                check(currentCoroutineContext()[key] === this@LcncNodeElement)
                runner.execute(node, inputs)
            }
            output.value = value
            return value
        } catch (failure: Throwable) {
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
