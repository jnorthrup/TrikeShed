package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** The child closed stdout before answering an admitted exchange. */
internal class ProcessStdoutClosed : IllegalArgumentException("Process stdout closed before its response")
/** The child's stdin failed while writing an admitted exchange. */
internal class ProcessStdinFailed(cause: Throwable) : IllegalStateException("Process stdin failed before its response", cause)

/** Bounded UTF-8 line exchanges with one owned child; one response per admitted request. */
class ProcessLineChannel private constructor(
    parent: CoroutineScope,
    private val pipes: ProcessPipes,
    capacity: Int,
    private val frameBytes: Int,
    private val timeoutMs: Long,
    private val collectStderrOnFailure: Boolean,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ProcessLineChannel> {
        suspend fun open(scope: CoroutineScope, command: String, args: List<String> = emptyList(),
                         env: Map<String, String> = emptyMap(), capacity: Int = 8,
                         frameBytes: Int = 1_048_576, timeoutMs: Long = 120_000,
                         collectStderrOnFailure: Boolean = false): ProcessLineChannel {
            require(capacity > 0 && frameBytes > 0 && timeoutMs > 0)
            scope.coroutineContext.ensureActive()
            val ops = requireNotNull(scope.coroutineContext[ProcessOperations]) { "Missing ProcessOperations" }
            val pipes = ops.open(command, args, env)
            return try { ProcessLineChannel(scope, pipes, capacity, frameBytes, timeoutMs, collectStderrOnFailure) }
            catch (failure: Throwable) { pipes.close(); throw failure }
        }
    }
    override val key: CoroutineContext.Key<ProcessLineChannel> get() = Key
    private class Exchange(val bytes: ByteArray, val reply: CompletableDeferred<String>)
    private val input = Channel.buffered<Exchange>(capacity)
    private val supervisor = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + this + supervisor)
    private val completion = CompletableDeferred<Result<Unit>>()
    internal val protocolCompletion: Deferred<Result<Unit>> get() = completion
    /** Completes only after the owned pipes have actually closed, even if the protocol failed. */
    private val closed = CompletableDeferred<Unit>()
    internal val pipeCompletion: Deferred<Unit> get() = closed
    private val closeMutex = Mutex()
    private val stderr = ByteArray(8_192)
    private var stderrSize = 0
    private var stderrPosition = 0
    private val stdout = ByteArray(8_192)
    private var position = 0
    private var limit = 0

    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var active: Exchange? = null
        var failure: Throwable? = null
        try {
            coroutineScope {
                val errors = launch {
                    val chunk = ByteArray(4_096)
                    while (true) {
                        val count = pipes.read(ProcessOutput.Stderr, chunk)
                        if (count < 0) break
                        for (i in 0 until count) {
                            stderr[stderrPosition] = chunk[i]
                            stderrPosition = (stderrPosition + 1) % stderr.size
                            stderrSize = minOf(stderrSize + 1, stderr.size)
                        }
                    }
                }
                for (request in input.kChannel) {
                    active = request
                    val response = withTimeout(timeoutMs) {
                        try { pipes.write(request.bytes) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (!collectStderrOnFailure) throw failure
                            // A peer can revoke itself before consuming the request. Give
                            // its already-emitted stderr a bounded chance to be collected.
                            withTimeoutOrNull(minOf(timeoutMs, 250L)) { errors.join() }
                            throw ProcessStdinFailed(failure)
                        }
                        val line = readLine()
                        if (line == null) {
                            // A child may close stdout while retaining stderr; never wait for
                            // that stream beyond this short, bounded diagnostic opportunity.
                            if (collectStderrOnFailure) withTimeoutOrNull(minOf(timeoutMs, 250L)) { errors.join() }
                            throw ProcessStdoutClosed()
                        }
                        line
                    }
                    request.reply.complete(response)
                    active = null
                }
                withTimeout(timeoutMs) {
                    pipes.closeInput()
                    check(readLine() == null) { "Unsolicited process output after final response" }
                    val exit = pipes.awaitExit()
                    errors.join()
                    check(exit == 0) { "Process exited $exit: ${errorText()}" }
                }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            input.close()
            withContext(NonCancellable) {
                try { closePipes() } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            val error = failure ?: IllegalStateException("Process channel closed")
            active?.reply?.completeExceptionally(error)
            while (true) {
                val pending = input.kChannel.tryReceive().getOrNull() ?: break
                pending.reply.completeExceptionally(error)
            }
            completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
            supervisor.complete()
        }
    }

    suspend fun exchange(line: String): String {
        require('\n' !in line && '\r' !in line) { "Request must be one line" }
        val bytes = line.encodeToByteArray()
        require(bytes.size <= frameBytes) { "Request exceeds $frameBytes bytes" }
        val reply = CompletableDeferred<String>()
        input.send(Exchange(bytes + byteArrayOf(10), reply)).getOrThrow()
        // Cancelling a caller does not discard a request already admitted to the process.
        return reply.await()
    }

    suspend fun drain() = withContext(NonCancellable) {
        input.close()
        worker.join()
        supervisor.join()
        val result = completion.await()
        if (!closed.isCompleted) {
            try { closePipes() } catch (cleanup: Throwable) {
                val prior = result.exceptionOrNull()
                if (prior == null) throw cleanup
                if (prior !== cleanup) prior.addSuppressed(cleanup)
            }
        }
        result.getOrThrow()
    }

    private suspend fun closePipes() = closeMutex.withLock {
        if (!closed.isCompleted) { pipes.close(); closed.complete(Unit) }
    }

    /** Valid after completion: stderr is consumed concurrently, but retained in a fixed-size tail. */
    private fun errorText(): String = ByteArray(stderrSize) {
        stderr[(stderrPosition - stderrSize + it + stderr.size) % stderr.size]
    }.decodeToString()

    /** Read only after drain joined the worker and stderr reader. Never return the captured text. */
    internal fun stderrLine(marker: String): Boolean = errorText().lineSequence().any { it == marker }

    private suspend fun readLine(): String? {
        var bytes = ByteArray(minOf(frameBytes, 4_096))
        var size = 0
        while (true) {
            if (position == limit) {
                limit = pipes.read(ProcessOutput.Stdout, stdout)
                position = 0
                if (limit < 0) {
                    check(size == 0) { "Process closed stdout inside a frame" }
                    return null
                }
                if (limit == 0) { yield(); continue }
            }
            val byte = stdout[position++]
            if (byte == 10.toByte()) return bytes.decodeToString(0, size, throwOnInvalidSequence = true)
            check(size < frameBytes) { "Process response exceeds $frameBytes bytes" }
            if (size == bytes.size) bytes = bytes.copyOf(minOf(frameBytes, bytes.size * 2))
            bytes[size++] = byte
        }
    }
}
