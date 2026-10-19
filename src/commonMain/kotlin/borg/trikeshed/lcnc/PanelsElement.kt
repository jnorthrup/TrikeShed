package borg.trikeshed.lcnc

import borg.trikeshed.context.*
import borg.trikeshed.kanban.BoardActionElement
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Page actions and shared picklist reads remain in separate bounded, joined lanes. */
@OptIn(ExperimentalAtomicApi::class)
class PanelsElement(
    parentJob: Job,
    capacity: Int = 16,
    workers: Int = 3,
    private val maxActions: Int = 64,
    maxPicklists: Int = 8,
    private val maxCachedPicklists: Int = 128,
) : AsyncContextElement(ElementState.CREATED, parentJob.also {
    require(capacity > 0 && workers > 0 && maxActions > 0 && maxPicklists > 0)
    require(maxCachedPicklists >= maxPicklists)
}) {
    companion object Key : AsyncContextKey<PanelsElement>()
    override val key: CoroutineContext.Key<*> get() = Key

    private class Invocation(val owner: PanelsElement) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Invocation>
        val active = AtomicBoolean(true)
    }

    private class Reading(val owner: PanelsElement) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Reading>
        val active = AtomicBoolean(true)
    }

    private class Picklist {
        val result = CompletableDeferred<Result<List<String>>>()
        val failed = AtomicBoolean(false)
    }

    private val lifecycle = Mutex()
    private val cacheMutex = Mutex()
    private val accepting = AtomicBoolean(true)
    private val opened = AtomicBoolean(false)
    private val admitted = AtomicInt(0)
    private val actions = BoardActionElement(supervisor, capacity, workers)
    private val reads = BoardActionElement(supervisor, capacity, workers)
    private val readPermits = Semaphore(maxPicklists)
    private val cache = LinkedHashMap<String, Picklist>()

    override suspend fun open() = lifecycle.withLock {
        if (opened.load()) return@withLock
        check(state == ElementState.CREATED && accepting.load()) { "Panels owner is stopped" }
        supervisor.ensureActive()
        try {
            withContext(this@PanelsElement) { actions.open(); reads.open() }
            state = ElementState.ACTIVE
            opened.store(true)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                for (lane in arrayOf(actions, reads)) try { lane.drain() }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                supervisor.complete()
                supervisor.join()
                state = ElementState.CLOSED
            }
            throw failure
        }
    }

    /** Null refuses a full or stopped page. Cancelling the returned waiter never frees live work. */
    fun submit(action: suspend () -> Unit): Deferred<Unit>? {
        while (true) {
            val before = admitted.load()
            if (before >= maxActions) return null
            if (admitted.compareAndSet(before, before + 1)) break
        }
        if (!opened.load() || !accepting.load() || !supervisor.isActive) {
            admitted.fetchAndAdd(-1)
            return null
        }
        val completion = actions.submit {
            val invocation = Invocation(this@PanelsElement)
            try { withContext(this@PanelsElement + invocation) { action() } }
            finally { invocation.active.store(false) }
        }
        if (completion == null) {
            admitted.fetchAndAdd(-1)
            return null
        }
        val waiter = CompletableDeferred<Unit>()
        // Keep the actual lane completion private: waiter cancellation is not action completion.
        completion.invokeOnCompletion { failure ->
            admitted.fetchAndAdd(-1)
            if (failure == null) waiter.complete(Unit) else waiter.completeExceptionally(failure)
        }
        return waiter
    }

    private fun cached(key: String): Picklist? {
        val previous = cache.remove(key) ?: return null
        if (previous.failed.load()) return null
        cache[key] = previous
        return previous
    }

    /** Only live admitted page actions can create or await page-local shared requests. */
    suspend fun picklist(key: String, read: suspend () -> List<String>): List<String> {
        require(key.isNotBlank())
        val invocation = currentCoroutineContext()[Invocation]
        check(invocation != null && invocation.owner === this && invocation.active.load()) {
            "Picklist requires a live admitted page action"
        }
        cacheMutex.withLock { cached(key) }?.let { return it.result.await().getOrThrow() }
        readPermits.acquire()
        var release = true
        try {
            currentCoroutineContext().ensureActive()
            var created = false
            val entry = cacheMutex.withLock {
                cached(key) ?: run {
                    while (cache.size >= maxCachedPicklists) {
                        val evicted = cache.entries.firstOrNull { it.value.result.isCompleted }
                            ?: error("Picklist cache has no completed entry to evict")
                        cache.remove(evicted.key)
                    }
                    Picklist().also { cache[key] = it; created = true }
                }
            }
            if (!created) return entry.result.await().getOrThrow()
            val completion = reads.submit {
                val reading = Reading(this@PanelsElement)
                val result = try {
                    withContext(this@PanelsElement + reading) {
                        try { Result.success(coroutineScope { read() }) }
                        catch (failure: Throwable) { Result.failure(failure) }
                    }
                } catch (failure: Throwable) { Result.failure(failure) }
                finally { reading.active.store(false) }
                entry.failed.store(result.isFailure)
                entry.result.complete(result)
                result.getOrThrow()
            }
            if (completion == null) {
                val failure = IllegalStateException("Panels request lane is stopped")
                entry.failed.store(true)
                entry.result.complete(Result.failure(failure))
                throw failure
            }
            release = false
            completion.invokeOnCompletion { failure ->
                if (failure != null) {
                    entry.failed.store(true)
                    entry.result.complete(Result.failure(failure))
                }
                readPermits.release()
            }
            return entry.result.await().getOrThrow()
        } finally { if (release) readPermits.release() }
    }

    /** Stop external action roots; their admitted dependent reads remain available until joined. */
    fun stopAdmission() { accepting.store(false) }

    override suspend fun drain(): Unit = withContext(NonCancellable) {
        val current = currentCoroutineContext()
        check(current[Invocation]?.let { it.owner === this@PanelsElement && it.active.load() } != true &&
            current[Reading]?.let { it.owner === this@PanelsElement && it.active.load() } != true) {
            "A page action cannot join its own owner"
        }
        stopAdmission()
        lifecycle.withLock {
            var failure: Throwable? = null
            // Reads stay open until every admitted action and its structured children has finished.
            for (lane in arrayOf(actions, reads)) try { lane.drain() }
            catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
            }
            cacheMutex.withLock { cache.clear() }
            supervisor.complete()
            supervisor.join()
            state = ElementState.CLOSED
            failure?.let { throw it }
        }
    }

    override suspend fun close() = drain()
}
