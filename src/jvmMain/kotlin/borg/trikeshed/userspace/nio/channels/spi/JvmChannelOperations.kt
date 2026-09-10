package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.reactor.Interest
import borg.trikeshed.userspace.reactor.toInterests
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UserspaceChannelBackend
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.j
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SelectableChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM descriptor provider for legacy [ChannelOperations]. Queued read/write
 * operations use the common uring facade with an explicitly emulated adapter.
 * Existing DNS/connect scheduling remains owned by this provider.
 */
class JvmChannelOperations(
    private val entries: Int = 2,
) : ChannelOperations {

    // fd -> Channel mapping (thread-safe)
    // Separate maps for file vs socket channels since FileChannel != SelectableChannel
    internal val fileChannels = ConcurrentHashMap<Int, FileChannel>()
    internal val socketChannels = ConcurrentHashMap<Int, SelectableChannel>()
    internal val socketInterests = ConcurrentHashMap<Int, Set<Interest>>()
    internal val fdCounter = AtomicInteger(100)
    private val connectionPhases = ConcurrentHashMap<Int, ConnectionPhase>()
    private val connectionFailures = ConcurrentHashMap<Int, String>()
    private val workerLimit = entries.coerceAtLeast(1)
    internal val ioWorkers = ThreadPoolExecutor(
        workerLimit,
        workerLimit,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(),
        { runnable -> Thread(runnable, "trikeshed-jvm-channel").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    internal fun schedule(work: Runnable): Boolean = try {
        ioWorkers.execute(work)
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle =
        JvmChannelHandle(this, entries)

    override fun socket(domain: Int, type: Int, protocol: Int): Int {
        val ch: SelectableChannel = SocketChannel.open().apply { configureBlocking(false) }
        return registerChannelInternal(ch, Interest.toMask(setOf(Interest.READ, Interest.ACCEPT, Interest.CONNECT)))
    }

    override fun bind(fd: Int, port: Int): Int {
        val oldCh = socketChannels[fd]
        if (oldCh != null) {
            try { oldCh.close() } catch (_: Exception) {}
        }
        val serverCh = ServerSocketChannel.open().apply { configureBlocking(false) }
        socketChannels[fd] = serverCh

        return try {
            serverCh.bind(java.net.InetSocketAddress(port))
            0
        } catch (e: Exception) {
            println("[JvmChannelOperations.bind] fd=$fd port=$port failed: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
    }

    override fun listen(fd: Int, backlog: Int): Int = 0 // NIO ServerSocketChannel listens implicitly on bind

    override fun accept(fd: Int): Int {
        val server = socketChannels[fd] as? ServerSocketChannel ?: return -1
        val client = server.accept() ?: return -1
        client.configureBlocking(false)
        return registerChannelInternal(client, Interest.toMask(setOf(Interest.READ)))
    }

    override fun connect(fd: Int, host: String, port: Int): Int {
        // Deny by default, permit deliberately — the policy now lives in
        // [EgressAllowlist], which DERIVES the provider hosts from
        // keymux.HarnessRegistry instead of requiring a second copy of that list
        // to be maintained by hand in bash. See that file for what this broke.
        if (!EgressAllowlist.permits(host)) {
            recordFailure(
                fd, "connect to $host:$port",
                SecurityException(EgressAllowlist.refusalMessage(host, port)),
            )
            return -1
        }
        val ch = socketChannels[fd] as? SocketChannel ?: return -1
        connectionFailures.remove(fd)
        connectionPhases[fd] = ConnectionPhase.QUEUED
        if (!schedule {
            try {
                val address = java.net.InetSocketAddress(host, port)
                if (socketChannels[fd] !== ch || !ch.isOpen) return@schedule
                ch.configureBlocking(false)
                connectionPhases[fd] = ConnectionPhase.CONNECTING
                val connected = ch.connect(address)
                if (socketChannels[fd] !== ch) return@schedule
                connectionPhases[fd] = if (connected || ch.isConnected) {
                    ConnectionPhase.CONNECTED
                } else {
                    ConnectionPhase.CONNECTING
                }
                socketInterests[fd] = setOf(Interest.READ, Interest.WRITE, Interest.CONNECT)
            } catch (e: Exception) {
                if (socketChannels[fd] === ch) {
                    recordFailure(fd, "connect to $host:$port", e)
                }
            }
        }) {
            recordFailure(
                fd,
                "schedule connect to $host:$port",
                RejectedExecutionException("JVM channel worker is closed"),
            )
            return -1
        }
        return 0
    }

    override fun close(fd: Int): Int {
        socketChannels.remove(fd)?.close()
        fileChannels.remove(fd)?.close()
        socketInterests.remove(fd)
        connectionPhases.remove(fd)
        connectionFailures.remove(fd)
        return 0
    }

    /**
     * Resolve the non-blocking connect state for an operation worker.
     *
     * `0` is EAGAIN: DNS/connect initiation is queued or finishConnect has not
     * completed yet. `-1` is a durable failure whose cause has already been
     * emitted. The caller never blocks a coroutine waiting for connect.
     */
    internal fun connectionReadiness(fd: Int, channel: SocketChannel): Int {
        if (channel.isConnected) {
            connectionPhases[fd] = ConnectionPhase.CONNECTED
            return 1
        }
        if (connectionPhases[fd] == ConnectionPhase.FAILED) return -1
        if (channel.isConnectionPending) {
            return try {
                if (channel.finishConnect()) {
                    connectionPhases[fd] = ConnectionPhase.CONNECTED
                    1
                } else {
                    0
                }
            } catch (e: Exception) {
                recordFailure(fd, "finish connect", e)
                -1
            }
        }
        return when (connectionPhases[fd]) {
            ConnectionPhase.QUEUED,
            ConnectionPhase.CONNECTING -> 0
            ConnectionPhase.CONNECTED -> {
                recordFailure(fd, "validate connection", IllegalStateException("channel is not connected"))
                -1
            }
            ConnectionPhase.FAILED -> -1
            null -> {
                recordFailure(fd, "perform channel operation", IllegalStateException("connect was not initiated"))
                -1
            }
        }
    }

    internal fun recordFailure(fd: Int, operation: String, cause: Throwable) {
        connectionPhases[fd] = ConnectionPhase.FAILED
        val detail = buildString {
            append(operation)
            append(" failed for fd=")
            append(fd)
            append(": ")
            append(cause.javaClass.simpleName)
            cause.message?.takeIf { it.isNotBlank() }?.let {
                append(": ")
                append(it)
            }
        }
        if (connectionFailures.putIfAbsent(fd, detail) == null) {
            System.err.println("[JvmChannelOperations] $detail")
        }
    }

    /**
     * Register a channel with fd and interests, return fd.
     */
    private fun registerChannelInternal(ch: SelectableChannel, initialMask: UInt): Int {
        val fd = fdCounter.incrementAndGet()
        val interests = initialMask.toInterests()
        socketChannels[fd] = ch
        socketInterests[fd] = interests
        return fd
    }

    // Public method to register a FileChannel (for file I/O)
    fun registerFile(fd: Int, fc: FileChannel) {
        fileChannels[fd] = fc
    }
    
    /** Get socket channel by fd for direct I/O (DEV stub only).
     *  Returns ANY select-able channel — SocketChannel, ServerSocketChannel,
     *  or DatagramChannel — because the bridge needs all variants, not just
     *  stream clients. */
    fun getSelectableChannel(fd: Int): java.nio.channels.SelectableChannel? =
        socketChannels[fd]
}

/** Legacy handle whose admission, completion queue and drain belong to commonMain. */
class JvmChannelHandle(
    private val ops: JvmChannelOperations,
    private val capacity: Int,
) : ChannelOperations.ChannelHandle {
    override val id: Int = ops.fdCounter.incrementAndGet()
    private val lock = Any()
    private var nextRequest = 0L
    private data class Request(val fd: Int, val userData: Long, val opcode: UringOp, val len: Int)
    private val requests = mutableMapOf<Long, Request>()
    private val facade = FunctionalUringFacade(capacity, object : UserspaceChannelBackend {
        override val capabilities: Long = UringOp.READ.mask or UringOp.WRITE.mask
        override val availability: String = "emulated: JVM registered file/socket descriptors"

        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
            submissions.map { execute(it) }

        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> =
            withContext(Dispatchers.IO + NonCancellable) {
                val completed = Array(submissions.size) {
                    val result = execute(submissions[it])
                    UringCompletion(result.userData, result.res, 0)
                }
                completed.size j { completed[it] }
            }
    })

    private fun enqueue(submission: UringSubmission, userData: Long): Int = synchronized(lock) {
        val token = nextRequest++
        facade.enqueue(submission.copy(userData = token))
        requests[token] = Request(submission.fd, userData, submission.opcode, submission.len)
        0
    }

    override fun read(buffer: ByteBuffer, offset: Long): Int = -9
    override fun write(buffer: ByteBuffer, offset: Long): Int = -9

    override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int =
        enqueue(UringSubmission(UringOp.READ, fd, 0, buffer.remaining(), -1, buffer = buffer), userData)

    override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int =
        enqueue(UringSubmission(UringOp.WRITE, fd, 0, buffer.remaining(), -1, buffer = buffer), userData)

    override fun prepAccept(serverFd: Int, userData: Long): Int =
        enqueue(UringSubmission(UringOp.ACCEPT, serverFd, 0, 0, 0), userData)

    override fun sendmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int =
        enqueue(UringSubmission(UringOp.SENDMSG, fd, msgHdrPtr, 0, 0), userData)

    override fun recvmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int =
        enqueue(UringSubmission(UringOp.RECVMSG, fd, msgHdrPtr, 0, 0), userData)

    override fun submit(): Int = synchronized(lock) { facade.submit() }

    override fun wait(minComplete: Int): List<ChannelResult> = synchronized(lock) {
        facade.wait(minComplete).map { completion ->
            val request = checkNotNull(requests.remove(completion.userData)) { "foreign channel completion" }
            // Keep the older reactor convention at its boundary only. Common CQEs
            // use successful EOF=0 and would-block=-EAGAIN without ambiguity.
            val result = when {
                completion.res == -11 -> 0
                completion.res == 0 && request.opcode == UringOp.READ && request.len > 0 -> -1
                else -> completion.res
            }
            ChannelResult(request.fd, result, request.userData)
        }
    }

    override fun close() = synchronized(lock) {
        facade.closeNow()
        requests.clear()
        // These descriptors are borrowed from ops; their caller closes them.
    }

    private fun execute(submission: UringSubmission): SelectionResult {
        fun completion(result: Int) = SelectionResult(result, submission.userData)
        if (submission.opcode != UringOp.READ && submission.opcode != UringOp.WRITE) return completion(-95)
        val buffer = submission.buffer ?: return completion(-22)
        val reading = submission.opcode == UringOp.READ
        if (submission.len < 0 || submission.len > buffer.remaining() || submission.offset < -1 ||
            (reading && buffer.isReadOnly())) return completion(-22)
        val file = ops.fileChannels[submission.fd]
        val socket = ops.socketChannels[submission.fd] as? SocketChannel
        if (file == null && socket == null) return completion(-9)
        return try {
            val nio = java.nio.ByteBuffer.wrap(buffer.array(),
                buffer.arrayOffset() + buffer.position(), submission.len).slice()
            val result = if (file != null) {
                val count = if (reading) {
                    if (submission.offset == -1L) file.read(nio) else file.read(nio, submission.offset)
                } else {
                    if (submission.offset == -1L) file.write(nio) else file.write(nio, submission.offset)
                }
                if (count < 0) 0 else count
            } else {
                val readiness = ops.connectionReadiness(submission.fd, socket!!)
                if (readiness == 0) return completion(-11)
                if (readiness < 0) return completion(-107)
                val count = if (reading) socket.read(nio) else socket.write(nio)
                when { count < 0 -> 0; count == 0 && submission.len > 0 -> -11; else -> count }
            }
            if (result > 0) buffer.position(buffer.position() + result)
            completion(result)
        } catch (failure: Exception) {
            if (socket != null) ops.recordFailure(submission.fd, if (reading) "read" else "write", failure)
            completion(when (failure) {
                is java.nio.channels.ClosedChannelException,
                is java.nio.channels.NonReadableChannelException,
                is java.nio.channels.NonWritableChannelException -> -9
                is java.nio.channels.NotYetConnectedException -> -107
                is IllegalArgumentException, is java.nio.ReadOnlyBufferException -> -22
                is SecurityException -> -13
                else -> -5
            })
        }
    }
}

private enum class ConnectionPhase {
    QUEUED,
    CONNECTING,
    CONNECTED,
    FAILED,
}
