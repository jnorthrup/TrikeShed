package borg.trikeshed.narsese

import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** Restore, scoring and probing share one publication lane; paid answers have a bounded fan-out. */
class CurationElement(
    scope: CoroutineScope,
    capacity: Int = 4,
    answerWorkers: Int = 48,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CurationElement>
    override val key: CoroutineContext.Key<CurationElement> get() = Key
    val supervisor = SupervisorJob(scope.coroutineContext[Job])
    val scope = CoroutineScope(fileIoContext + scope.coroutineContext + this + supervisor)
    val admission = Mutex()
    val draining = Mutex()
    var accepting = true
        private set
    val input = Channel.buffered<Work>(capacity.also { require(it > 0) })
    val places = Semaphore(capacity)
    val answers = Channel.buffered<Answer>(capacity)
    val answerPlaces = Semaphore(capacity)
    val claims = mutableMapOf<String, CompletableDeferred<Result<Map<String, Any?>>>>()
    val states = mutableMapOf<String, State>()
    val completion = CompletableDeferred<Unit>()
    class State(val gate: Mutex = Mutex(), var claims: Int = 0)

    class Work(val context: CoroutineContext, val block: suspend () -> Unit,
               val reply: CompletableDeferred<Result<Unit>>, val failed: (Throwable) -> Unit)
    class Answer(val identity: String, val stateIdentity: String, val state: State, val context: CoroutineContext, val block: suspend () -> Map<String, Any?>,
                 val reply: CompletableDeferred<Result<Map<String, Any?>>>)

    val worker = this.scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
        while (true) {
            val work = input.recv().getOrNull() ?: break
            places.release()
            val result = runCatching { withContext(work.context.minusKey(Job) + this@CurationElement) { work.block() } }
            work.reply.complete(result)
            result.exceptionOrNull()?.let { runCatching { work.failed(it) } }
        }
    } }
    val answerJobs = List(answerWorkers.also { require(it > 0) }) {
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
            while (true) {
                val work = answers.recv().getOrNull() ?: break
                answerPlaces.release()
                val result = runCatching { work.state.gate.withLock {
                    withContext(work.context.minusKey(Job) + this@CurationElement) { work.block() }
                } }
                admission.withLock {
                    work.reply.complete(result)
                    claims.remove(work.identity)
                    work.state.claims--
                    if (work.state.claims == 0) states.remove(work.stateIdentity)
                }
            }
        } }
    }
    /** Parent cancellation closes admission and awaits every accepted effect while remaining a supervised child. */
    val cancellation = this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() }
        finally { withContext(NonCancellable) { closeLanes() } }
    }

    /** Admission completes before returning; cancellation of a waiter never cancels accepted work. */
    suspend fun submit(context: CoroutineContext? = null,
                       failed: (Throwable) -> Unit = {}, admissible: () -> Boolean = { true },
                       block: suspend () -> Unit): Deferred<Result<Unit>> {
        val workContext = context ?: currentCoroutineContext()
        places.acquire()
        try {
            return admission.withLock {
                check(accepting && supervisor.isActive && admissible()) { "curation is not accepting work" }
                val reply = CompletableDeferred<Result<Unit>>()
                check(input.kChannel.trySend(Work(workContext, block, reply, failed)).isSuccess)
                reply
            }
        } catch (failure: Throwable) {
            places.release()
            throw failure
        }
    }

    /** Boot admission is synchronous, so registration cannot leave unowned restore or probe work behind. */
    fun offer(context: CoroutineContext = scope.coroutineContext,
              failed: (Throwable) -> Unit = {}, block: suspend () -> Unit): Deferred<Result<Unit>> {
        check(places.tryAcquire()) { "curation admission is full" }
        if (!admission.tryLock()) { places.release(); error("curation admission is busy") }
        try {
            check(accepting && supervisor.isActive) { "curation is not accepting work" }
            val reply = CompletableDeferred<Result<Unit>>()
            check(input.kChannel.trySend(Work(context, block, reply, failed)).isSuccess)
            return reply
        } catch (failure: Throwable) {
            places.release()
            throw failure
        } finally { admission.unlock() }
    }

    /** Identical misses share both success and failure. A later attempt may retry a failed claim. */
    suspend fun answer(identity: String, stateIdentity: String = identity, admissible: () -> Boolean = { true },
                       block: suspend () -> Map<String, Any?>): Map<String, Any?> {
        val context = currentCoroutineContext()
        suspend fun joined(reply: CompletableDeferred<Result<Map<String, Any?>>>): Map<String, Any?> {
            val result = reply.await().getOrThrow()
            return if ("asked" in result) result + mapOf("asked" to 0,
                "usage" to mapOf("input_tokens" to 0, "output_tokens" to 0)) else result
        }
        val standing = admission.withLock {
            check(context[Key] === this || accepting && supervisor.isActive && admissible()) { "curation is not accepting work" }
            claims[identity]
        }
        if (standing != null) return joined(standing)
        answerPlaces.acquire()
        var enqueued = false
        val reply = try {
            admission.withLock {
                check(context[Key] === this || accepting && supervisor.isActive && admissible()) { "curation is not accepting work" }
                claims[identity]?.also { answerPlaces.release() } ?: run {
                    val result = CompletableDeferred<Result<Map<String, Any?>>>()
                    val state = states.getOrPut(stateIdentity) { State() }
                    check(answers.kChannel.trySend(Answer(identity, stateIdentity, state, context, block, result)).isSuccess)
                    state.claims++
                    enqueued = true
                    claims[identity] = result
                    result
                }
            }
        } catch (failure: Throwable) {
            if (!enqueued) answerPlaces.release()
            throw failure
        }
        return if (enqueued) reply.await().getOrThrow() else joined(reply)
    }

    suspend fun stopAdmission() = admission.withLock { accepting = false; input.close() }

    private suspend fun closeLanes() = draining.withLock {
        stopAdmission()
        worker.join()
        admission.withLock { answers.close() }
        answerJobs.joinAll()
        completion.complete(Unit)
    }

    /** Finish admitted tasks before closing their answer lane. No cancellation substitutes for completion. */
    suspend fun drain() = withContext(NonCancellable) {
        closeLanes()
        // The watcher also enters closeLanes in finally; never join it while holding the drain mutex.
        cancellation.cancelAndJoin()
        supervisor.complete()
        supervisor.join()
    }

    /** A single parser feeds fixed section workers; results fan in before the caller publishes a book. */
    suspend fun <S, P, T> sections(
        source: suspend (suspend (S) -> Unit) -> Unit,
        parse: suspend (S) -> P,
        table: suspend (P) -> T,
        collect: suspend (T) -> Unit,
        workers: Int = 4,
        capacity: Int = 2,
    ) = supervisorScope {
        check(currentCoroutineContext()[Key] === this@CurationElement) { "section dispatch requires admitted curation work" }
        val input = Channel.buffered<S>(capacity)
        val parsed = Channel.buffered<P>(capacity)
        val output = Channel.buffered<Result<T>>(capacity)
        val failure = CompletableDeferred<Throwable>()
        val producer = launch {
            try { source { if (!failure.isCompleted) input.send(it).getOrThrow() } }
            catch (e: Throwable) { failure.complete(e) }
            finally { input.close() }
        }
        val parser = launch {
            try {
                while (true) {
                    val section = input.recv().getOrNull() ?: break
                    try { parsed.send(parse(section)).getOrThrow() }
                    catch (e: Throwable) { failure.complete(e) }
                }
            } finally { parsed.close() }
        }
        val branches = List(workers.also { require(it > 0) }) { launch {
            while (true) {
                val section = parsed.recv().getOrNull() ?: break
                val result = runCatching { table(section) }
                result.exceptionOrNull()?.let { failure.complete(it) }
                output.send(result).getOrThrow()
            }
        } }
        val joined = launch { branches.joinAll(); output.close() }
        while (true) {
            val result = output.recv().getOrNull() ?: break
            result.onSuccess { try { collect(it) } catch (e: Throwable) { failure.complete(e) } }
        }
        producer.join(); parser.join(); joined.join()
        if (failure.isCompleted) throw failure.await()
    }

    /** Bounded window dispatch and fan-in, without one coroutine or retained reply per window. */
    suspend fun <T> windows(count: Int, ask: suspend (Int) -> T, collect: suspend (Int, T) -> Unit,
                            workers: Int = 12) = supervisorScope {
        check(currentCoroutineContext()[Key] === this@CurationElement) { "window dispatch requires admitted curation work" }
        val input = Channel.buffered<Int>(2)
        val output = Channel.buffered<Pair<Int, Result<T>>>(2)
        val failure = CompletableDeferred<Throwable>()
        val producer = launch {
            try { for (i in 0 until count) if (!failure.isCompleted) input.send(i).getOrThrow() }
            finally { input.close() }
        }
        val branches = List(workers.also { require(it > 0) }) { launch {
            while (true) {
                val i = input.recv().getOrNull() ?: break
                val result = runCatching { ask(i) }
                result.exceptionOrNull()?.let { failure.complete(it) }
                output.send(i to result).getOrThrow()
            }
        } }
        val joined = launch { branches.joinAll(); output.close() }
        while (true) {
            val (i, result) = output.recv().getOrNull() ?: break
            result.onSuccess { try { collect(i, it) } catch (e: Throwable) { failure.complete(e) } }
        }
        producer.join(); joined.join()
        if (failure.isCompleted) throw failure.await()
    }
}
