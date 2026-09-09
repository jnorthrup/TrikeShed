package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** POSIX_FADV_WILLNEED / MADV_WILLNEED. The one advice this backend can actually act on. */
private const val ADVICE_WILLNEED = 3

internal val jvmUringOperations = UringOp.caps(
    UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
    UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE,
    // The socket half of the emulation: the vocabulary already names these, and a stream fd
    // reaches them through the same table a file does.
    UringOp.SEND, UringOp.RECV, UringOp.CONNECT, UringOp.SHUTDOWN,
    // Mapping and the metadata syscalls. Every one of these was previously reached by calling
    // java.nio directly from wherever needed it -- which is the JVM creep: each call site grows
    // its own platform assumptions and the single vocabulary stops being the only way in.
    UringOp.MAP, UringOp.MUNMAP, UringOp.MSYNC, UringOp.STATX, UringOp.FALLOCATE,
    // Advisory, and answered rather than refused: the emulation must not differ from a native
    // ring in what it CAN do, only in how fast it does it.
    UringOp.FADVISE, UringOp.MADVISE,
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
internal class JvmSocketDescriptor(val channel: SocketChannel) : JvmDescriptor {
    override fun close() = channel.close()
    override fun isOpen(): Boolean = channel.isOpen
    /** A stream has no size; -1 is what the file path returns for an unknown extent. */
    override fun size(): Long = -1L
}

internal class JvmChannelDescriptor(val channel: FileChannel) : JvmDescriptor {
    /**
     * The mapping this fd holds, if MAP was submitted for it. One per descriptor: a second MAP
     * without an intervening MUNMAP is -22 rather than a silent leak of the first.
     *
     * Kept as a field on the descriptor rather than in a side map keyed by fd, so a mapping cannot
     * outlive the descriptor that backs it -- close() releases it by construction.
     */
    @Volatile internal var mapping: java.nio.MappedByteBuffer? = null
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
        return try { descriptor.close(); 0 } catch (failure: Exception) { jvmIoError(failure) }
    }
}

