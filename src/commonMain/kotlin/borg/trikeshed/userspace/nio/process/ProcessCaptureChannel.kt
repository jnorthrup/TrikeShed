@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.userspace.nio.process

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** A finite process's merged output, retaining its head while counting every drained byte. */
data class ProcessCapture(val exit: Int, val bytes: ByteArray, val total: Long, val timedOut: Boolean) {
    val kept: Long get() = bytes.size.toLong()
    val dropped: Long get() = total - kept
    fun text(): String = bytes.decodeToString()
}

/**
 * Raw process input, bounded output delivery and exit fan-in under one owning supervisor.
 * The platform opens these finite-process pipes with stderr merged into stdout.
 */
class ProcessCaptureChannel(
    context: CoroutineContext,
    private val pipes: ProcessProtocolPipes,
    stdin: ByteArray? = null,
    private val maxBytes: Int = 4_194_304,
    private val timeoutMillis: Long = 120_000,
    capacity: Int = 8,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ProcessCaptureChannel>
    override val key: CoroutineContext.Key<*> get() = Key

    init { require(maxBytes >= 0 && timeoutMillis > 0 && capacity > 0) }
    private val input = stdin?.copyOf()
    private val revoked = AtomicBoolean(false)
    private val chunks = Channel.buffered<ByteArray>(capacity)
    private val supervisor = SupervisorJob(context[Job])
    private val scope = CoroutineScope(context.minusKey(Job) + supervisor + this)
    private val completion = CompletableDeferred<Result<ProcessCapture>>()
    val processId: Long get() = pipes.processId

    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var failure: Throwable? = null
        var captured: ProcessCapture? = null
        try {
            captured = coroutineScope {
                val collector = async {
                    var bytes = ByteArray(minOf(maxBytes, 8_192))
                    var size = 0
                    var total = 0L
                    for (chunk in chunks.kChannel) {
                        check(total <= Long.MAX_VALUE - chunk.size) { "Process byte count overflow" }
                        total += chunk.size
                        val take = minOf(chunk.size, maxBytes - size)
                        if (take > 0) {
                            if (size + take > bytes.size)
                                bytes = bytes.copyOf(minOf(maxBytes.toLong(), maxOf((size + take).toLong(), bytes.size.toLong() * 2)).toInt())
                            chunk.copyInto(bytes, size, 0, take)
                            size += take
                        }
                    }
                    bytes.copyOf(size) j total
                }
                val reader = async {
                    try {
                        val bytes = ByteArray(8_192)
                        while (true) {
                            val count = pipes.read(ProcessOutput.Stdout, bytes)
                            if (count < 0) break
                            if (count == 0) { yield(); continue }
                            chunks.send(bytes.copyOf(count)).getOrThrow()
                        }
                    } catch (caught: Throwable) {
                        currentCoroutineContext().ensureActive()
                        if (caught is CancellationException || !revoked.load()) throw caught
                    } finally { chunks.close() }
                }
                val writer = async {
                    try { input?.let { pipes.write(it) } }
                    catch (caught: Throwable) {
                        currentCoroutineContext().ensureActive()
                        if (caught is CancellationException || !revoked.load() && pipes.isAlive) throw caught
                    } finally {
                        try { pipes.closeInput() }
                        catch (caught: Throwable) {
                            currentCoroutineContext().ensureActive()
                            if (caught is CancellationException || !revoked.load() && pipes.isAlive) throw caught
                        }
                    }
                }
                val exit = async { pipes.awaitExit() }
                // Only the waiter times out. Readers and the collector drain the revoked pipes to EOF.
                val finished = withTimeoutOrNull(timeoutMillis) {
                    exit.await()
                    writer.await()
                    reader.await()
                    true
                } ?: false
                if (!finished) withContext(NonCancellable) { revoked.store(true); pipes.revoke(0) }
                val code = exit.await()
                writer.await()
                reader.await()
                val output = collector.await()
                ProcessCapture(if (finished) code else -1, output.a, output.b, !finished)
            }
        } catch (caught: Throwable) { failure = caught }
        finally {
            chunks.kChannel.cancel()
            withContext(NonCancellable) {
                suspend fun release(action: suspend () -> Unit) {
                    try { action() }
                    catch (caught: Throwable) {
                        if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
                    }
                }
                if (pipes.isAlive) release { pipes.revoke(0) }
                release { pipes.close() }
            }
            completion.complete(failure?.let { Result.failure(it) } ?: Result.success(checkNotNull(captured)))
            supervisor.complete()
        }
    }

    /** Caller cancellation is explicit revocation; return only after pipe stages and the child are joined. */
    suspend fun await(): ProcessCapture = try {
        completion.await().getOrThrow()
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            try { revoked.store(true); pipes.revoke(0) } catch (cleanup: Throwable) { if (cleanup !== cancelled) cancelled.addSuppressed(cleanup) }
        }
        throw cancelled
    } finally {
        withContext(NonCancellable) { worker.join(); supervisor.join() }
    }

    /** Finish the admitted process and all byte stages; ordinary drain never kills it. */
    suspend fun drain(): Unit = withContext(NonCancellable) {
        worker.join()
        supervisor.join()
        completion.await().getOrThrow()
        Unit
    }
}
