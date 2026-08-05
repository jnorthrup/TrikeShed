package borg.trikeshed.context

import borg.trikeshed.context.ElementState.*
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Parent job passed to the internal SupervisorJob, or null. */
    protected val parentJob: Job? = parentJob

    /** SupervisorJob for this element's coroutine scope. */
    open val supervisor: CompletableJob = SupervisorJob(parentJob)

    private val stateMutex = Mutex()

    var state: ElementState = initialState
        protected set

    /** Alias for [state] — overrideable in anonymous test subclasses. */
    open val lifecycleState: ElementState get() = state

    /**
     * Ordered list of downstream fanout subscribers.
     * Each subscriber is an [AsyncContextElement] that will receive
     * channelized completions from this element.
     */
    open val fanoutSubscribers: List<AsyncContextElement> = emptyList()

    /** Abstract key property that must be implemented by subclasses. */
    abstract override val key: CoroutineContext.Key<*>

    /** Transition CREATED -> OPEN. Idempotent if already OPEN or later. */
    open suspend fun open() {
        stateMutex.withLock {
            if (state == CREATED) {
                state = OPEN
            }
        }
    }

    /**
     * Begin draining: stop accepting new work, process remaining completions,
     * then transition to [ElementState.CLOSED].
     */
    open suspend fun drain() {
        val shouldDrain = stateMutex.withLock {
            if (state.isAtLeast(OPEN) && state.isLessThan(DRAINING)) {
                state = DRAINING
                true
            } else {
                false
            }
        }
        if (shouldDrain) {
            supervisor.complete()
            supervisor.join()
            close()
        }
    }

    /** Transition OPEN -> DRAINING -> CLOSED. */
    open suspend fun close() {
        val shouldClose = stateMutex.withLock {
            if (state.isAtLeast(OPEN) && state.isLessThan(CLOSED)) {
                if (state < DRAINING) {
                    state = DRAINING
                }
                true
            } else {
                false
            }
        }
        if (shouldClose) {
            supervisor.cancelAndJoin()
            stateMutex.withLock {
                state = CLOSED
            }
        }
    }

    protected fun requireState(expected: ElementState) {
        check(state == expected) { "Expected $expected but was $state" }
    }
}