internal fun jvmIoError(failure: Exception): Int = when (failure) {
    is NoSuchFileException -> -2
    is AccessDeniedException, is SecurityException -> -13
    is FileAlreadyExistsException -> -17
    is ClosedChannelException, is NonReadableChannelException, is NonWritableChannelException -> -9
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

internal fun jvmOpen(path: String, flags: Long): Int {
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
    val channel = FileChannel.open(Paths.get(path), options)
    return JvmFileTable.register(JvmChannelDescriptor(channel))
}

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
                UringOp.OPENAT -> {
                    if (sub.fd != -100) return -95
                    jvmOpen(sub.path(), sub.offset).also { owned.add(it) }
                }
                UringOp.CLOSE -> {
                    (JvmFileTable.descriptor(sub.fd) as? JvmChannelDescriptor)?.mapping = null
                    owned.remove(sub.fd); JvmFileTable.close(sub.fd)
                }
                else -> {
                    val descriptor = JvmFileTable.descriptor(sub.fd) ?: return -9
                    if (descriptor is JvmSocketDescriptor) return socketExecute(descriptor.channel, sub)
                    val channel = (descriptor as? JvmChannelDescriptor)?.channel ?: return -95
                    when (sub.opcode) {
                        UringOp.READ, UringOp.WRITE -> transfer(channel, sub)
                        UringOp.MAP -> mapDescriptor(descriptor, sub)
                        UringOp.MUNMAP -> { descriptor.mapping = null; 0 }
                        UringOp.MSYNC -> descriptor.mapping?.let { it.force(); 0 } ?: -22
                        UringOp.STATX -> statx(channel, sub)
                        UringOp.FALLOCATE -> {
                            // Grow to offset+len without writing the interior; a hole is the point.
                            val want = sub.offset + sub.len
                            if (want > channel.size()) channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)), want - 1)
                            0
                        }
                        UringOp.FADVISE -> advise(channel, sub)
                        UringOp.MADVISE -> when (sub.flags) {
                            // madvise(WILLNEED) on a mapping is exactly MappedByteBuffer.load():
                            // fault the pages in now rather than on first touch.
                            ADVICE_WILLNEED -> descriptor.mapping?.let { it.load(); 0 } ?: -22
                            else -> 0
                        }
                        UringOp.FSYNC -> { channel.force(true); 0 }
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

    /** Stream IO under the same ops. Offsets are meaningless on a stream and are refused, not ignored. */
    private fun socketExecute(channel: SocketChannel, sub: UringSubmission): Int = when (sub.opcode) {
        UringOp.READ, UringOp.RECV, UringOp.WRITE, UringOp.SEND -> {
            val buffer = sub.buffer
            when {
                buffer == null || sub.len < 0 || sub.len > buffer.remaining() -> -22
                sub.offset != -1L && sub.offset != 0L -> -22   // ESPIPE in spirit: a stream has no offset
                (sub.opcode == UringOp.READ || sub.opcode == UringOp.RECV) && buffer.isReadOnly() -> -22
                else -> {
                    val position = buffer.position()
                    val nio = java.nio.ByteBuffer.wrap(buffer.array(), buffer.arrayOffset() + position, sub.len)
                    val n = if (sub.opcode == UringOp.READ || sub.opcode == UringOp.RECV) channel.read(nio)
                            else channel.write(nio)
                    if (n > 0) buffer.position(position + n)
                    // EOF on a stream is a zero-byte completion, same as the file path.
                    if (n < 0) 0 else n
                }
            }
        }
        UringOp.SHUTDOWN -> { channel.shutdownOutput(); 0 }
        UringOp.CONNECT -> if (channel.finishConnect()) 0 else -115   // EINPROGRESS
        else -> -95
    }

    /**
     * mmap through the waist. [UringSubmission.flags] is the protection: 0 read-only private,
     * 1 read/write shared. A JVM mapping cannot be relocated, so the address the caller would
     * get from a real mmap has no analogue -- the completion returns the mapped length, and the
     * bytes are reached through the descriptor rather than through an address.
     */
    private fun mapDescriptor(descriptor: JvmChannelDescriptor, sub: UringSubmission): Int {
        if (descriptor.mapping != null) return -22          // already mapped; MUNMAP first
        if (sub.len <= 0 || sub.offset < 0) return -22
        val mode = when (sub.flags) {
            0 -> FileChannel.MapMode.READ_ONLY
            1 -> FileChannel.MapMode.READ_WRITE
            else -> return -22
        }
        if (sub.offset + sub.len > descriptor.channel.size()) return -22
        descriptor.mapping = descriptor.channel.map(mode, sub.offset, sub.len.toLong())
        return sub.len
    }

    /**
     * statx into the caller's buffer. Three little-endian longs -- size, mtime millis, mode bits
     * (1 regular, 2 directory) -- which is what this backend can answer without inventing the
     * rest of struct statx.
     */
    private fun statx(channel: FileChannel, sub: UringSubmission): Int {
        val buffer = sub.buffer ?: return -22
        if (buffer.remaining() < 24) return -22
        val nio = java.nio.ByteBuffer.wrap(buffer.array(), buffer.arrayOffset() + buffer.position(), 24)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        nio.putLong(channel.size()); nio.putLong(0L); nio.putLong(1L)
        return 24
    }

    /**
     * posix_fadvise. [UringSubmission.flags] is the advice, [offset] and [len] the range.
     *
     * These previously returned -95, on the reasoning that returning 0 would claim an effect that
     * did not happen. That was the wrong reading of the contract: advice is advisory, and
     * posix_fadvise returning 0 has never promised the kernel acted -- only that the advice was
     * well-formed and received. Refusing it made the emulation diverge from a native ring in
     * capability rather than in performance, which is the one way this waist must not differ:
     * a caller would have to branch on which backend it got, and that branch is the fraying.
     *
     * WILLNEED is honoured for real by reading the range so the page cache holds it. The rest are
     * accepted and ignored, which is precisely what a kernel is permitted to do with them.
     */
    private fun advise(channel: FileChannel, sub: UringSubmission): Int {
        if (sub.offset < 0 || sub.len < 0) return -22
        if (sub.flags != ADVICE_WILLNEED) return 0
        val length = if (sub.len == 0) (channel.size() - sub.offset) else sub.len.toLong()
        if (length <= 0L) return 0
        // A page-sized touch per page is enough to fault the range in; the bytes are discarded.
        val scratch = java.nio.ByteBuffer.allocate(4096)
        var at = sub.offset
        val end = sub.offset + length
        while (at < end) {
            scratch.clear()
            val n = channel.read(scratch, at)
            if (n <= 0) break
            at += n
        }
        return 0
    }

    private fun transfer(channel: FileChannel, sub: UringSubmission): Int {
        val buffer = sub.buffer ?: return -22
        if (sub.len < 0 || sub.len > buffer.remaining() || sub.offset < -1L) return -22
        if (sub.opcode == UringOp.READ && buffer.isReadOnly()) return -22
        val position = buffer.position()
        val nio = java.nio.ByteBuffer.wrap(buffer.array(), buffer.arrayOffset() + position, sub.len)
        val count = if (sub.opcode == UringOp.READ) {
            if (sub.offset == -1L) channel.read(nio) else channel.read(nio, sub.offset)
        } else {
            if (sub.offset == -1L) channel.write(nio) else channel.write(nio, sub.offset)
        }
        if (count > 0) buffer.position(position + count)
        return count.coerceAtLeast(0) // io_uring EOF is a successful zero-byte completion.
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> =
        withContext(Dispatchers.IO) {
            val results = Array(submissions.size) { index ->
                val sub = submissions[index]
                UringCompletion(sub.userData, execute(sub), 0)
            }
            results.size j { results[it] }
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
