package borg.trikeshed.context

import kotlin.coroutines.CoroutineContext

/**
 * Base for all Element lifecycle objects in the coroutine->context->key->element flow.
 * Implementors hold their Key as a companion object singleton.
 */
abstract class AsyncContextElement : CoroutineContext.Element {
    var state: ElementState = ElementState.CREATED
        protected set

    /** Transition CREATED -> OPEN. Throws IllegalStateException if not CREATED. */
    abstract suspend fun open()

    /** Transition OPEN -> CLOSING -> CLOSED. Throws IllegalStateException if not OPEN. */
    abstract suspend fun close()

    protected fun requireState(expected: ElementState) {
        check(state == expected) { "Expected $expected but was $state" }
    }
}
