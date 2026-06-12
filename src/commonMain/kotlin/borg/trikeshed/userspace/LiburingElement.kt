package borg.trikeshed.userspace

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * LiburingElement — Pattern A CCEK element wrapping the liburing facade.
 *
 * Provides:
 * - Lifecycle management (CREATED -> OPEN -> ACTIVE -> DRAINING -> CLOSED)
 * - Fanout dispatcher registration via liburing's registerFanoutHandler
 * - Coroutine-scope submit/wait for io_uring operations
 *
 * PRELOAD.md contract:
 * - Gets installed in coroutine context via LiburingKey
 * - Structured concurrency: SupervisorJob + withContext for all operations
 * - Fanout handlers keyed by userData token
 */
class LiburingElement(
    parentJob: kotlinx.coroutines.Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: CoroutineContext.Key<*> get() = borg.trikeshed.userspace.context.AsyncContextKey.LiburingKey

    override suspend fun open() {
        super.open()
        // Initialize the liburing ring
        val result = Liburing.open()
        result.onFailure { e ->
            throw IllegalStateException("Failed to open liburing: ${e.message}", e)
        }
    }

    override suspend fun close() {
        // Close the liburing ring
        Liburing.close()
        super.close()
    }

    /** Submit a batch of prepared operations. */
    suspend fun submit(): Int = Liburing.submit().getOrThrow()

    /** Wait for at least [minComplete] completions. */
    suspend fun wait(minComplete: Int = 1): List<UringCompletion> = withContext(kotlinx.coroutines.Dispatchers.Default) {
        Liburing.waitCqe().getOrThrow()
        // In real impl, this would collect multiple completions
        emptyList()
    }

    override suspend fun drain() {
        Liburing.drain()
        super.drain()
    }
}

/**
 * FanoutEvent — generic event type for fanout dispatcher.
 * Subsystems can define their own event type codes (offset + 100).
 */
interface FanoutEvent {
    val eventType: Int
}

/**
 * FanoutDispatcherElement — Pattern A CCEK element for generic event dispatch.
 *
 * Central dispatch point for all events (io_uring completions, splat updates, etc.).
 * Elements register handlers by event type token; events are fanned out to all
 * subscribers for that type.
 *
 * PRELOAD.md contract:
 * - Single-threaded dispatch via reactor CQE loop
 * - Structured concurrency: handlers run in parent SupervisorJob scope
 * - Cold Series α-projection: handlers receive cold event Series
 */
class FanoutDispatcherElement(
    parentJob: kotlinx.coroutines.Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: CoroutineContext.Key<*> get() = borg.trikeshed.userspace.context.AsyncContextKey.FanoutDispatcherKey

    private val handlers = mutableMapOf<Int, MutableList<(FanoutEvent) -> Unit>>()

    /** Register a handler for events of the given type code. */
    fun registerHandler(eventType: Int, handler: (FanoutEvent) -> Unit) {
        handlers.getOrPut(eventType) { mutableListOf() }.add(handler)
    }

    /** Remove a handler. */
    fun removeHandler(eventType: Int, handler: (FanoutEvent) -> Unit) {
        handlers[eventType]?.remove(handler)
        if (handlers[eventType].isNullOrEmpty()) handlers.remove(eventType)
    }

    /** Dispatch an event to all handlers for its type. */
    fun dispatch(event: FanoutEvent) {
        handlers[event.eventType]?.toList()?.forEach { it(event) }
    }

    /** Dispatch a UringCompletion (eventType = 0). */
    fun dispatchUring(completion: UringCompletion) {
        // UringCompletion doesn't implement FanoutEvent, use type code 0
        handlers[0]?.toList()?.forEach { it(completion) }
    }

    override suspend fun close() {
        handlers.clear()
        super.close()
    }
}

/** Companion object for LiburingInstallement key access. */
object LiburingKey : CoroutineContext.Key<LiburingElement>

/** Companion object for FanoutDispatcherElement key access. */
object FanoutDispatcherKey : CoroutineContext.Key<FanoutDispatcherElement>

/** Install LiburingElement + FanoutDispatcherElement in the coroutine context. */
suspend fun CoroutineScope.installLiburingWithFanout(): Pair<LiburingElement, FanoutDispatcherElement> {
    val liburing = LiburingElement(SupervisorJob())
    val fanout = FanoutDispatcherElement(SupervisorJob())
    liburing.open()
    // Install both elements in this scope's context
    return withContext(liburing + fanout) { liburing to fanout }
}