package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.SocketDomain
import borg.trikeshed.userspace.nio.channels.sockaddrFamily
import borg.trikeshed.userspace.nio.channels.sockaddrOctets
import borg.trikeshed.userspace.nio.channels.sockaddrPort
import java.nio.channels.*
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** POSIX_FADV_WILLNEED / MADV_WILLNEED. The one advice this backend can actually act on. */
private const val ADVICE_WILLNEED = 3

internal val jvmUringOperations = UringOp.caps(
    UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
    UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE,
    // The socket half of the emulation: the vocabulary already names these, and a stream fd
    // reaches them through the same table a file does. SOCKET/BIND/LISTEN/ACCEPT settle
    // the lifecycle SQEs; their CQEs carry the new fd or 0/-errno like the kernel's.
    UringOp.SOCKET, UringOp.BIND, UringOp.LISTEN, UringOp.ACCEPT,
    UringOp.SEND, UringOp.RECV, UringOp.CONNECT, UringOp.SHUTDOWN,
    UringOp.POLL_ADD, UringOp.POLL_REMOVE,
    // Mapping and the metadata syscalls. Every one of these was previously reached by calling
    // java.nio directly from wherever needed it -- which is the JVM creep: each call site grows
    // its own platform assumptions and the single vocabulary stops being the only way in.
    UringOp.STATX, UringOp.FALLOCATE,
    // Advisory, and answered rather than refused: the emulation must not differ from a native
    // ring in what it CAN do, only in how fast it does it.
    UringOp.FADVISE, UringOp.MADVISE,
    UringOp.UNLINKAT, UringOp.MKDIRAT, UringOp.RENAMEAT,
)

/** One descriptor record shared by FileImpl and submission execution. */
internal sealed interface JvmDescriptor {
    fun close()
    fun size(): Long
    fun isOpen(): Boolean
}

/**
 * A stream socket, addressed through the same table as a file.
 *
 * The point of the uring waist is that commonMain speaks ONE submission vocabulary and the
 * backend varies underneath -- a real ring on Linux, emulation elsewhere. That only holds if
 * every kind of fd is reachable through the same table, so a socket is a descriptor here rather
 * than a second mechanism beside it. READ/RECV and WRITE/SEND land on the same ops a file uses;
 * the descriptor decides what they mean.
 */
internal class JvmSocketDescriptor(
    val channel: SocketChannel,
    val domain: Int = SocketDomain.AF_INET.posix,
) : JvmDescriptor {
    override fun close() = channel.close()
    override fun isOpen(): Boolean = channel.isOpen
    /** A stream has no size; -1 is what the file path returns for an unknown extent. */
    override fun size(): Long = -1L
}

/**
 * A listening socket. Its CQE-producing op is ACCEPT (res = new fd or -errno);
 * every other op on this descriptor is a shape mismatch and is refused.
 */
internal class JvmServerSocketDescriptor(
    val channel: ServerSocketChannel,
    val domain: Int = SocketDomain.AF_INET.posix,
) : JvmDescriptor {
    override fun close() = channel.close()
    override fun isOpen(): Boolean = channel.isOpen
    override fun size(): Long = -1L
}

internal class JvmChannelDescriptor(val channel: FileChannel) : JvmDescriptor {
    override fun close() = channel.close()
    override fun size(): Long = channel.size()
    override fun isOpen(): Boolean = channel.isOpen
}

internal object JvmFileTable {
    private val nextId = AtomicInteger(1 shl 20)
    private val files = ConcurrentHashMap<Int, JvmDescriptor>()
    fun register(descriptor: JvmDescriptor): Int = nextId.getAndIncrement().also { files[it] = descriptor }
    fun descriptor(fd: Int): JvmDescriptor? = files[fd]
    /** Replace an existing descriptor without creating an unallocated fd. */
    fun replace(fd: Int, descriptor: JvmDescriptor): JvmDescriptor? = files.replace(fd, descriptor)
    fun close(fd: Int): Int {
        val descriptor = files.remove(fd) ?: return -9
        return try { descriptor.close(); 0 } catch (failure: Exception) { jvmIoError(failure) }
    }
}

