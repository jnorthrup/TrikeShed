package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.reactor.Interest
import borg.trikeshed.userspace.reactor.toInterests
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.SelectionResult
import java.nio.channels.FileChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SelectableChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extension to convert custom ByteBuffer to NIO ByteBuffer
 */
private fun ByteBuffer.toNioByteBuffer(): java.nio.ByteBuffer {
    val nio = java.nio.ByteBuffer.wrap(array(), arrayOffset(), capacity())
    nio.position(position())
    nio.limit(limit())
    return nio
}

/**
 * JVM stub implementation of [ChannelOperations].
 * 
 * This is a DEV/CI stub only. Production uses Linux io_uring (kernel-level).
 * This stub retains blocking NIO for local development, but executes queued
 * operations on daemon workers. `submit()` must return promptly so the common
 * reactor's cancellable completion/timeout loop keeps control of the caller.
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
        val allowlist = setOf("127.0.0.1", "localhost", "github.com") +
            (System.getenv("TRIKESHED_EGRESS_ALLOWLIST")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())
        if (host !in allowlist) {
            recordFailure(fd, "connect to $host:$port", SecurityException("egress channel closed by substrate: host not in allowlist"))
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

// Moved out of inner class to avoid 'Class is prohibited here' error
private data class PendingOp(
    val fd: Int,
    val buf: ByteBuffer,
    val read: Boolean,
    val user: Long,
    val offset: Long = 0L,
)

/** Standalone ChannelHandle implementation */
class JvmChannelHandle(
    private val ops: JvmChannelOperations,
    private val capacity: Int,
) : ChannelOperations.ChannelHandle {

    override val id: Int get() = ops.fdCounter.incrementAndGet()

    private val pendingLock = Any()
    private val pending = java.util.ArrayDeque<PendingOp>()
    private val submitted = java.util.ArrayDeque<PendingOp>()
    private var workerScheduled = false
    private val completed = ConcurrentLinkedQueue<ChannelResult>()

    override fun read(buffer: ByteBuffer, offset: Long): Int = -1
    override fun write(buffer: ByteBuffer, offset: Long): Int = -1

    override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int {
        synchronized(pendingLock) {
            pending.add(PendingOp(fd, buffer, read = true, user = userData))
        }
        return 0
    }

    override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int {
        synchronized(pendingLock) {
            pending.add(PendingOp(fd, buffer, read = false, user = userData))
        }
        return 0
    }

    override fun prepAccept(serverFd: Int, userData: Long): Int = -1
    override fun sendmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = -1
    override fun recvmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = -1

    override fun submit(): Int {
        var scheduleWorker = false
        val submittedCount = synchronized(pendingLock) {
            var count = 0
            while (pending.isNotEmpty()) {
                submitted.add(pending.removeFirst())
                count++
            }
            if (submitted.isNotEmpty() && !workerScheduled) {
                workerScheduled = true
                scheduleWorker = true
            }
            count
        }
        if (scheduleWorker && !ops.schedule(::drainSubmitted)) {
            rejectSubmitted()
        }
        return submittedCount
    }

    private fun drainSubmitted() {
        try {
            while (true) {
                val op = synchronized(pendingLock) {
                    if (submitted.isEmpty()) null else submitted.removeFirst()
                } ?: break
                completed.add(execute(op))
            }
        } finally {
            var reschedule = false
            synchronized(pendingLock) {
                workerScheduled = false
                if (submitted.isNotEmpty()) {
                    workerScheduled = true
                    reschedule = true
                }
            }
            if (reschedule && !ops.schedule(::drainSubmitted)) {
                rejectSubmitted()
            }
        }
    }

    private fun rejectSubmitted() {
        val rejected = synchronized(pendingLock) {
            workerScheduled = false
            buildList {
                while (submitted.isNotEmpty()) {
                    add(submitted.removeFirst())
                }
            }
        }
        rejected.forEach { op ->
            completed.add(ChannelResult(op.fd, -1, op.user))
        }
    }

    private fun execute(op: PendingOp): ChannelResult {
        val fc = ops.fileChannels[op.fd]
        if (fc != null) {
            val nioBuf = op.buf.toNioByteBuffer()
            val res = try {
                if (op.read) {
                    val n = fc.read(nioBuf, op.offset)
                    if (n > 0) op.buf.position(op.buf.position() + n)
                    n
                } else {
                    val n = fc.write(nioBuf, op.offset)
                    if (n > 0) op.buf.position(op.buf.position() + n)
                    n
                }
            } catch (e: Exception) {
                -1
            }
            return ChannelResult(op.fd, res, op.user)
        }

        val sc = ops.socketChannels[op.fd] as? SocketChannel
            ?: return ChannelResult(op.fd, -1, op.user)
        val nioBuf = op.buf.toNioByteBuffer()
        val res = try {
            val readiness = ops.connectionReadiness(op.fd, sc)
            if (readiness <= 0) {
                return ChannelResult(op.fd, readiness, op.user)
            }
            if (op.read) {
                val n = sc.read(nioBuf)
                if (n > 0) op.buf.position(op.buf.position() + n)
                n
            } else {
                val n = sc.write(nioBuf)
                if (n > 0) op.buf.position(op.buf.position() + n)
                n
            }
        } catch (e: Exception) {
            ops.recordFailure(op.fd, if (op.read) "read" else "write", e)
            -1
        }
        return ChannelResult(op.fd, res, op.user)
    }

    override fun wait(minComplete: Int): List<ChannelResult> = buildList {
        while (true) {
            add(completed.poll() ?: break)
        }
    }
}

private enum class ConnectionPhase {
    QUEUED,
    CONNECTING,
    CONNECTED,
    FAILED,
}
