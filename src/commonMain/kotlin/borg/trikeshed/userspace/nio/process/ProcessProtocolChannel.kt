package borg.trikeshed.userspace.nio.process

import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/** Multiplexed frames and physical terminal streams, with one joined child-process lifetime. */
class ProcessProtocolChannel(
    context: CoroutineContext,
    private val pipes: ProcessProtocolPipes,
    capacity: Int = 16,
    private val onLine: suspend ProcessProtocolChannel.(String) -> Unit,
    private val onTerminal: suspend (ProcessOutput, ByteArray) -> Unit = { _, _ -> },
    private val onEnd: () -> Unit = {},
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ProcessProtocolChannel>
    override val key: CoroutineContext.Key<*> get() = Key

    init { require(capacity > 0) }
    private val terminal = ProcessProtocolWriter(capacity)
    private val protocol = CompletableDeferred<ProcessFrameChannel>()
    private val supervisor = SupervisorJob(context[Job])
    private val scope = CoroutineScope(context + supervisor + this)
    private val completion = CompletableDeferred<Result<Unit>>()
    private var ended = false
    private fun end() { if (!ended) { ended = true; onEnd() } }
    val processId: Long get() = pipes.processId
    val isAlive: Boolean get() = pipes.isAlive

    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var failure: Throwable? = null
        try {
            coroutineScope {
                val frames = ProcessFrameChannel(coroutineContext, pipes, capacity,
                    onLine = { this@ProcessProtocolChannel.onLine(it) },
                    onEnd = { terminal.end(); end() })
                protocol.complete(frames)
                launch { terminal.run(pipes::write) { if (pipes.separateProtocol) pipes.closeInput() } }
                for (output in pipes.terminalOutputs) launch {
                    val bytes = ByteArray(8_192)
                    while (true) {
                        val count = pipes.read(output, bytes)
                        if (count < 0) break
                        if (count == 0) { yield(); continue }
                        onTerminal(output, bytes.copyOf(count))
                    }
                }
                frames.awaitClosed()
                pipes.awaitExit()
            }
        } catch (cause: Throwable) {
            failure = cause
        } finally {
            withContext(NonCancellable) {
                try { pipes.close() }
                catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                }
            }
            val result = failure?.let { Result.failure<Unit>(it) } ?: Result.success(Unit)
            terminal.complete(result)
            protocol.completeExceptionally(failure ?: CancellationException("Process transport closed"))
            try { end() }
            catch (cause: Throwable) { if (failure == null) failure = cause else failure.addSuppressed(cause) }
            completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
            supervisor.complete()
        }
    }

    suspend fun send(line: String) = protocol.await().send(line)

    suspend fun writeInput(bytes: ByteArray) {
        check(pipes.separateProtocol) { "Terminal input uses protocol frames on this worker" }
        terminal.send(bytes.copyOf())
    }

    /** Terminal EOF is independent of protocol replies needed by already admitted guest work. */
    suspend fun closeInput(): Unit = withContext(NonCancellable) { terminal.close() }

    suspend fun closeProtocolInput() = protocol.await().closeInput()

    /** Complete the close handshake first. Drain has no kill timer. */
    suspend fun drain(): Unit = withContext(NonCancellable) {
        terminal.end()
        try { protocol.await().stopInput() }
        finally { worker.join(); supervisor.join() }
        completion.await().getOrThrow()
    }

    suspend fun revoke(graceMillis: Long): Unit = withContext(NonCancellable) {
        require(graceMillis >= 0)
        pipes.revoke(graceMillis)
    }
}