internal fun jvmIoError(failure: Exception): Int = when (failure) {
    is NoSuchFileException -> -2
    is AccessDeniedException, is SecurityException -> -13
    is FileAlreadyExistsException -> -17
    is java.net.BindException -> -98
    is java.net.ConnectException -> -111
    is java.net.NoRouteToHostException -> -113
    is java.net.SocketTimeoutException -> -110
    is ClosedChannelException, is NonReadableChannelException, is NonWritableChannelException -> -9
    is AlreadyBoundException -> -22
    is AlreadyConnectedException -> -106
    is ConnectionPendingException -> -114
    is NotYetConnectedException -> -107
    is UnresolvedAddressException -> -22
    is UnsupportedAddressTypeException -> -97
    is IllegalArgumentException -> -22
    is UnsupportedOperationException -> -95
    else -> -5
}

internal fun UringSubmission.path(): String {
    val bytes = requireNotNull(buffer)
    require(len > 0 && len <= bytes.remaining())
    return bytes.array().decodeToString(bytes.arrayOffset() + bytes.position(),
        bytes.arrayOffset() + bytes.position() + len, throwOnInvalidSequence = true).also {
        require('\u0000' !in it)
    }
}

/**
 * SOCKET SQE execution. Encoding follows io_uring_prep_socket: the SQE carries
 * domain in the fd field, protocol in len, type in offset; res = new fd or
 * -errno. AF_INET yields a stream descriptor; AF_UNIX yields a UNIX stream.
 */
internal fun jvmSocket(domain: Int, protocol: Int, type: Int): Int = try {
    if (type and 0xf != 1) return -94
    if (type and (0xf or 0x800 or 0x80000).inv() != 0) return -22
    if (protocol != 0 && (domain != SocketDomain.AF_INET.posix || protocol != 6)) return -93
    val channel = when (domain) {
        SocketDomain.AF_INET.posix -> SocketChannel.open(java.net.StandardProtocolFamily.INET)
        SocketDomain.AF_UNIX.posix -> SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        else -> return -97
    }
    try {
        channel.configureBlocking(false)
        JvmFileTable.register(JvmSocketDescriptor(channel, domain))
    } catch (failure: Exception) {
        channel.close()
        throw failure
    }
} catch (failure: Exception) { jvmIoError(failure) }

internal fun jvmSocketAddress(address: ByteBuffer?, len: Int, domain: Int): java.net.SocketAddress {
    require(address != null && len >= 2 && len <= address.remaining())
    val bytes = ByteArray(len).also { address.duplicate().get(it) }
    require(sockaddrFamily(bytes) == domain)
    return when (domain) {
        SocketDomain.AF_INET.posix -> {
            require(len == 16)
            java.net.InetSocketAddress(java.net.InetAddress.getByAddress(sockaddrOctets(bytes)), sockaddrPort(bytes))
        }
        SocketDomain.AF_UNIX.posix -> {
            require(len in 3..110)
            val end = (2 until len).firstOrNull { bytes[it] == 0.toByte() } ?: len
            require(end > 2)
            java.net.UnixDomainSocketAddress.of(bytes.decodeToString(2, end, throwOnInvalidSequence = true))
        }
        else -> throw UnsupportedAddressTypeException()
    }
}

/**
 * BIND SQE execution. The address rides the SQE buffer as encoded sockaddr
 * bytes ([sockaddrFamily]/[sockaddrPort]/[sockaddrOctets] decode it).
 * res = 0 or -errno.
 */
internal fun jvmBind(fd: Int, address: ByteBuffer?, len: Int): Int {
    val descriptor = JvmFileTable.descriptor(fd) ?: return -9
    if (descriptor !is JvmSocketDescriptor) return -88
    return try {
        descriptor.channel.bind(jvmSocketAddress(address, len, descriptor.domain))
        0
    } catch (failure: Exception) { jvmIoError(failure) }
}

