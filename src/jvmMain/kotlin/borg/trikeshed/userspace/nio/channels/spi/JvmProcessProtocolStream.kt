package borg.trikeshed.userspace.nio.channels.spi

import kotlinx.coroutines.*
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

/**
 * Child-side byte effects only; framing, admission queues and dispatch live in
 * commonMain. At most one read and one write/half-close may be outstanding.
 * Cancellation revokes this endpoint, never a process or unrelated terminal I/O.
 */
class JvmProcessProtocolStream private constructor(
    private val read: (ByteArray) -> Int,
    private val write: (ByteArray) -> Unit,
    private val closeOutput: () -> Unit,
    private val closeEndpoints: () -> Unit,
) : ProcessProtocolStream {
    private enum class Direction { Input, Output }

    private val gate = Any()
    private var closed = false
    private var outputClosed = false
    private var reading: CompletableDeferred<Unit>? = null
    private var writing: CompletableDeferred<Unit>? = null

    private fun revoke() {
        synchronized(gate) { closed = true }
        // Channel/socket close is idempotent and does not acquire the native
        // read/write monitor. In particular this is not System.in.close().
        closeEndpoints()
    }

    private suspend fun <T> io(direction: Direction, halfClose: Boolean = false, operation: () -> T): T {
        val completion = synchronized(gate) {
            if (closed || direction == Direction.Output && outputClosed && !halfClose)
                throw ClosedChannelException()
            val pending = if (direction == Direction.Input) reading else writing
            check(pending == null) { "A protocol ${direction.name.lowercase()} operation is already outstanding" }
            CompletableDeferred<Unit>().also {
                if (direction == Direction.Input) reading = it else writing = it
            }
        }
        try {
            try {
                return processProtocolIo(::revoke, operation = operation)
            } finally {
                synchronized(gate) {
                    if (direction == Direction.Input) reading = null else writing = null
                    completion.complete(Unit)
                }
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException || !currentCoroutineContext().isActive) {
                // Our native operation has joined already. Join the other
                // direction too, without waiting for this operation itself.
                try { closeProtocol() }
                catch (caught: Throwable) { if (caught !== failure) failure.addSuppressed(caught) }
            }
            throw failure
        }
    }

    override suspend fun readProtocol(bytes: ByteArray): Int {
        require(bytes.isNotEmpty())
        return io(Direction.Input) { read(bytes) }
    }

    override suspend fun writeProtocol(bytes: ByteArray): Unit = io(Direction.Output) { write(bytes) }

    override suspend fun closeProtocolInput(): Unit = io(Direction.Output, halfClose = true) {
        closeOutput()
        synchronized(gate) { outputClosed = true }
    }

    override suspend fun closeProtocol(): Unit = withContext(NonCancellable) {
        val pending = synchronized(gate) {
            closed = true
            listOfNotNull(reading, writing)
        }
        val released = runCatching {
            withContext(processProtocolCloseDispatcher) { closeEndpoints() }
        }
        pending.forEach { it.await() }
        released.getOrThrow()
    }

    companion object {
        /**
         * Transfers the child process's actual fd 0/1 into this endpoint. Use
         * once, only for restricted stdio protocol workers, never native Python
         * terminal I/O or the hosting test runner. FileChannel implements
         * InterruptibleChannel; closing it wakes a blocked native operation.
         */
        fun stdio(): JvmProcessProtocolStream {
            val input = FileInputStream(FileDescriptor.`in`)
            var output: FileOutputStream? = null
            try {
                val sink = FileOutputStream(FileDescriptor.out).also { output = it }
                val inputChannel = input.channel
                val outputChannel = sink.channel
                return JvmProcessProtocolStream(
                    read = { inputChannel.read(ByteBuffer.wrap(it)) },
                    write = {
                        val bytes = ByteBuffer.wrap(it)
                        while (bytes.hasRemaining()) outputChannel.write(bytes)
                    },
                    closeOutput = outputChannel::close,
                    closeEndpoints = {
                        processProtocolClose(inputChannel::close, outputChannel::close)
                    },
                )
            } catch (failure: Throwable) {
                try { processProtocolClose(input::close, { output?.close() }) }
                catch (caught: Throwable) { if (caught !== failure) failure.addSuppressed(caught) }
                throw failure
            }
        }

        /** Takes ownership of an already-connected protocol socket; native stdio is untouched. */
        fun socket(socket: Socket): JvmProcessProtocolStream {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                return JvmProcessProtocolStream(
                    read = input::read,
                    write = { output.write(it); output.flush() },
                    closeOutput = { if (!socket.isOutputShutdown) socket.shutdownOutput() },
                    closeEndpoints = socket::close,
                )
            } catch (failure: Throwable) {
                try { socket.close() }
                catch (caught: Throwable) { if (caught !== failure) failure.addSuppressed(caught) }
                throw failure
            }
        }
    }
}
