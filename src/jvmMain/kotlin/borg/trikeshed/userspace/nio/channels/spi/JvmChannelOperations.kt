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
 * This stub just delegates to blocking NIO for local development.
 */
class JvmChannelOperations(
    private val entries: Int = 256,
) : ChannelOperations {

    // fd -> Channel mapping (thread-safe)
    // Separate maps for file vs socket channels since FileChannel != SelectableChannel
    internal val fileChannels = ConcurrentHashMap<Int, FileChannel>()
    internal val socketChannels = ConcurrentHashMap<Int, SelectableChannel>()
    internal val socketInterests = ConcurrentHashMap<Int, Set<Interest>>()
    internal val fdCounter = AtomicInteger(100)

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle =
        JvmChannelHandle(this, entries)

    override fun socket(domain: Int, type: Int, protocol: Int): Int {
        val ch = if (type == 1) { // SOCK_STREAM
            SocketChannel.open().apply { configureBlocking(false) }
        } else {
            ServerSocketChannel.open().apply { configureBlocking(false) }
        }
        return registerChannelInternal(ch, Interest.toMask(setOf(Interest.READ, Interest.ACCEPT, Interest.CONNECT)))
    }

    override fun bind(fd: Int, port: Int): Int {
        val ch = socketChannels[fd] as? ServerSocketChannel ?: return -1
        ch.bind(java.net.InetSocketAddress(port))?.let { return 0 }
        return -1
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
        val address = java.net.InetSocketAddress(host, port)
        try {
            ch.configureBlocking(true)
            if (!ch.connect(address)) {
                ch.finishConnect() // might throw
            }
        } catch (e: Exception) {
            return -1
        } finally {
            ch.configureBlocking(false)
        }
        return registerChannelInternal(ch, Interest.toMask(setOf(Interest.READ, Interest.WRITE, Interest.CONNECT)))
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

    private val pending = java.util.ArrayDeque<PendingOp>()
    private var lastResults: List<ChannelResult> = emptyList()

    override fun read(buffer: ByteBuffer, offset: Long): Int = -1
    override fun write(buffer: ByteBuffer, offset: Long): Int = -1

    override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int {
        pending.add(PendingOp(fd, buffer, read = true, user = userData))
        return 0
    }

    override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int {
        pending.add(PendingOp(fd, buffer, read = false, user = userData))
        return 0
    }

    override fun prepAccept(serverFd: Int, userData: Long): Int = -1
    override fun sendmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = -1
    override fun recvmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = -1

    override fun submit(): Int {
        val completions = mutableListOf<ChannelResult>()

        // Split into file I/O (direct) vs socket I/O
        val fileOps = mutableListOf<PendingOp>()
        val socketOps = mutableListOf<PendingOp>()

        while (pending.isNotEmpty()) {
            val op = pending.removeFirst()
            if (ops.fileChannels[op.fd] != null) {
                fileOps.add(op)
            } else {
                socketOps.add(op)
            }
        }

        // Execute file I/O directly (blocking - DEV ONLY)
        for (op in fileOps) {
            val fc = ops.fileChannels[op.fd] ?: run {
                completions.add(ChannelResult(op.fd, -1, op.user))
                continue
            }
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
            completions.add(ChannelResult(op.fd, res, op.user))
        }

        // Socket I/O - blocking for dev
        for (op in socketOps) {
            val sc = ops.socketChannels[op.fd] as? SocketChannel ?: run {
                completions.add(ChannelResult(op.fd, -1, op.user))
                continue
            }
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
            completions.add(ChannelResult(op.fd, res, op.user))
        }

        lastResults = completions
        return completions.size
    }

    override fun wait(minComplete: Int): List<ChannelResult> = lastResults
}