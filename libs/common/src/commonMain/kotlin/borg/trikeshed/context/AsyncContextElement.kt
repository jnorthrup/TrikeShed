package borg.trikeshed.context

import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * Base for all Element lifecycle objects in the coroutine->context->key->element flow.
 */
abstract class AsyncContextElement(
    initialState: ElementState = ElementState.CREATED
) : CoroutineContext.Element {
    protected val supervisor = SupervisorJob()

    var state: ElementState = initialState
        protected set

    /** Transition CREATED -> OPEN. */
    open suspend fun open() {
        if (state == ElementState.CREATED) {
            state = ElementState.OPEN
        }
    }

    /** Transition OPEN -> CLOSING -> CLOSED. */
    open suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSING)) {
            state = ElementState.CLOSING
            supervisor.cancel()
            state = ElementState.CLOSED
        }
    }

    protected fun requireState(expected: ElementState) {
        check(state == expected) { "Expected $expected but was $state" }
    }
}
