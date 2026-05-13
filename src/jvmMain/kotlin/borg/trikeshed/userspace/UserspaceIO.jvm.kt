package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.FileChannel

// ---- fd registry ----

private var nextFd = 1
private val fileSockets = mutableMapOf<Int, Socket>()          // TCP fds
private val fileDatagrams = mutableMapOf<Int, DatagramSocket>() // UDP fds
private val fileChannels = mutableMapOf<Int, FileChannel>()    // file fds

private fun allocFd(): Int = nextFd++

// ---- UserspaceChannelBackend ----

private class JvmUserspaceChannelBackend : UserspaceChannelBackend {

    // Legacy typed shims — delegate to submitBatch where possible

    @Deprecated("Use submitBatch")
    override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val ch = fileChannels[file.id] ?: return -1
        return try {
            ch.read(buffer.toNioByteBuffer(), offset)
                .also { if (it > 0) buffer.position(buffer.position() + it) }
        } catch (_: Exception) { -1 }
    }

    @Deprecated("Use submitBatch")
    override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val ch = fileChannels[file.id] ?: return -1
        return try {
            ch.write(buffer.toNioByteBuffer(), offset)
                .also { if (it > 0) buffer.position(buffer.position() + it) }
        } catch (_: Exception) { -1 }
    }

    @Deprecated("Use submitBatch")
    override fun accept(file: FileImpl): Int = -1 // use submitBatch ACCEPT

    @Deprecated("Use submitBatch")
    override fun connect(file: FileImpl, address: CharSequence, port: Int): Int = -1 // use submitBatch CONNECT

    @Deprecated("Use submitBatch")
    override fun close(file: FileImpl): Int {
        fileDatagrams.remove(file.id)?.runCatching { close() }
        fileSockets.remove(file.id)?.runCatching { close() }
        fileChannels.remove(file.id)?.runCatching { close() }
        return 0
    }

    @Deprecated("Use submitBatch")
    override fun sync(file: FileImpl, metaData: Boolean): Int {
        val ch = fileChannels[file.id] ?: return -1
        return try { ch.force(metaData); 0 } catch (_: Exception) { -1 }
    }

    @Deprecated("Use submitBatch")
    override fun truncate(file: FileImpl, size: Long): Int {
        val ch = fileChannels[file.id] ?: return -1
        return try { ch.truncate(size); 0 } catch (_: Exception) { -1 }
    }

    @Deprecated("Use submitBatch")
    override fun map(file: FileImpl, mode: CharSequence, position: Long, size: Long): Int {
        val ch = fileChannels[file.id] ?: return -1
        return try {
            val mapMode = when (mode.toString()) {
                "r" -> FileChannel.MapMode.READ_ONLY
                "rw" -> FileChannel.MapMode.READ_WRITE
                "p" -> FileChannel.MapMode.PRIVATE
                else -> return -1
            }
            ch.map(mapMode, position, size)
            0
        } catch (_: Exception) { -1 }
    }

    // ---- poll readiness ----

    override fun hasPending(fd: Int): Boolean =
        fileDatagrams[fd]?.let { false }  // UDP: no non-blocking poll on JVM DatagramSocket
        ?: fileSockets[fd]?.runCatching { getInputStream().available() > 0 }?.getOrDefault(false)
        ?: false

    // ---- unified batch path — uring emulation on JVM ----

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        if (submissions.isEmpty()) return emptyList()
        return submissions.map { sub -> SelectionResult(dispatchOp(sub), sub.userData) }
    }

    private fun dispatchOp(sub: UringSubmission): Int = when (sub.opcode) {

        UringOp.READ, UringOp.READV -> {
            val ch = fileChannels[sub.fd]
            val sock = fileSockets[sub.fd]
            val buf = sub.buffer ?: return@dispatchOp -1
            when {
                ch != null -> try {
                    ch.read(buf.toNioByteBuffer(), sub.offset)
                        .also { if (it > 0) buf.position(buf.position() + it) }
                } catch (_: Exception) { -1 }
                sock != null -> try {
                    val arr = buf.array()
                    val n = sock.getInputStream().read(arr, buf.position(), buf.remaining())
                    if (n > 0) buf.position(buf.position() + n)
                    n
                } catch (_: Exception) { -1 }
                else -> -1
            }
        }

        UringOp.WRITE, UringOp.WRITEV -> {
            val ch = fileChannels[sub.fd]
            val sock = fileSockets[sub.fd]
            val buf = sub.buffer ?: return@dispatchOp -1
            when {
                ch != null -> try {
                    ch.write(buf.toNioByteBuffer(), sub.offset)
                        .also { if (it > 0) buf.position(buf.position() + it) }
                } catch (_: Exception) { -1 }
                sock != null -> try {
                    val arr = buf.array()
                    val n = buf.remaining()
                    sock.getOutputStream().write(arr, buf.position(), n)
                    buf.position(buf.position() + n)
                    n
                } catch (_: Exception) { -1 }
                else -> -1
            }
        }

        UringOp.CONNECT -> {
            val buf = sub.buffer ?: return@dispatchOp -1
            val host = String(buf.array(), buf.position(), buf.remaining(), Charsets.UTF_8)
            val port = sub.offset.toInt()
            val sock = fileSockets[sub.fd]
            val dg = fileDatagrams[sub.fd]
            when {
                sock != null -> try { sock.connect(InetSocketAddress(host, port)); 0 } catch (_: Exception) { -1 }
                dg != null -> try { dg.connect(InetSocketAddress(host, port)); 0 } catch (_: Exception) { -1 }
                else -> -1
            }
        }

        UringOp.SEND -> {
            val buf = sub.buffer ?: return@dispatchOp -1
            val sock = fileSockets[sub.fd]
            val dg = fileDatagrams[sub.fd]
            when {
                sock != null -> try {
                    val n = buf.remaining()
                    sock.getOutputStream().write(buf.array(), buf.position(), n)
                    buf.position(buf.position() + n)
                    n
                } catch (_: Exception) { -1 }
                dg != null -> try {
                    val data = buf.array().copyOfRange(buf.position(), buf.position() + buf.remaining())
                    dg.send(DatagramPacket(data, data.size))
                    data.size
                } catch (_: Exception) { -1 }
                else -> -1
            }
        }

        UringOp.RECV -> {
            val buf = sub.buffer ?: return@dispatchOp -1
            val sock = fileSockets[sub.fd]
            val dg = fileDatagrams[sub.fd]
            when {
                sock != null -> try {
                    val n = sock.getInputStream().read(buf.array(), buf.position(), buf.remaining())
                    if (n > 0) buf.position(buf.position() + n)
                    n
                } catch (_: Exception) { -1 }
                dg != null -> try {
                    val pkt = DatagramPacket(buf.array(), buf.position(), buf.remaining())
                    dg.soTimeout = 10_000
                    dg.receive(pkt)
                    if (pkt.length > 0) buf.position(buf.position() + pkt.length)
                    pkt.length
                } catch (_: java.net.SocketTimeoutException) { 0 }
                catch (_: Exception) { -1 }
                else -> -1
            }
        }

        UringOp.ACCEPT -> {
            // server-side accept: not wired yet — return -1
            -1
        }

        UringOp.FSYNC -> {
            val ch = fileChannels[sub.fd] ?: return@dispatchOp -1
            try { ch.force(false); 0 } catch (_: Exception) { -1 }
        }

        UringOp.FTRUNCATE -> {
            val ch = fileChannels[sub.fd] ?: return@dispatchOp -1
            try { ch.truncate(sub.offset); 0 } catch (_: Exception) { -1 }
        }

        UringOp.SENDMSG -> {
            val dg = fileDatagrams[sub.fd] ?: return@dispatchOp -1
            val buf = sub.buffer ?: return@dispatchOp -1
            val host = String(buf.array(), buf.position(), buf.remaining(), Charsets.UTF_8)
            val port = sub.offset.toInt()
            try {
                val data = buf.array().let { arr ->
                    // If addr field holds the actual data, use that instead
                    if (sub.addr > 0 && sub.len > 0) {
                        // data is at native addr — can't access on JVM, use buffer
                        arr.copyOfRange(buf.position(), buf.position() + buf.remaining())
                    } else arr.copyOfRange(buf.position(), buf.position() + buf.remaining())
                }
                dg.send(DatagramPacket(data, data.size, InetSocketAddress(host, port)))
                data.size
            } catch (_: Exception) { -1 }
        }

        UringOp.RECVMSG -> {
            val dg = fileDatagrams[sub.fd] ?: return@dispatchOp -1
            val buf = sub.buffer ?: return@dispatchOp -1
            try {
                val pkt = DatagramPacket(buf.array(), buf.position(), buf.remaining())
                dg.soTimeout = (sub.offset.coerceIn(0, 30_000)).toInt()
                dg.receive(pkt)
                if (pkt.length > 0) buf.position(buf.position() + pkt.length)
                pkt.length
            } catch (_: java.net.SocketTimeoutException) { 0 }
            catch (_: Exception) { -1 }
        }

        UringOp.CLOSE -> {
            fileDatagrams.remove(sub.fd)?.runCatching { close() }
            fileSockets.remove(sub.fd)?.runCatching { close() }
            fileChannels.remove(sub.fd)?.runCatching { close() }
            0
        }

        else -> -1
    }
}

