package borg.trikeshed.userspace

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.supervisorScope
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
 * FanoutDispatcherElement — Pattern A CCEK element for channelized completion dispatch.
 *
 * Central dispatch point for all io_uring completions. Elements register
 * handlers by userData token; completions are fanned out to all subscribers
 * for that token.
 *
 * PRELOAD.md contract:
 * - Single-threaded dispatch via reactor CQE loop
 * - Structured concurrency: handlers run in parent SupervisorJob scope
 * - Cold Series α-projection: handlers receive cold completion Series
 */
class FanoutDispatcherElement(
    parentJob: kotlinx.coroutines.Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: CoroutineContext.Key<*> get() = borg.trikeshed.userspace.context.AsyncContextKey.FanoutDispatcherKey

    private val handlers = mutableMapOf<Long, MutableList<(UringCompletion) -> Unit>>()

    /** Register a handler for completions with the given userData token. */
    fun registerHandler(userData: Long, handler: (UringCompletion) -> Unit) {
        handlers.getOrPut(userData) { mutableListOf() }.add(handler)
        // Also register with liburing facade
        Liburing.registerFanoutHandler(userData, handler)
    }

    /** Remove a handler. */
    fun removeHandler(userData: Long, handler: (UringCompletion) -> Unit) {
        handlers[userData]?.remove(handler)
        if (handlers[userData].isNullOrEmpty()) handlers.remove(userData)
        Liburing.removeFanoutHandler(userData, handler)
    }

    /** Dispatch a completion to all handlers for its userData. */
    internal fun dispatch(completion: UringCompletion) {
        handlers[completion.userData]?.toList()?.forEach { it(completion) }
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
    val liburing = LiburingElement(supervisorScope { null })
    val fanout = FanoutDispatcherElement(supervisorScope { null })
    liburing.open()
    // Install both elements in this scope's context
    return withContext(liburing + fanout) { liburing to fanout }
}