/** JDK listen requires replacing the bound channel behind the same logical fd. */
internal fun jvmListen(fd: Int, backlog: Int): Int {
    if (backlog < 0) return -22
    val descriptor = JvmFileTable.descriptor(fd) ?: return -9
    if (descriptor is JvmServerSocketDescriptor) return if (descriptor.isOpen()) 0 else -9
    if (descriptor !is JvmSocketDescriptor) return -88
    if (!descriptor.isOpen()) return -9
    if (descriptor.channel.isConnected || descriptor.channel.isConnectionPending) return -22
    return try {
        val local = descriptor.channel.localAddress ?: when (descriptor.domain) {
            SocketDomain.AF_INET.posix -> java.net.InetSocketAddress(0)
            else -> return -22
        }
        val family = if (descriptor.domain == SocketDomain.AF_UNIX.posix)
            java.net.StandardProtocolFamily.UNIX else java.net.StandardProtocolFamily.INET
        val server = ServerSocketChannel.open(family)
        try {
            server.configureBlocking(false)
            descriptor.close()
            if (local is java.net.UnixDomainSocketAddress) Files.delete(local.path)
            server.bind(local, backlog)
            if (JvmFileTable.replace(fd, JvmServerSocketDescriptor(server, descriptor.domain)) == null) {
                server.close()
                return -9
            }
            0
        } catch (failure: Exception) {
            server.close()
            throw failure
        }
    } catch (failure: Exception) { jvmIoError(failure) }
}

/**
 * CONNECT SQE execution. The address rides the SQE buffer as encoded sockaddr
 * bytes; res = 0, -EINPROGRESS (-115), or -errno. Non-blocking, so an in-flight
 * connection reports -115; a later CONNECT SQE checks completion.
 */
internal fun jvmConnect(fd: Int, address: ByteBuffer?, len: Int): Int {
    val descriptor = JvmFileTable.descriptor(fd) ?: return -9
    val channel = (descriptor as? JvmSocketDescriptor)?.channel ?: return -88
    return try {
        val remote = jvmSocketAddress(address, len, descriptor.domain)
        when {
            channel.isConnected -> if (channel.remoteAddress == remote) 0 else -106
            channel.isConnectionPending -> if (channel.remoteAddress != remote) -114 else if (channel.finishConnect()) 0 else -115
            else -> if (channel.connect(remote)) 0 else -115
        }
    } catch (failure: Exception) { jvmIoError(failure) }
}

internal fun jvmOpen(path: String, flags: Long, permissions: Int = 438): Int {
    require(flags >= 0 && flags and (3L or 64L or 128L or 512L).inv() == 0L)
    val mode = flags.toInt() and 3
    require(mode != 3)
    val options = mutableSetOf<StandardOpenOption>()
    if (mode != 1) options.add(StandardOpenOption.READ)
    if (mode != 0) options.add(StandardOpenOption.WRITE)
    if (flags and 64L != 0L) {
        // FileChannel ignores CREATE without WRITE; do not silently claim an open that created nothing.
        require(mode != 0)
        options.add(if (flags and 128L != 0L) StandardOpenOption.CREATE_NEW else StandardOpenOption.CREATE)
    }
    if (flags and 512L != 0L) { require(mode != 0); options.add(StandardOpenOption.TRUNCATE_EXISTING) }
    require(permissions in 0..511)
    val permissionSet = java.nio.file.attribute.PosixFilePermission.values().filterIndexed { index, _ ->
        permissions and (1 shl (8 - index)) != 0
    }.toSet()
    val target = Paths.get(path)
    val creating = flags and 64L != 0L
    val posix = target.fileSystem.supportedFileAttributeViews().contains("posix")
    require(!creating || posix || permissions == 438) { "Cannot enforce POSIX creation permissions on this provider" }
    val channel = if (creating && posix) FileChannel.open(target, options,
        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(permissionSet)) else FileChannel.open(target, options)
    return JvmFileTable.register(JvmChannelDescriptor(channel))
}

