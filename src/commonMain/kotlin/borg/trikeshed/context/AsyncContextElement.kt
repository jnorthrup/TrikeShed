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
 * Context element with a supervisor and explicit lifecycle state.
 */
abstract class AsyncContextElement(
    initialState: ElementState = CREATED,
    parentJob: Job? = null
) : CoroutineContext.Element {

    /** Parent job passed to the internal SupervisorJob, or null. */
    protected val parentJob: Job? = parentJob

    /** SupervisorJob created from [parentJob]. */
    open val supervisor: CompletableJob = SupervisorJob(parentJob)

    private val stateMutex = Mutex()

    var state: ElementState = initialState
        protected set

    /** Alias for [state] — overrideable in anonymous test subclasses. */
    open val lifecycleState: ElementState get() = state

    /** Downstream elements exposed by subclasses. */
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

    /** Completes an opened element's supervisor, then calls [close]. */
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
        } else {
            // Wait for the supervisor and the separately maintained state.
            supervisor.join()
            while (state.isLessThan(CLOSED)) {
                kotlinx.coroutines.delay(1)
            }
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
