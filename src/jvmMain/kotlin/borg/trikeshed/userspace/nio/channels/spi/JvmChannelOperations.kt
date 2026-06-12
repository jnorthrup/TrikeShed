package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.reactor.Interest
import borg.trikeshed.userspace.reactor.toInterests
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.SelectionResult
import java.nio.channels.FileChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.spi.SelectorProvider as JdkSelectorProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.suspendCancellableCoroutine
import kotlin.time.Duration

/**
 * JVM implementation of [ChannelOperations] using Java NIO.
 *
 * Maps uring-style [UringSubmission] to NIO Channel operations.
 * Uses a single-threaded Selector reactor for socket I/O and
 * direct FileChannel for file I/O.
 */
class JvmChannelOperations(
    private val entries: Int = 256,
) : ChannelOperations {

    // fd -> Channel mapping (thread-safe)
    private val fdToChannel = ConcurrentHashMap<Int, ChannelEntry>()
    private val fdCounter = AtomicInteger(100)

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
        val entry = fdToChannel[fd] ?: return -1
        (entry.channel as? ServerSocketChannel)
            ?.bind(java.net.InetSocketAddress(port)) ?.let { return 0 }
        return -1
    }

    override fun listen(fd: Int, backlog: Int): Int = 0 // NIO ServerSocketChannel listens implicitly on bind

    override fun accept(fd: Int): Int {
        val entry = fdToChannel[fd] ?: return -1
        val server = entry.channel as? ServerSocketChannel ?: return -1
        val client = server.accept() ?: return -1
        client.configureBlocking(false)
        return registerChannelInternal(client, Interest.toMask(setOf(Interest.READ)))
    }

    override fun connect(fd: Int, host: String, port: Int): Int {
        val entry = fdToChannel[fd] ?: return -1
        val ch = entry.channel as? SocketChannel ?: return -1
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
        fdToChannel.remove(fd)?.channel?.close()
        return 0
    }

    /**
     * Register a channel with fd and interests, return fd.
     */
    private fun registerChannelInternal(ch: java.nio.channels.SelectableChannel, initialMask: UInt): Int {
        val fd = fdCounter.incrementAndGet()
        // Convert UInt mask back to Set for internal storage
        val interests = initialMask.toInterests()
        fdToChannel[fd] = ChannelEntry(ch, interests)
        return fd
    }

    internal inner class ChannelEntry(
        internal val channel: java.nio.channels.SelectableChannel,
        internal var interests: Set<Interest>,
    )

    internal inner class JvmChannelHandle(
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

            // Split into file I/O (direct) vs socket I/O (via Selector)
            val fileOps = mutableListOf<PendingOp>()
            val socketOps = mutableListOf<PendingOp>()

            while (pending.isNotEmpty()) {
                val op = pending.removeFirst()
                if (ops.fdToChannel[op.fd]?.channel is FileChannel) {
                    fileOps.add(op)
                } else {
                    socketOps.add(op)
                }
            }

            // Execute file I/O directly (blocking but on dedicated thread pool in real impl)
            for (op in fileOps) {
                val entry = ops.fdToChannel[op.fd] ?: run {
                    completions.add(ChannelResult(op.fd, -1, op.user))
                    continue
                }
                val fc = entry.channel as FileChannel
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

            // Socket I/O: register interests with reactor (handled via JvmReactorOperations)
            // For now, execute directly on calling thread (simplified)
            for (op in socketOps) {
                val entry = ops.fdToChannel[op.fd] ?: run {
                    completions.add(ChannelResult(op.fd, -1, op.user))
                    continue
                }
                val sc = entry.channel as? SocketChannel ?: run {
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

        private data class PendingOp(
            val fd: Int,
            val buf: ByteBuffer,
            val read: Boolean,
            val user: Long,
            val offset: Long = 0L,
        )
    }

    private fun ByteBuffer.toNioByteBuffer(): java.nio.ByteBuffer {
        val nio = java.nio.ByteBuffer.wrap(array(), arrayOffset(), capacity())
        nio.position(position())
        nio.limit(limit())
        return nio
    }
}