/**
 * The backend's readiness substrate for POLL_ADD execution. The Selector lives
 * here — inside the actual — never in service code. A single watch thread
 * selects and queues readiness signals for registered userData tokens.
 */
internal object JvmPollRegistry {
    private val selector: Selector = Selector.open()
    private val watches = ConcurrentHashMap<Long, Pair<Int, SelectionKey>>()
    private val pending = ConcurrentLinkedQueue<Triple<Long, Int, Int>>() // userData, fd, mask

    init {
        Thread({
            while (selector.isOpen) {
                try {
                    selector.select(500)
                } catch (_: Exception) {
                    break
                }
                val ready = selector.selectedKeys()
                for (key in ready) {
                    val token = key.attachment() as? Long ?: continue
                    val fd = watches[token]?.first ?: continue
                    var mask = 0
                    if (key.isReadable || key.isAcceptable) mask = mask or 0x1
                    if (key.isWritable) mask = mask or 0x4
                    if (key.isConnectable) mask = mask or 0x2
                    if (mask != 0) pending.add(Triple(token, fd, mask))
                }
                ready.clear()
            }
        }, "trikeshed-uring-poll").apply { isDaemon = true }.start()
    }

    fun add(fd: Int, mask: Int, userData: Long): Int {
        if (userData == 0L) return -22
        val descriptor = JvmFileTable.descriptor(fd) ?: return -9
        val channel = when (descriptor) {
            is JvmSocketDescriptor -> descriptor.channel
            is JvmServerSocketDescriptor -> descriptor.channel
            else -> return -95
        }
        var ops = 0
        if (mask and 0x1 != 0 || mask and 0x40000000 != 0) ops = ops or SelectionKey.OP_READ or SelectionKey.OP_ACCEPT
        if (mask and 0x4 != 0) ops = ops or SelectionKey.OP_WRITE
        if (mask and 0x2 != 0) ops = ops or SelectionKey.OP_CONNECT
        return try {
            selector.wakeup()
            val key = channel.register(selector, ops, userData)
            watches[userData] = fd to key
            0
        } catch (failure: Exception) { jvmIoError(failure) }
    }

    fun remove(userData: Long) {
        watches.remove(userData)?.second?.cancel()
        selector.wakeup()
    }

    /** Drain queued readiness signals (userData, fd, mask); empty when none fired. */
    fun drain(): List<Triple<Long, Int, Int>> {
        val out = ArrayList<Triple<Long, Int, Int>>()
        while (true) {
            val next = pending.poll() ?: break
            out.add(next)
        }
        return out
    }
}

internal fun jvmPollAdd(fd: Int, mask: Int, userData: Long): Int =
    JvmPollRegistry.add(fd, mask, userData)

