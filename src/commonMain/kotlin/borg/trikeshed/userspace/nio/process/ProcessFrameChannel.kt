package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/** Duplex UTF-8 framing shared by parent and child; neither direction waits on guest execution. */
class ProcessFrameChannel(
    context: CoroutineContext,
    private val stream: ProcessProtocolStream,
    capacity: Int = 16,
    private val onLine: suspend ProcessFrameChannel.(String) -> Unit,
    private val onEnd: suspend () -> Unit = {},
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ProcessFrameChannel>
    override val key: CoroutineContext.Key<*> get() = Key

    private val output = ProcessProtocolWriter(capacity)
    private val supervisor = SupervisorJob(context[Job])
    private val scope = CoroutineScope(context + supervisor + this)
    private val completion = CompletableDeferred<Result<Unit>>()
    private var ended = false
    private suspend fun end() { if (!ended) { ended = true; onEnd() } }

    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var failure: Throwable? = null
        try {
            coroutineScope {
                launch { output.run(stream::writeProtocol, stream::closeProtocolInput) }
                launch {
                    try { readFrames() }
                    finally { output.end(); end() }
                }
            }
        } catch (cause: Throwable) {
            failure = cause
        } finally {
            withContext(NonCancellable) {
                try { end() }
                catch (cause: Throwable) { if (failure == null) failure = cause else failure.addSuppressed(cause) }
                try { stream.closeProtocol() }
                catch (cause: Throwable) { if (failure == null) failure = cause else failure.addSuppressed(cause) }
            }
            val result = failure?.let { Result.failure<Unit>(it) } ?: Result.success(Unit)
            output.complete(result)
            completion.complete(result)
            supervisor.complete()
        }
    }

    suspend fun send(line: String) {
        require('\n' !in line && '\r' !in line) { "Process protocol requires one line" }
        output.send((line + "\n").encodeToByteArray())
    }

    /** Flush admitted frames and half-close output; incoming replies remain readable. */
    suspend fun closeInput(): Unit = withContext(NonCancellable) { output.close() }

    internal suspend fun stopInput() = output.stop()

    /** Wait for peer EOF and joined cleanup without closing outgoing replies prematurely. */
    suspend fun awaitClosed() {
        worker.join()
        supervisor.join()
        completion.await().getOrThrow()
    }

    suspend fun drain(): Unit = withContext(NonCancellable) {
        output.stop()
        awaitClosed()
    }

    private suspend fun readFrames() {
        val chunk = ByteArray(8_192)
        var frame = ByteArray(4_096)
        var size = 0
        var carriageReturn = false
        suspend fun emit() {
            onLine(frame.decodeToString(0, size))
            size = 0
        }
        while (true) {
            val count = stream.readProtocol(chunk)
            if (count < 0) {
                if (size != 0) emit() // BufferedReader compatibility includes a final unterminated frame.
                return
            }
            if (count == 0) { yield(); continue }
            for (i in 0 until count) {
                val byte = chunk[i]
                if (carriageReturn && byte == 10.toByte()) { carriageReturn = false; continue }
                carriageReturn = byte == 13.toByte()
                if (carriageReturn || byte == 10.toByte()) { emit(); continue }
                if (size == frame.size) {
                    check(size < Int.MAX_VALUE) { "Process protocol frame exceeds addressable bytes" }
                    frame = frame.copyOf(minOf(Int.MAX_VALUE.toLong(), frame.size.toLong() * 2).toInt())
                }
                frame[size++] = byte
            }
        }
    }
}

/** Admission and acknowledgements for one writer coroutine owned by the surrounding composition. */
internal class ProcessProtocolWriter(capacity: Int) {
    init { require(capacity > 0) }
    private class Write(val bytes: ByteArray) { val completed = CompletableDeferred<Unit>() }
    private val admission = Mutex()
    private var accepting = true
    private val completed = CompletableDeferred<Result<Unit>>()
    private val queue = Channel<Write>(capacity, onUndeliveredElement = {
        it.completed.completeExceptionally(CancellationException("Process transport closed before write"))
    })

    suspend fun run(write: suspend (ByteArray) -> Unit, close: suspend () -> Unit) {
        var active: Write? = null
        var failure: Throwable? = null
        try {
            for (next in queue) {
                active = next
                write(next.bytes)
                next.completed.complete(Unit)
                active = null
            }
            close()
        } catch (cause: Throwable) {
            failure = cause
            throw cause
        } finally {
            active?.completed?.completeExceptionally(failure ?: CancellationException("Process writer closed"))
            complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
        }
    }

    suspend fun send(bytes: ByteArray) {
        val write = Write(bytes)
        admission.withLock {
            check(accepting) { "Process transport is draining" }
            queue.send(write)
        }
        // Cancellation of the waiter does not discard an admitted write.
        write.completed.await()
    }

    fun end() { queue.close() }
    suspend fun stop() = admission.withLock { accepting = false; end() }
    suspend fun close() { stop(); completed.await().getOrThrow() }
    fun complete(result: Result<Unit>) { queue.cancel(); completed.complete(result) }
}
