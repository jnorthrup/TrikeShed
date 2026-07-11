package borg.trikeshed.userspace

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.reactor.FanoutDispatcherElement
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

/** Companion object for LiburingElement key access. */
object LiburingKey : CoroutineContext.Key<LiburingElement>

/** Install LiburingElement + FanoutDispatcherElement in the coroutine context. */
suspend fun CoroutineScope.installLiburingWithFanout(): Pair<LiburingElement, FanoutDispatcherElement> {
    val liburing = LiburingElement(supervisorScope { null })
    val fanout = FanoutDispatcherElement(supervisorScope { null })
    liburing.open()
    // Install both elements in this scope's context
    return withContext(liburing + fanout) { liburing to fanout }
}