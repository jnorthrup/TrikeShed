package borg.trikeshed.userspace.nio.channels.spi

import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// A full IO pool of native reads must not prevent their own revocation from running.
internal val processProtocolCloseDispatcher = Dispatchers.IO.limitedParallelism(Int.MAX_VALUE)

/** Physical process pipes with either stdio or an authenticated socket for the protocol. */
class JvmProcessProtocolPipes private constructor(
    private val process: Process,
    private val protocol: Socket?,
    override val terminalOutputs: Set<ProcessOutput>,
) : ProcessProtocolPipes {
    override val processId: Long = process.pid()
    override val isAlive: Boolean get() = process.isAlive
    override val separateProtocol: Boolean get() = protocol != null

    private suspend fun <T> io(operation: () -> T): T =
        processProtocolIo(::revokeBlocking, ::closeBlocking, operation)

    override suspend fun write(bytes: ByteArray): Unit = io {
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }

    override suspend fun read(output: ProcessOutput, bytes: ByteArray): Int {
        require(bytes.isNotEmpty())
        return io {
            when (output) {
                ProcessOutput.Stdout -> process.inputStream.read(bytes)
                ProcessOutput.Stderr -> process.errorStream.read(bytes)
            }
        }
    }

    override suspend fun closeInput(): Unit = io { process.outputStream.close() }

    override suspend fun readProtocol(bytes: ByteArray): Int {
        require(bytes.isNotEmpty())
        return io { (protocol?.getInputStream() ?: process.inputStream).read(bytes) }
    }

    override suspend fun writeProtocol(bytes: ByteArray): Unit = io {
        val output = protocol?.getOutputStream() ?: process.outputStream
        output.write(bytes)
        output.flush()
    }

    override suspend fun closeProtocolInput(): Unit = io {
        if (protocol == null) process.outputStream.close()
        else if (!protocol.isOutputShutdown) protocol.shutdownOutput()
    }

    /**
     * Release only the protocol endpoints, never the child or its terminal pipes.
     * For stdio, the common owner joins protocol I/O first: Process streams can
     * hold buffered-stream monitors during native reads. Revocation remains a
     * separate, explicit operation when that child is still blocked.
     */
    override suspend fun closeProtocol(): Unit = withContext(NonCancellable + processProtocolCloseDispatcher) {
        if (protocol != null) protocol.close()
        else processProtocolClose({ process.outputStream.close() }, { process.inputStream.close() })
    }

    override suspend fun awaitExit(): Int = io { process.waitFor() }

    override suspend fun revoke(graceMillis: Long) {
        require(graceMillis >= 0)
        withContext(NonCancellable + processProtocolCloseDispatcher) {
            processProtocolClose({ process.reapProtocol(graceMillis) }, ::closeBlocking)
        }
    }

    override suspend fun close(): Unit = withContext(NonCancellable + processProtocolCloseDispatcher) { closeBlocking() }

    private fun revokeBlocking() = processProtocolClose({ protocol?.close() }, { process.reapProtocol() })

    private fun closeBlocking() = processProtocolClose({ protocol?.close() }, { process.closeProtocolPipes() })

    companion object {
        suspend fun open(
            command: (port: Int?, token: String?) -> List<String>,
            environment: Map<String, String>,
            workingDirectory: String? = null,
            protocolTimeoutMillis: Long? = null,
            inheritError: Boolean = false,
            mergeError: Boolean = false,
        ): JvmProcessProtocolPipes {
            require(protocolTimeoutMillis == null || protocolTimeoutMillis > 0)
            var listener: JvmProcessProtocolSocket? = null
            var process: Process? = null
            var protocol: Socket? = null
            try {
                return withContext(Dispatchers.IO) {
                    val transport = protocolTimeoutMillis?.let(::JvmProcessProtocolSocket).also { listener = it }
                    val arguments = command(transport?.port, transport?.token)
                    require(arguments.isNotEmpty() && arguments[0].isNotBlank()) { "Process command is missing" }
                    val builder = ProcessBuilder(arguments)
                    builder.environment().apply { clear(); putAll(environment) }
                    workingDirectory?.let { builder.directory(File(it)) }
                    if (inheritError) builder.redirectError(ProcessBuilder.Redirect.INHERIT)
                    builder.redirectErrorStream(mergeError)
                    ensureActive()
                    val child = builder.start().also { process = it }
                    ensureActive()
                    val socket = transport?.accept(child).also { protocol = it }
                    transport?.close()
                    JvmProcessProtocolPipes(child, socket, buildSet {
                        if (socket != null) add(ProcessOutput.Stdout)
                        if (!inheritError && !mergeError) add(ProcessOutput.Stderr)
                    })
                }
            } catch (failure: Throwable) {
                // Own every allocation until withContext successfully delivers the adapter.
                processProtocolClose(failure) {
                    processProtocolClose({ listener?.close() }, { protocol?.close() }, { process?.closeProtocolPipes() })
                }
                throw failure
            }
        }
    }
}

/** Separate protocol transport: native writes to fd 1/2 remain terminal bytes. */
class JvmProcessProtocolSocket(private val timeoutMillis: Long) : AutoCloseable {
    init { require(timeoutMillis > 0) }

    val token: String = UUID.randomUUID().toString()
    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = listener.localPort
    private var closed = false
    private var connection: Socket? = null

