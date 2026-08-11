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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
    private val workerLimit = entries.coerceAtLeast(1)
    // Unbounded queue: with parallel HTX fan-out, a bounded ArrayBlockingQueue
    // plus AbortPolicy rejects submissions mid-exchange; JvmChannelHandle then
    // fails the pending ops with -1 ("HTX reactor write failed"). Rejection is
    // never the right answer for the reactor — queue growth is the backpressure.
    internal val ioWorkers = ThreadPoolExecutor(
        workerLimit,
        workerLimit,
        0L,
        TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(),
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
        val ch = socketChannels[fd] as? SocketChannel ?: return -1
        // The caller (HtxReactorElement.openConnection) treats a >= 0 return as
        // "connected and ready to write". Scheduling finishConnect() on a worker
        // races the first TLS ClientHello write, which then throws
        // NotYetConnectedException and kills every exchange (observed Aug 09:
        // 565 DispatchFailed / 0 Dispatched). Run connect+finishConnect inline —
        // the worker version was already effectively blocking, just unordered.
        return try {
            val address = java.net.InetSocketAddress(host, port)
            if (!ch.connect(address)) {
                while (ch.isOpen && !ch.finishConnect()) {
                    // non-blocking connect completes on first or second finishConnect
                }
            }
            if (!ch.isOpen) return -1
            socketInterests[fd] = setOf(Interest.READ, Interest.WRITE, Interest.CONNECT)
            0
        } catch (e: Exception) {
            println("JvmChannelOperations connect exception: ${e.message}")
            -1
        }
    }

    override fun close(fd: Int): Int {
        socketChannels.remove(fd)?.close()
        fileChannels.remove(fd)?.close()
        socketInterests.remove(fd)
        return 0
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