internal actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend =
    JvmUserspaceChannelBackend()

// ---- FileImpl ----

actual class FileImpl actual constructor(actual val id: Int) {
    @PublishedApi internal var path: CharSequence = ""
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {
        fileDatagrams.remove(id)?.runCatching { close() }
        fileSockets.remove(id)?.runCatching { close() }
        fileChannels.remove(id)?.runCatching { close() }
    }
    actual fun size(): Long =
        fileChannels[id]?.size()
            ?: path.toString().let { java.io.File(it).let { f -> if (f.exists()) f.length() else -1L } }
}

// ---- FilesImpl ----

internal actual object FilesImpl {
    actual fun open(path: CharSequence, readOnly: Boolean): FileImpl {
        val fd = allocFd()
        fileChannels[fd] = FileChannel.open(
            java.nio.file.Paths.get(path.toString()),
            if (readOnly)
                java.util.EnumSet.of(java.nio.file.StandardOpenOption.READ)
            else
                java.util.EnumSet.of(
                    java.nio.file.StandardOpenOption.READ,
                    java.nio.file.StandardOpenOption.WRITE
                )
        )
        return FileImpl(fd).also { it.path = path }
    }
}

// ---- ChannelsImpl — real JVM socket, keyed by synthetic fd ----

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl {
        val fd = allocFd()
        // SOCK_DGRAM (type=2) → UDP DatagramSocket, else TCP Socket
        if (type == 2) {
            fileDatagrams[fd] = DatagramSocket()
        } else {
            fileSockets[fd] = Socket()   // unconnected; CONNECT op wires it up
        }
        return FileImpl(fd)
    }
}

// ---- helpers ----

private fun ByteBuffer.toNioByteBuffer(): java.nio.ByteBuffer {
    val nio = java.nio.ByteBuffer.wrap(array(), arrayOffset(), capacity())
    nio.position(position())
    nio.limit(limit())
    return nio
}