    suspend fun accept(process: Process): Socket {
        var accepted: Socket? = null
        try {
            val socket = processProtocolIo(::close) {
                val start = System.nanoTime()
                val timeout = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                var socket: Socket? = null
                while (socket == null) {
                    if (!process.isAlive) {
                        val stderr = process.errorStream
                        error("Process exited ${process.exitValue()}: " +
                            stderr.readNBytes(stderr.available()).decodeToString())
                    }
                    val remaining = TimeUnit.NANOSECONDS.toMillis(timeout - (System.nanoTime() - start))
                    check(remaining > 0) { "Process did not open its protocol within ${timeoutMillis}ms" }
                    listener.soTimeout = remaining.coerceAtMost(250).coerceAtLeast(1).toInt()
                    try { socket = listener.accept() }
                    catch (_: SocketTimeoutException) { /* Check process/deadline. */ }
                }
                val connected = requireNotNull(socket)
                synchronized(this) {
                    // Cancellation can close the listener between accept() and registration.
                    if (closed) {
                        connected.close()
                        error("Process protocol listener is closed")
                    }
                    connection = connected
                }
                require(connected.inetAddress.isLoopbackAddress) { "Process protocol peer is not loopback" }
                connected.soTimeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                require(DataInputStream(connected.getInputStream()).readUTF() == token) {
                    "Process protocol identity mismatch"
                }
                connected.soTimeout = 0
                connected.tcpNoDelay = true
                connected.also { accepted = it }
            }
            synchronized(this) { connection = null }
            return socket
        } catch (failure: Throwable) {
            processProtocolClose(failure) { processProtocolClose({ close() }, { accepted?.close() }) }
            throw failure
        }
    }

    override fun close() {
        val socket = synchronized(this) { closed = true; connection.also { connection = null } }
        processProtocolClose({ listener.close() }, { socket?.close() })
    }

    companion object {
        suspend fun connect(port: Int, token: String): Socket {
            var connection: Socket? = null
            try {
                return withContext(Dispatchers.IO) {
                    val socket = Socket().also { connection = it }
                    processProtocolIo(socket::close) {
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
                        socket.tcpNoDelay = true
                        DataOutputStream(socket.getOutputStream()).apply { writeUTF(token); flush() }
                        socket
                    }
                }
            } catch (failure: Throwable) {
                processProtocolClose(failure) { connection?.close() }
                throw failure
            }
        }
    }
}

/** Unblock native I/O, join it, then release streams whose close may acquire the read/write lock. */
internal suspend fun <T> processProtocolIo(
    revoke: () -> Unit,
    close: () -> Unit = {},
    operation: () -> T,
): T = supervisorScope {
    val pending = async(Dispatchers.IO) { operation() }
    try { pending.await() }
    catch (failure: Throwable) {
        if (failure is CancellationException || !isActive) {
            withContext(NonCancellable) {
                processProtocolClose(failure, revoke)
                pending.join()
                processProtocolClose(failure, close)
            }
        }
        throw failure
    }
}

/**
 * Capture handles before killing the worker loses its ancestry. This is a snapshot,
 * not process-group containment: already detached children and concurrent forks are
 * not covered. Serialize revocation from independently cancelled pipe readers.
 */
private fun Process.reapProtocol(graceMillis: Long = 0): Unit = synchronized(this) {
    val root = toHandle()
    val descendants = root.descendants().use { it.toList() }
    val parents = descendants.associateWith { it.parent().orElse(null) }
    fun depth(handle: ProcessHandle): Int {
        var parent = parents[handle]
        var depth = 0
        while (parent != null && depth < descendants.size) { depth++; parent = parents[parent] }
        return depth
    }
    val started = System.nanoTime()
    val graceNanos = TimeUnit.MILLISECONDS.toNanos(graceMillis)
    fun terminate(handle: ProcessHandle) {
        if (!handle.isAlive) return
        val remaining = graceNanos - (System.nanoTime() - started)
        if (remaining > 0 && handle.supportsNormalTermination()) {
            handle.destroy()
            try { handle.onExit().get(remaining, TimeUnit.NANOSECONDS) }
            catch (_: TimeoutException) { /* Escalate only this explicit revocation. */ }
        }
        if (handle.isAlive) {
            check(handle.destroyForcibly() || !handle.isAlive) { "Could not revoke process ${handle.pid()}" }
            handle.onExit().get(5, TimeUnit.SECONDS)
        }
    }
    // Keep each parent alive while its children exit so it can reap them. Do not
    // depend on the unspecified order of ProcessHandle.descendants().
    val releases = (descendants.sortedByDescending(::depth) + root).map { handle -> { terminate(handle) } }
    processProtocolClose(*releases.toTypedArray(), {
        check(waitFor(5, TimeUnit.SECONDS)) { "Revoked worker ${pid()} did not exit" }
    })
}

private fun Process.closeProtocolPipes() = processProtocolClose(
    { reapProtocol() }, { outputStream.close() }, { inputStream.close() }, { errorStream.close() },
)

/** Attempt every release, preserving the first failure and the remaining failures as suppressed. */
internal fun processProtocolClose(vararg operations: () -> Unit) {
    var failure: Throwable? = null
    for (operation in operations) {
        try { operation() }
        catch (caught: Throwable) {
            val first = failure
            if (first == null) failure = caught else if (first !== caught) first.addSuppressed(caught)
        }
    }
    failure?.let { throw it }
}

private suspend fun processProtocolClose(failure: Throwable, operation: () -> Unit) {
    withContext(NonCancellable + processProtocolCloseDispatcher) {
        try { operation() }
        catch (caught: Throwable) { if (failure !== caught) failure.addSuppressed(caught) }
    }
}
