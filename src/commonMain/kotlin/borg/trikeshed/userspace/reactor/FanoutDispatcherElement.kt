package borg.trikeshed.userspace.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.Liburing
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.context.AsyncContextKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Fanout dispatcher CCEK element for liburing completion dispatch.
 * Moved here from userspace/LiburingElement.kt for proper package organization.
 *
 * Completion idiom aligned with the sibling ChannelRunner: a suspended awaiter
 * parks on a [CompletableDeferred] keyed by userData token and is resumed by
 * the channel-backed consumer loop. Each deferred is single-shot — the consumer
 * takes it out of the registry as it completes it — so every [awaitCompletion]
 * call arms a fresh one.
 *
 * Delivery rules for [awaitCompletion]:
 *  - A completion is handed to exactly **one** awaiter, oldest first, the way
 *    ChannelRunner drains its per-fd writer queue.
 *  - A completion dispatched for a token nobody is awaiting yet is **stashed**
 *    (bounded, see [UNCLAIMED_LIMIT]) rather than dropped, so the natural
 *    io_uring order — submit the SQE, then await its userData — cannot lose a
 *    CQE that arrives inside that window.
 *
 * Legacy multi-handler callbacks ([registerHandler]/[removeHandler]) are
 * preserved for compatibility but deprecated in favor of [awaitCompletion].
 * They keep their broadcast semantics: every registered handler sees every
 * completion for its token, independently of any awaiter.
 */
