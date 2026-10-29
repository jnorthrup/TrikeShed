@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.vm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.atomics.*
import kotlin.coroutines.CoroutineContext

/** Ordered VM requests with independently serviced host callbacks and an acknowledged process drain. */
class ProcessVmChannel(
    context: CoroutineContext,
    pipes: ProcessProtocolPipes,
    private val invokeHost: suspend (() -> Teleported) -> Teleported,
    capacity: Int = 16,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ProcessVmChannel>
    override val key: CoroutineContext.Key<*> get() = Key

    private class Request(val message: Teleported.Obj) {
        val result = CompletableDeferred<Result<Teleported>>()
    }
    private class Pending(val id: Int) { val reply = CompletableDeferred<Teleported.Obj>() }
    init { require(capacity > 0) }
    private val connected = AtomicBoolean(true)
    private val separateProtocol = pipes.separateProtocol
    private val revoked = AtomicBoolean(false)
    private val pending = AtomicReference<Pending?>(null)
    private val delegates = AtomicReference<Map<String, (List<Teleported>) -> Teleported>>(emptyMap())
    private val ids = AtomicInt(1)
    private val evals = AtomicLong(0)
    private val calls = AtomicLong(0)
    private val hostCalls = AtomicLong(0)
    private val interrupts = AtomicLong(0)
    private val input = Channel<Request>(capacity, onUndeliveredElement = {
        it.result.complete(Result.failure(VmGuestException("DEAD", "Process request was not dispatched")))
    })
    private val supervisor = SupervisorJob(context[Job])
    private val scope = CoroutineScope(context + supervisor + this)
    private val completion = CompletableDeferred<Result<Unit>>()
    /** The first [drain] claims it to close guest input once, then [input]. */
    private val initiation: CompletableJob = Job()
    private val initiationFailure = AtomicReference<Throwable?>(null)
    private val protocol = CompletableDeferred<ProcessProtocolChannel>()
    private val callbacks = ProcessCalls(scope.coroutineContext, concurrency = 1, capacity = capacity) { response ->
        val message = response.b.fold(
            { Teleported.obj("id" to response.a, "value" to it) },
            { Teleported.obj("id" to response.a, "error" to (it.message ?: it.toString())) },
        )
        try { protocol.await().send(SubVmProtocol.encode(message)) }
        catch (failure: Throwable) { if (connected.load()) throw failure }
    }
    private val transport = ProcessProtocolChannel(scope.coroutineContext, pipes, capacity,
        onLine = { line ->
            val message = if (line.isBlank()) null else runCatching { SubVmProtocol.decode(line) }.getOrNull()
            if (message != null) when (message.str("op")) {
                "host" -> {
                    hostCalls.fetchAndAdd(1)
                    val id = message.int("id") ?: -1
                    val name = message.str("name") ?: ""
                    val callback = delegates.load()[name]
                    val args = (message.field("args") as? Teleported.Arr)?.v.orEmpty()
                    if (callback == null) send(SubVmProtocol.encode(Teleported.obj("id" to id, "error" to "no delegate '$name'")))
                    else if (callbacks.trySubmit(id, name, null) { invokeHost { callback(args) } } == null)
                        send(SubVmProtocol.encode(Teleported.obj("id" to id, "error" to "host capacity exhausted or draining")))
                }
                "terminal" -> Unit // This VM API has no terminal sink; frames must not become call replies.
                else -> pending.load()?.takeIf { it.id == message.int("id") }?.reply?.complete(message)
            }
        },
        onEnd = {
            connected.store(false)
            pending.load()?.reply?.completeExceptionally(VmGuestException("DEAD", "Process closed before its reply"))
            input.close()
        }).also { protocol.complete(it) }

    val processId: Long get() = transport.processId
    val isAlive: Boolean get() = !completion.isCompleted && connected.load() && transport.isAlive
    fun stats() = VmStats(evals.load(), calls.load(), hostCalls.load(), interrupted = interrupts.load())

    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var active: Request? = null
        var failure: Throwable? = null
        try {
            for (request in input) {
                active = request
                val result = try { Result.success(exchange(request.message)) }
                catch (cause: Throwable) {
                    currentCoroutineContext().ensureActive()
                    Result.failure(cause)
                }
                request.result.complete(result)
                active = null
            }
            if (!revoked.load() && isAlive) {
                exchange(Teleported.obj("op" to "close"))
                transport.closeProtocolInput()
            }
            if (revoked.load()) runCatching { transport.drain() } else transport.drain()
        } catch (cause: Throwable) {
            failure = cause
        } finally {
            input.cancel()
            active?.result?.complete(Result.failure(failure ?: VmGuestException("DEAD", "Process closed")))
            withContext(NonCancellable) {
                suspend fun release(operation: suspend () -> Unit) {
                    try { operation() }
                    catch (cause: Throwable) {
                        val first = failure
                        if (first == null) failure = cause else if (first !== cause) first.addSuppressed(cause)
                    }
                }
                // Only failed/revoked paths can still have a live child here. Normal close awaited its exit.
                if (transport.isAlive) release { transport.revoke(0) }
                release { callbacks.drain() }
                release { if (revoked.load()) runCatching { transport.drain() } else transport.drain() }
            }
            initiationFailure.load()?.let { cause ->
                val first = failure
                if (first == null) failure = cause else if (first !== cause) first.addSuppressed(cause)
            }
            completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
            supervisor.complete()
        }
    }

    suspend fun eval(source: String, name: String = "<eval>"): Teleported {
        evals.fetchAndAdd(1)
        return request(Teleported.obj("op" to "eval", "source" to source, "name" to name))
    }

    suspend fun call(root: String, args: List<Teleported>): Teleported {
        calls.fetchAndAdd(1)
        return request(Teleported.obj("op" to "call", "root" to root, "args" to args))
    }

    suspend fun delegate(name: String, callback: (List<Teleported>) -> Teleported) {
        while (true) {
            val before = delegates.load()
            if (delegates.compareAndSet(before, before + (name to callback))) break
        }
        request(Teleported.obj("op" to "delegate", "name" to name))
    }

    private suspend fun request(message: Teleported.Obj): Teleported {
        val request = Request(message)
        input.send(request)
        // Cancellation abandons only this waiter, never an already admitted guest effect.
        return request.result.await().getOrThrow()
    }

    private suspend fun exchange(message: Teleported.Obj): Teleported {
        check(connected.load() && transport.isAlive) { "Process VM is dead" }
        val id = ids.fetchAndAdd(1)
        val response = Pending(id)
        check(pending.compareAndSet(null, response)) { "Process request ordering was violated" }
        try {
            transport.send(SubVmProtocol.encode(Teleported.Obj(message.v + ("id" to Teleported.Num(id.toLong())))))
            val reply = response.reply.await()
            if (reply.bool("ok") != true)
                throw VmGuestException(reply.str("kind") ?: "GUEST_ERROR", reply.str("error") ?: "guest error")
            return reply.field("value") ?: Teleported.Null
        } finally { pending.compareAndSet(response, null) }
    }

    /** Revocation bypasses the request queue; a blocked guest must not prevent its own termination. */
    suspend fun interrupt(): Boolean = withContext(NonCancellable) {
        if (!transport.isAlive) return@withContext false
        interrupts.fetchAndAdd(1)
        revoked.store(true)
        input.close()
        transport.revoke(0)
        true
    }

    suspend fun drain(): Unit = withContext(NonCancellable) {
        if (initiation.complete()) {
            try {
                // Unblock guest input before joining an admitted eval or a full request queue.
                if (!revoked.load() && isAlive) {
                    if (separateProtocol) transport.closeInput()
                    else transport.send(SubVmProtocol.encode(Teleported.obj("op" to "stdin-close")))
                }
            } catch (cause: Throwable) { initiationFailure.compareAndSet(null, cause) }
            finally { input.close() }
        }
        worker.join()
        supervisor.join()
        completion.await().getOrThrow()
    }
}