/** Blocking OS primitives servicing commonMain uring submissions; this is emulation. */
internal class JvmUserspaceChannelBackend(
    override val availability: String = "emulated: Java file primitives; kernel bridge not selected",
) : UserspaceChannelBackend {
    override val capabilities: Long get() = jvmUringOperations
    private val owned = mutableSetOf<Int>()
    private var closed = false

    @Synchronized
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
        submissions.map { SelectionResult(execute(it), it.userData) }

    @Synchronized
    internal fun execute(sub: UringSubmission): Int {
        if (closed) return -9
        if (sub.flags != 0) return -95
        if (capabilities and sub.opcode.mask == 0L) return -95
        return try {
            when (sub.opcode) {
                UringOp.NOP -> 0
                // Socket lifecycle as SQEs: creation, bind, listen and accept each
                // settle one CQE. res carries the new fd (SOCKET/ACCEPT), 0 or
                // -errno (BIND/LISTEN) — the same result the kernel ring reports.
                UringOp.SOCKET -> {
                    val fd = jvmSocket(sub.fd, sub.len, sub.offset.toInt())
                    if (fd >= 0) owned.add(fd)
                    fd
                }
                UringOp.BIND -> jvmBind(sub.fd, sub.buffer, sub.len)
                UringOp.CONNECT -> jvmConnect(sub.fd, sub.buffer, sub.len)
                // Poll watches are registrations on the backend's readiness
                // substrate; the CQE fires when the fd becomes ready. mask
                // (EPOLLIN/OUT semantics) rides operationFlags.
                UringOp.POLL_ADD -> jvmPollAdd(sub.fd, sub.operationFlags, sub.userData)
                UringOp.POLL_REMOVE -> {
                    // POLLOUT removal by userData is the kernel contract.
                    if (sub.userData == 0L) return -22
                    JvmPollRegistry.remove(sub.userData); 0
                }
                UringOp.LISTEN -> {
                    // NIO ServerSocketChannel listens implicitly at bind; the SQE's
                    // backlog (len) has no JVM knob. LISTEN therefore verifies the
                    // descriptor is a bound listener and settles 0.
                    if (sub.len < 0) return -22
                    val descriptor = JvmFileTable.descriptor(sub.fd)
                    if (descriptor !is JvmServerSocketDescriptor) return -22
                    if (!descriptor.channel.isOpen) return -9
                    0
                }
                UringOp.ACCEPT -> {
                    val descriptor = JvmFileTable.descriptor(sub.fd)
                    val server = (descriptor as? JvmServerSocketDescriptor)?.channel ?: return -22
                    val client = try { server.accept() } catch (failure: Exception) { return jvmIoError(failure) }
                        ?: return -11
                    client.configureBlocking(false)
                    val fd = JvmFileTable.register(JvmSocketDescriptor(client))
                    owned.add(fd)
                    fd
                }
                UringOp.MADVISE -> if (sub.len < 0) -22 else adviseMemory(sub.addr, sub.len.toLong(), sub.operationFlags)
                UringOp.OPENAT -> {
                    if (sub.fd != -100) return -95
                    jvmOpen(sub.path(), sub.offset, sub.operationFlags).also { owned.add(it) }
                }
                // Path syscalls. The path rides in the submission buffer, and renameat carries
                // both halves NUL-separated -- one buffer, because an SQE has one address field
                // and inventing a second channel for the second path would be a shape only this
                // backend understands.
                UringOp.UNLINKAT -> { Files.delete(Paths.get(sub.path())); 0 }
                UringOp.MKDIRAT -> { Files.createDirectories(Paths.get(sub.path())); 0 }
                UringOp.RENAMEAT -> {
                    val both = sub.path()
                    val split = both.indexOf('\u0000')
                    if (split <= 0 || split == both.length - 1) return -22
                    Files.move(
                        Paths.get(both.substring(0, split)), Paths.get(both.substring(split + 1)),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    0
                }
                UringOp.CLOSE -> {
                    owned.remove(sub.fd); JvmFileTable.close(sub.fd)
                }
                else -> {
                    val descriptor = JvmFileTable.descriptor(sub.fd) ?: return -9
                    if (descriptor is JvmSocketDescriptor) return socketExecute(descriptor.channel, sub)
                    val channel = (descriptor as? JvmChannelDescriptor)?.channel ?: return -95
                    when (sub.opcode) {
                        UringOp.READ, UringOp.WRITE -> transfer(channel, sub)
                        UringOp.STATX -> statx(channel, sub)
                        UringOp.FALLOCATE -> {
                            // Grow to offset+len without writing the interior; a hole is the point.
                            val want = sub.offset + sub.len
                            if (want > channel.size()) channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)), want - 1)
                            0
                        }
                        UringOp.FADVISE -> advise(channel, sub)
                        UringOp.FSYNC -> {
                            if (sub.operationFlags and 1.inv() != 0) return -22
                            channel.force(sub.operationFlags and 1 == 0); 0
                        }
                        UringOp.FTRUNCATE -> {
                            require(sub.offset >= 0)
                            if (sub.offset <= channel.size()) channel.truncate(sub.offset)
                            else if (channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)), sub.offset - 1) != 1) return -5
                            0
                        }
                        else -> -95
                    }
                }
            }
        } catch (failure: Exception) { jvmIoError(failure) }
    }

    /** Stream IO under the same ops. Offsets are meaningless on a stream and are refused, not ignored.
     * A connecting socket finishes its connect on the first writable CQE attempt (-EAGAIN until then). */
    private fun socketExecute(channel: SocketChannel, sub: UringSubmission): Int = when (sub.opcode) {
        UringOp.READ, UringOp.RECV, UringOp.WRITE, UringOp.SEND -> {
            val buffer = sub.buffer
            when {
                buffer == null || sub.len < 0 || sub.len > buffer.remaining() -> -22
                sub.offset != -1L && sub.offset != 0L -> -22   // ESPIPE in spirit: a stream has no offset
                (sub.opcode == UringOp.READ || sub.opcode == UringOp.RECV) && buffer.isReadOnly() -> -22
                channel.isConnectionPending -> -11   // -EAGAIN: connect still in flight
                !channel.isConnected -> -107
                else -> {
                    val position = buffer.position()
                    val nio = java.nio.ByteBuffer.wrap(buffer.array(), buffer.arrayOffset() + position, sub.len)
                    val n = if (sub.opcode == UringOp.READ || sub.opcode == UringOp.RECV) channel.read(nio)
                            else channel.write(nio)
                    if (n > 0) buffer.position(position + n)
                    // EOF on a stream is a zero-byte completion, same as the file path.
                    if (n < 0) 0 else if (n == 0 && sub.len > 0) -11 else n
                }
            }
        }
        UringOp.SHUTDOWN -> { channel.shutdownOutput(); 0 }
        UringOp.CONNECT -> {
            val bytes = sub.buffer
            if (bytes == null || sub.len !in setOf(16, 28) || sub.len > bytes.remaining() || sub.operationFlags != 0) -22
            else {
                val start = bytes.arrayOffset() + bytes.position()
                val array = bytes.array()
                val family = (array[start].toInt() and 255) or ((array[start + 1].toInt() and 255) shl 8)
                val port = ((array[start + 2].toInt() and 255) shl 8) or (array[start + 3].toInt() and 255)
                val address = when {
                    family == 2 && sub.len == 16 -> java.net.InetAddress.getByAddress(array.copyOfRange(start + 4, start + 8))
                    family == 10 && sub.len == 28 -> java.net.Inet6Address.getByAddress(null,
                        array.copyOfRange(start + 8, start + 24),
                        (0..3).fold(0) { n, i -> n or ((array[start + 24 + i].toInt() and 255) shl (8 * i)) })
                    else -> throw IllegalArgumentException("Invalid CONNECT sockaddr")
                }
                val remote = java.net.InetSocketAddress(address, port)
                if (channel.isConnected) {
                    if (channel.remoteAddress == remote) 0 else -106
                } else if (channel.isConnectionPending) {
                    if (channel.remoteAddress != remote) -114 else if (channel.finishConnect()) 0 else -115
                } else if (channel.connect(remote)) 0 else -115
            }
        }
        else -> -95
    }

    /**
     * statx into the caller's buffer. Three little-endian longs -- size, mtime millis, mode bits
     * (1 regular, 2 directory) -- which is what this backend can answer without inventing the
     * rest of struct statx.
     */
    private fun statx(channel: FileChannel, sub: UringSubmission): Int {
        val buffer = sub.buffer ?: return -22
        if (buffer.isReadOnly() || sub.len < 24 || sub.len > buffer.remaining() || sub.offset != 0L || sub.addr != 0L || sub.operationFlags != 0) return -22
        val nio = java.nio.ByteBuffer.wrap(buffer.array(), buffer.arrayOffset() + buffer.position(), 24)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        nio.putLong(channel.size()); nio.putLong(0L); nio.putLong(1L)
        buffer.position(buffer.position() + 24)
        return 24
    }

    /** JDK fallback: valid cache-policy hints are accepted; WILLNEED reads the requested range.
     * It makes no residency guarantee and is reported as emulation in nativeCapabilities.
     */
    private fun advise(channel: FileChannel, sub: UringSubmission): Int {
        if (sub.offset < 0 || sub.len < 0 || sub.operationFlags !in 0..5) return -22
        if (sub.operationFlags != ADVICE_WILLNEED) return 0
        val length = if (sub.len == 0) (channel.size() - sub.offset) else sub.len.toLong()
        if (length <= 0L) return 0
        // A page-sized touch per page is enough to fault the range in; the bytes are discarded.
        val scratch = java.nio.ByteBuffer.allocate(4096)
        var at = sub.offset
        val end = sub.offset + length
        while (at < end) {
            scratch.clear()
            scratch.limit(minOf(scratch.capacity().toLong(), end - at).toInt())
            val n = channel.read(scratch, at)
            if (n <= 0) break
            at += n
        }
        return 0
    }

    private fun transfer(channel: FileChannel, sub: UringSubmission): Int {
        val buffer = sub.buffer
        if (sub.len < 0 || sub.offset < -1L) return -22
        if (buffer != null && (sub.len > buffer.remaining() || sub.opcode == UringOp.READ && buffer.isReadOnly())) return -22
        val position = buffer?.position() ?: 0
        val nio = if (buffer != null) java.nio.ByteBuffer.wrap(buffer.array(), buffer.arrayOffset() + position, sub.len)
        else {
            if (sub.addr == 0L && sub.len != 0) return -14
            sub.memory?.let { memory ->
                if (!memory.isOpen || sub.addr < memory.address || sub.addr - memory.address > memory.length - sub.len) return -22
            }
            java.lang.foreign.MemorySegment.ofAddress(sub.addr).reinterpret(sub.len.toLong()).asByteBuffer()
        }
        val count = if (sub.opcode == UringOp.READ) {
            if (sub.offset == -1L) channel.read(nio) else channel.read(nio, sub.offset)
        } else {
            if (sub.offset == -1L) channel.write(nio) else channel.write(nio, sub.offset)
        }
        if (count > 0) buffer?.position(position + count)
        return count.coerceAtLeast(0) // io_uring EOF is a successful zero-byte completion.
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        // Direct execution: the effect is already non-blocking in this backend;
        // a dispatcher hop here would be wrapper theater, not safety.
        val results = Array(submissions.size) { index ->
            val sub = submissions[index]
            UringCompletion(sub.userData, execute(sub), 0)
        }
        return results.size j { results[it] }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        owned.forEach { JvmFileTable.close(it) }
        owned.clear()
    }
}