open class FanoutDispatcherElement(
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: CoroutineContext.Key<*> get() = AsyncContextKey.FanoutDispatcherKey

    /**
     * Legacy callback registry, mutated by the non-suspending
     * [registerHandler]/[removeHandler] and read by the consumer loop.
     *
     * Known pre-existing hazard, unchanged by the [awaitCompletion] work and
     * deliberately not papered over here: those two entry points cannot take
     * [mutex] without either blocking (forbidden in commonMain) or deferring
     * the mutation off-thread, where a registration could miss completions
     * dispatched right after it returned. A lock-free copy-on-write swap would
     * fix it properly; until then this map carries exactly the concurrency
     * guarantees it always had, which is one more reason to prefer
     * [awaitCompletion], whose state IS mutex-guarded.
     */
    private val handlers = mutableMapOf<Long, MutableList<(UringCompletion) -> Unit>>()

    /** Guards [awaiters] and [unclaimed]. */
    private val mutex = Mutex()

    /** userData token -> FIFO queue of single-shot deferreds awaiting it. */
    private val awaiters = mutableMapOf<Long, ArrayDeque<CompletableDeferred<UringCompletion>>>()

    /** userData token -> completions that arrived before anyone awaited them. */
    private val unclaimed = mutableMapOf<Long, ArrayDeque<UringCompletion>>()

    private var unclaimedCount = 0

    private val dispatchChannel = Channel<UringCompletion>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** The consumer loop, so [close] can let it drain before the supervisor dies. */
    private var consumer: Job? = null

    override suspend fun open() {
        super.open()
        if (consumer != null) return // open() is idempotent; one consumer loop only
        consumer = CoroutineScope(supervisor).launch {
            for (completion in dispatchChannel) {
                // Legacy callbacks are a broadcast independent of any awaiter, and
                // they run before the deferred completes so a resuming awaiter
                // observes all callback side effects. One misbehaving handler must
                // not kill dispatch for every token.
                handlers[completion.userData]?.toList()?.forEach { handler ->
                    try {
                        handler(completion)
                    } catch (e: Throwable) {
                        println("WARN: FanoutDispatcherElement handler threw for userData=${completion.userData}: $e")
                    }
                }
                // Claim-or-stash must be ONE critical section: splitting it lets an
                // awaiter arm in the gap, find nothing stashed, park — and then have
                // its completion stashed behind it, where it never looks again.
                val waiter = mutex.withLock {
                    val claimed = takeWaiter(completion.userData)
                    if (claimed == null) stash(completion)
                    claimed
                }
                waiter?.complete(completion)
            }
        }
    }

    /**
     * Suspend until a completion arrives for [userData], ChannelRunner-style.
     *
     * Returns immediately with a completion already stashed for the token, if
     * any. Otherwise arms a fresh single-shot deferred and parks on it; with
     * several awaiters on one token the oldest is served first. Cancellation
     * disarms only this call's own deferred, leaving peers on the same token
     * intact. [close] fails whatever is still armed.
     */
    suspend fun awaitCompletion(userData: Long): UringCompletion {
        val deferred = mutex.withLock {
            takeUnclaimed(userData)?.let { return it }
            CompletableDeferred<UringCompletion>().also {
                awaiters.getOrPut(userData) { ArrayDeque() }.addLast(it)
            }
        }
        return try {
            deferred.await()
        } finally {
            // NonCancellable: the common reason this finally runs is cancellation,
            // and Mutex.lock() is itself cancellable — without this the entry leaks.
            withContext(NonCancellable) {
                mutex.withLock { disarm(userData, deferred) }
            }
        }
    }

    /** Register a handler for completions with the given userData token. */
    @Deprecated(
        "Use awaitCompletion(userData) — the CompletableDeferred idiom shared with ChannelRunner.",
        ReplaceWith("awaitCompletion(userData)"),
    )
    fun registerHandler(userData: Long, handler: (UringCompletion) -> Unit) {
        handlers.getOrPut(userData) { mutableListOf() }.add(handler)
        // Also register with liburing facade
        Liburing.registerFanoutHandler(userData, handler)
    }

    /** Remove a handler. */
    @Deprecated("Callback API superseded by awaitCompletion(userData); pairs with registerHandler.")
    fun removeHandler(userData: Long, handler: (UringCompletion) -> Unit) {
        handlers[userData]?.remove(handler)
        if (handlers[userData].isNullOrEmpty()) handlers.remove(userData)
        Liburing.removeFanoutHandler(userData, handler)
    }

    /** Dispatch a completion to all handlers/awaiters for its userData. */
    internal suspend fun dispatch(completion: UringCompletion) {
        val result = dispatchChannel.trySend(completion)
        if (result.isFailure) {
            println("WARN: FanoutDispatcherElement channel full, suspending")
            dispatchChannel.send(completion)
        }
    }

    /**
     * Close the dispatch channel first so the consumer loop can finish; the base
     * implementation's `supervisor.join()` would otherwise wait on a loop that
     * never ends.
     */
    override suspend fun drain() {
        dispatchChannel.close()
        super.drain()
    }

    override suspend fun close() {
        dispatchChannel.close()
        // Let the loop hand out already-buffered completions before the
        // supervisor is cancelled, so an awaiter whose completion was accepted
        // is resumed with it rather than failed.
        consumer?.join()
        handlers.clear()
        val stranded = mutex.withLock {
            val armed = awaiters.values.flatten()
            awaiters.clear()
            unclaimed.clear()
            unclaimedCount = 0
            armed
        }
        // Fail outside the lock: resuming an awaiter may run inline, and that
        // awaiter's cleanup takes the same mutex. completeExceptionally rather
        // than cancel() so the shutdown surfaces instead of silently killing
        // launch-based awaiters.
        stranded.forEach {
            it.completeExceptionally(IllegalStateException("FanoutDispatcherElement closed while awaiting completion"))
        }
        super.close()
    }

    /** Pop the oldest awaiter for [userData], if any. Caller holds [mutex]. */
    private fun takeWaiter(userData: Long): CompletableDeferred<UringCompletion>? {
        val queue = awaiters[userData] ?: return null
        val waiter = queue.removeFirstOrNull()
        if (queue.isEmpty()) awaiters.remove(userData)
        return waiter
    }

    /** Drop [deferred] from [userData]'s queue if it is still parked. Caller holds [mutex]. */
    private fun disarm(userData: Long, deferred: CompletableDeferred<UringCompletion>) {
        val queue = awaiters[userData] ?: return
        queue.remove(deferred)
        if (queue.isEmpty()) awaiters.remove(userData)
    }

    /** Pop a completion stashed for [userData], if any. Caller holds [mutex]. */
    private fun takeUnclaimed(userData: Long): UringCompletion? {
        val queue = unclaimed[userData] ?: return null
        val completion = queue.removeFirstOrNull()
        if (completion != null) unclaimedCount--
        if (queue.isEmpty()) unclaimed.remove(userData)
        return completion
    }

    /** Hold a completion nobody is awaiting yet, evicting the oldest when full. Caller holds [mutex]. */
    private fun stash(completion: UringCompletion) {
        unclaimed.getOrPut(completion.userData) { ArrayDeque() }.addLast(completion)
        unclaimedCount++
        while (unclaimedCount > UNCLAIMED_LIMIT) {
            val oldest = unclaimed.entries.firstOrNull() ?: break
            oldest.value.removeFirstOrNull() ?: break
            unclaimedCount--
            if (oldest.value.isEmpty()) unclaimed.remove(oldest.key)
        }
    }

    private companion object {
        /**
         * Cap on completions held for tokens nobody awaited. A userData token is
         * short-lived, so an unclaimed stash that keeps growing means completions
         * are being dispatched for tokens with no reader; drop the oldest rather
         * than retain them forever.
         */
        const val UNCLAIMED_LIMIT = 256
    }
}
