package borg.trikeshed.context

import borg.trikeshed.context.ElementState.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * Base for all Element lifecycle objects in the coroutine->context->key->element flow.
 * Implementors hold their Key as a companion object singleton.
 *
 * Fanout semantics: an element may channel completions to N downstream
 * subscribers via [fanoutSubscribers]. The element is responsible for
 * dispatching completions to all subscribers atomically from its perspective.
 */
abstract class AsyncContextElement(
    initialState: ElementState = CREATED,
    parentJob: Job? = null
) : CoroutineContext.Element {
    protected val supervisor = SupervisorJob(parentJob)

    var state: ElementState = initialState
        protected set

    /** Alias for [state] — overrideable in anonymous test subclasses. */
    open val lifecycleState: ElementLifecycleState get() = state

    /**
     * Ordered list of downstream fanout subscribers.
     * Each subscriber is an [AsyncContextElement] that will receive
     * channelized completions from this element.
     */
    open val fanoutSubscribers: List<AsyncContextElement> = emptyList()

    /** Transition CREATED -> OPEN. Idempotent if already OPEN or later. */
    open suspend fun open() {
        if (state == CREATED) {
            state = OPEN
        }
    }

    /**
     * Begin draining: stop accepting new work, process remaining completions,
     * then transition to [ElementState.CLOSED].
     */
    open suspend fun drain() {
        if (state.isAtLeast(OPEN) && state.isLessThan(DRAINING)) {
            state = DRAINING
            // Implementation specific drain logic here
            close()
        }
    }

    /** Transition OPEN -> DRAINING -> CLOSED. */
    open suspend fun close() {
        if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
            if (state < DRAINING) {
                state = DRAINING
            }
            supervisor.cancel()
            state = CLOSED
        }
    }

    protected fun requireState(expected: ElementState) {
        check(state == expected) { "Expected $expected but was $state" }
    }
}
