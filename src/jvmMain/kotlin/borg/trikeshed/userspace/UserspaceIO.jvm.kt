package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import java.nio.channels.*
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** POSIX_FADV_WILLNEED / MADV_WILLNEED. The one advice this backend can actually act on. */
private const val ADVICE_WILLNEED = 3

internal val jvmUringOperations = UringOp.caps(
    UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE, UringOp.READ_FIXED, UringOp.WRITE_FIXED,
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
internal class JvmSocketDescriptor(val fd: Int) : JvmDescriptor {
    @Volatile private var open = true
    @Synchronized
    fun closeResult(): Int {
        if (!open) return -9
        open = false
        return JvmSocketSyscalls.close(fd)
    }
    override fun close() { closeResult() }
    override fun isOpen(): Boolean = open
    /** A stream has no size; -1 is what the file path returns for an unknown extent. */
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
    fun close(fd: Int): Int {
        val descriptor = files.remove(fd) ?: return -9
        if (descriptor is JvmSocketDescriptor) return descriptor.closeResult()
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
internal fun jvmSocket(domain: Int, protocol: Int, type: Int): Int {
    val fd = JvmSocketSyscalls.socket(domain, type, protocol)
    return if (fd < 0) fd else JvmFileTable.register(JvmSocketDescriptor(fd))
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

/** Blocking OS primitives servicing commonMain uring submissions; this is emulation. */
internal class JvmUserspaceChannelBackend(
    override val availability: String = "emulated: JVM file and POSIX socket effects; kernel ring not selected",
) : UserspaceChannelBackend {
    override val capabilities: Long get() = jvmUringOperations
    override val deferredCapabilities: Long get() = UringOp.caps(UringOp.POLL_ADD, UringOp.CONNECT)
    private val owned = mutableSetOf<Int>()
    private data class Watch(val descriptor: JvmDescriptor, val mask: Int, val opcode: UringOp)
    private val watches = LinkedHashMap<Long, Watch>()
    private val completions = ArrayDeque<SelectionResult>()
    private var closed = false
    private var draining = false

    @Synchronized
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
        submissions.mapNotNull { sub ->
            val result = when (sub.opcode) {
                UringOp.POLL_ADD -> pollAdd(sub)
                UringOp.CONNECT -> connect(sub)
                else -> execute(sub)
            }
            result?.let { SelectionResult(it, sub.userData) }
        }

    private fun pollAdd(sub: UringSubmission): Int? {
        if (closed) return -9
        if (draining) return -125
        if (sub.flags != 0 || sub.len != 0 || sub.operationFlags and 0x3f.inv() != 0) return -95
        val descriptor = JvmFileTable.descriptor(sub.fd) ?: return -9
        if (!descriptor.isOpen()) return -9
        if (sub.userData in watches) return -17
        watches[sub.userData] = Watch(descriptor, sub.operationFlags, UringOp.POLL_ADD)
        return null
    }

    private fun connect(sub: UringSubmission): Int? {
        if (closed) return -9
        if (draining) return -125
        if (sub.flags != 0 || sub.operationFlags != 0) return -95
        val descriptor = JvmFileTable.descriptor(sub.fd) ?: return -9
        if (descriptor !is JvmSocketDescriptor) return -88
        if (sub.userData in watches) return -17
        return synchronized(descriptor) {
            if (!descriptor.isOpen()) return -9
            val result = JvmSocketSyscalls.connectStart(descriptor.fd, sub.buffer, sub.len)
            if (result != -115) result else {
                watches[sub.userData] = Watch(descriptor, 4, UringOp.CONNECT)
                null
            }
        }
    }

    override fun reapCompletions(minComplete: Int): List<SelectionResult> {
        require(minComplete >= 0)
        val results = ArrayList<SelectionResult>()
        var pending: Boolean
        do {
            pending = synchronized(this) {
            while (completions.isNotEmpty()) results.add(completions.removeFirst())
            val snapshot = watches.toList()
            if (snapshot.isEmpty()) return results
            val immediate = snapshot.map { (_, watch) ->
                when {
                    !watch.descriptor.isOpen() -> -9
                    watch.descriptor is JvmChannelDescriptor -> watch.mask and (1 or 4)
                    else -> 0
                }
            }
            val fds = IntArray(snapshot.size) { index ->
                val descriptor = snapshot[index].second.descriptor
                when {
                    !descriptor.isOpen() -> -1
                    descriptor is JvmSocketDescriptor -> descriptor.fd
                    descriptor is JvmNativeDescriptor -> descriptor.fd
                    else -> -1
                }
            }
            val masks = IntArray(snapshot.size) { snapshot[it].second.mask }
            // Bound monitor ownership so cancelPending can quiesce a synchronous wait.
            val timeout = if (results.size < minComplete && immediate.none { it != 0 }) 10 else 0
            val (status, ready) = JvmSocketSyscalls.poll(fds, masks, timeout)
            for (index in snapshot.indices) {
                val event = if (immediate[index] != 0) immediate[index]
                    else if (status < 0) status else ready[index]
                if (event == 0 || event == -4) continue
                val (token, watch) = snapshot[index]
                val result = if (watch.opcode != UringOp.CONNECT || event < 0) event
                else synchronized(watch.descriptor) {
                    val descriptor = watch.descriptor as JvmSocketDescriptor
                    if (!descriptor.isOpen() || event and 32 != 0) -9
                    else JvmSocketSyscalls.connectFinish(descriptor.fd)
                }
                if (watch.opcode == UringOp.CONNECT && (result == -115 || result == -114)) continue
                watches.remove(token)
                results.add(SelectionResult(result, token))
            }
            watches.isNotEmpty()
            }
        } while (results.size < minComplete && pending)
        return results
    }

    @Synchronized
    override fun cancelPending() {
        draining = true
        // Cancel observation, retaining socket identity until CLOSE/backend close. The peer may
        // already have accepted the connection; no backend effect can still access the sockaddr.
        for (token in watches.keys) completions.addLast(SelectionResult(-125, token))
        watches.clear()
    }

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
                UringOp.POLL_REMOVE -> {
                    if (watches[sub.addr]?.opcode != UringOp.POLL_ADD) return -2
                    if (watches.remove(sub.addr) == null) return -2
                    completions.addLast(SelectionResult(-125, sub.addr))
                    0
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
                    if (descriptor is JvmSocketDescriptor) return synchronized(descriptor) {
                        if (!descriptor.isOpen()) -9 else socketExecute(descriptor.fd, sub)
                    }
                    if (sub.opcode in setOf(UringOp.BIND, UringOp.LISTEN, UringOp.ACCEPT, UringOp.CONNECT, UringOp.SHUTDOWN)) return -88
                    val channel = (descriptor as? JvmChannelDescriptor)?.channel ?: return -95
                    when (sub.opcode) {
                        UringOp.READ, UringOp.WRITE -> transfer(channel, sub)
                        UringOp.READ_FIXED, UringOp.WRITE_FIXED -> transfer(channel,
                            sub.copy(opcode = if (sub.opcode == UringOp.READ_FIXED) UringOp.READ else UringOp.WRITE))
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

    /** Socket effects retain the same OS descriptor from SOCKET through CLOSE. */
    private fun socketExecute(fd: Int, sub: UringSubmission): Int = when (sub.opcode) {
        UringOp.READ, UringOp.RECV, UringOp.WRITE, UringOp.SEND -> {
            if (sub.offset != -1L && sub.offset != 0L) -29
            else JvmSocketSyscalls.transfer(fd, sub.buffer, sub.len,
                sub.opcode == UringOp.READ || sub.opcode == UringOp.RECV, sub.operationFlags)
        }
        UringOp.BIND -> JvmSocketSyscalls.bind(fd, sub.buffer, sub.len)
        UringOp.LISTEN -> JvmSocketSyscalls.listen(fd, sub.len)
        UringOp.SHUTDOWN -> JvmSocketSyscalls.shutdown(fd, sub.len)
        UringOp.ACCEPT -> {
            val accepted = JvmSocketSyscalls.accept(fd)
            if (accepted < 0) accepted
            else JvmFileTable.register(JvmSocketDescriptor(accepted)).also { owned.add(it) }
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
        val outstanding = HashSet<Long>(submissions.size)
        for (index in 0 until submissions.size) outstanding.add(submissions[index].userData)
        val results = submitBatch(List(submissions.size) { submissions[it] }).toMutableList()
        results.forEach { outstanding.remove(it.userData) }
        while (outstanding.isNotEmpty()) {
            val settled = reapCompletions(0)
            settled.forEach { outstanding.remove(it.userData) }
            results.addAll(settled)
            if (outstanding.isNotEmpty()) kotlinx.coroutines.delay(1)
        }
        return results.size j { UringCompletion(results[it].userData, results[it].res, 0) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        cancelPending()
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
        val fd = jvmSocket(domain, protocol, type)
        check(fd >= 0) { "SOCKET failed: $fd" }
        return FileImpl(fd)
    }
}
