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

/** A live backend handle. The token identifies a context, not a reusable route name. */
interface CamelRouteHandle {
    val id: String
    val token: Any get() = this
    val cleanupPending: Boolean get() = false
    fun status(): String
    fun routeIds(): List<String>
    fun identity(): Map<String, Any?>
    fun describe(): Map<String, Any?>
}

/** Host route commands and exchange publication have separate bounded, joined lanes. */
@OptIn(ExperimentalAtomicApi::class)
class CamelRouteElement(
    parentJob: Job,
    capacity: Int = 16,
    private val maxCommands: Int = 64,
    maxObservations: Int = 64,
) : AsyncContextElement(ElementState.CREATED, parentJob.also {
    require(capacity > 0 && maxCommands > 0 && maxObservations > 0)
}) {
    companion object Key : AsyncContextKey<CamelRouteElement>()
    override val key: CoroutineContext.Key<*> get() = Key

    private enum class Lane { COMMAND, PUBLICATION }

    private class Invocation(val owner: CamelRouteElement, val lane: Lane, val parent: Invocation?) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Invocation>
        val active = AtomicBoolean(true)
    }

    private fun Invocation?.contains(owner: CamelRouteElement, lane: Lane? = null): Boolean {
        var invocation = this
        while (invocation != null) {
            if (invocation.owner === owner && invocation.active.load() &&
                (lane == null || invocation.lane == lane)) return true
            invocation = invocation.parent
        }
        return false
    }

    private class Lease(val handle: CamelRouteHandle, val stop: suspend () -> Unit)
    private val lifecycle = Mutex()
    private val accepting = AtomicBoolean(true)
    private val opened = AtomicBoolean(false)
    private val admitted = AtomicInt(0)
    private val commands = BoardActionElement(supervisor, capacity, workers = 1)
    private val observations = BoardActionElement(supervisor, capacity, workers = 1)
    private val observationPermits = Semaphore(maxObservations)
    // Lifetime obligations only; the backend remains the one registry of live Camel contexts.
    private val leases = mutableListOf<Lease>()

    override suspend fun open() = lifecycle.withLock {
        if (opened.load()) return@withLock
        check(state == ElementState.CREATED && accepting.load()) { "Camel owner is stopped" }
        supervisor.ensureActive()
        try {
            withContext(this@CamelRouteElement) { commands.open(); observations.open() }
            state = ElementState.ACTIVE
            opened.store(true)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                for (lane in arrayOf(commands, observations)) try { lane.drain() }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                supervisor.complete()
                supervisor.join()
                state = ElementState.CLOSED
            }
            throw failure
        }
    }

    /** Refuse excess commands; cancelling a waiter does not discard an accepted host effect. */
    suspend fun <T> command(action: suspend () -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        checkOwnedCallback(caller)
        while (true) {
            val count = admitted.load()
            check(count < maxCommands) { "Camel command capacity reached" }
            if (admitted.compareAndSet(count, count + 1)) break
        }
        if (!opened.load() || !accepting.load() || !supervisor.isActive) {
            admitted.fetchAndAdd(-1)
            error("Camel command admission stopped")
        }
        val result = CompletableDeferred<Result<T>>()
        val completion = commands.submit {
            val invocation = Invocation(this@CamelRouteElement, Lane.COMMAND, caller[Invocation])
            val value = try {
                withContext(caller.minusKey(Job) + this@CamelRouteElement + invocation) {
                    runCatching { coroutineScope { action() } }
                }
            } catch (failure: Throwable) { Result.failure(failure) }
            finally { invocation.active.store(false) }
            // A command refusal is a result, not a poisoned worker lane.
            result.complete(value)
        }
        if (completion == null) {
            admitted.fetchAndAdd(-1)
            error("Camel command admission stopped")
        }
        completion.invokeOnCompletion { failure ->
            admitted.fetchAndAdd(-1)
            if (failure != null) result.complete(Result.failure(failure))
        }
        return result.await().getOrThrow()
    }

    /** Consumer callbacks backpressure here; no unbounded producer coroutines or dropped facts. */
    suspend fun observe(action: suspend () -> Unit) {
        val current = currentCoroutineContext()
        check(!current[Invocation].contains(this, Lane.PUBLICATION)) {
            "A Camel publication cannot await its own lane"
        }
        check(opened.load() && supervisor.isActive) { "Camel observation owner is stopped" }
        observationPermits.acquire()
        val completion = observations.submit {
            val invocation = Invocation(this@CamelRouteElement, Lane.PUBLICATION, current[Invocation])
            try { withContext(this@CamelRouteElement + invocation) { action() } }
            finally { invocation.active.store(false) }
        }
        if (completion == null) {
            observationPermits.release()
            error("Camel observation admission stopped")
        }
        completion.invokeOnCompletion { observationPermits.release() }
        completion.await()
    }

    suspend fun retain(handle: CamelRouteHandle, stop: suspend () -> Unit) {
        requireCommand()
        check(leases.none { it.handle.token === handle.token }) { "Camel context already owned" }
        leases.add(Lease(handle, stop))
    }

    suspend fun owns(handle: CamelRouteHandle): Boolean {
        requireCommand()
        return leases.any { it.handle.token === handle.token }
    }

    suspend fun ownedRoutes(): List<CamelRouteHandle> {
        requireCommand()
        return leases.map { it.handle }
    }

    suspend fun release(handle: CamelRouteHandle) {
        requireCommand()
        leases.removeAll { it.handle.token === handle.token }
    }

    suspend fun stop(handle: CamelRouteHandle): Boolean {
        requireCommand()
        val lease = leases.firstOrNull { it.handle.token === handle.token } ?: return false
        lease.stop()
        leases.remove(lease)
        return true
    }

    private suspend fun requireCommand() {
        check(currentCoroutineContext()[Invocation]?.let {
            it.owner === this && it.lane == Lane.COMMAND && it.active.load()
        } == true) {
            "Camel lifetime changes require an admitted command"
        }
    }

    private fun checkOwnedCallback(context: CoroutineContext) {
        check(!context[Invocation].contains(this)) {
            "A Camel callback cannot join its own command owner"
        }
    }

    fun stopAdmission() { accepting.store(false) }

    override suspend fun drain(): Unit = withContext(NonCancellable) {
        checkOwnedCallback(currentCoroutineContext())
        stopAdmission()
        lifecycle.withLock {
            if (state == ElementState.CLOSED) return@withLock
            state = ElementState.DRAINING
            commands.drain()
            var failure: Throwable? = null
            for (lease in leases.toList()) try {
                lease.stop()
                leases.remove(lease)
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
            }
            // A failed consumer stop still owns callbacks: keep their lane and dependencies live
            // for an explicit drain retry, but never reopen external command admission.
            failure?.let { throw it }
            try { observations.drain() }
            finally {
                supervisor.complete()
                supervisor.join()
                state = ElementState.CLOSED
            }
        }
    }

    override suspend fun close() = drain()
}