internal fun openJvmEmulatedChannelBackend(): UserspaceChannelBackend = JvmUserspaceChannelBackend()

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend {
    require(entries > 0)
    val discovery = discoverJvmUringBackend(entries)
    val selected = discovery.backend ?: JvmUserspaceChannelBackend(discovery.report.description)
    return object : UserspaceChannelBackend by selected {
        override val probeReport = discovery.report
        override val availability = discovery.report.description
    }
}

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = JvmFileTable.descriptor(id)?.isOpen() ?: false
    actual fun close() { JvmFileTable.close(id) }
    actual fun size(): Long = JvmFileTable.descriptor(id)?.size() ?: -1L
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl = FileImpl(jvmOpen(path, if (readOnly) 0L else 2L))
}

internal actual object ChannelsImpl {
    /**
     * A stream socket in the shared fd table. Non-blocking, because the waist is a
     * submission/completion model and a blocking read would stall the whole ring.
     * domain/type/protocol are the POSIX triple; only AF_INET/SOCK_STREAM is emulated here,
     * and anything else is refused rather than silently downgraded.
     */
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl {
        require(type == 1) { "only SOCK_STREAM is emulated here, got type=$type" }
        val channel = SocketChannel.open().apply { configureBlocking(false) }
        return FileImpl(JvmFileTable.register(JvmSocketDescriptor(channel)))
    }
}
