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
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal val jvmUringOperations = UringOp.caps(
    UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
    UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE,
)

/** One descriptor record shared by FileImpl and submission execution. */
internal sealed interface JvmDescriptor {
    fun close()
    fun size(): Long
    fun isOpen(): Boolean
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
                UringOp.CLOSE -> { owned.remove(sub.fd); JvmFileTable.close(sub.fd) }
                else -> {
                    val descriptor = JvmFileTable.descriptor(sub.fd) ?: return -9
                    val channel = (descriptor as? JvmChannelDescriptor)?.channel ?: return -95
                    when (sub.opcode) {
                        UringOp.READ, UringOp.WRITE -> transfer(channel, sub)
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
        // EOF is -1 here, not 0. Real io_uring reports end-of-file as a zero-byte completion, and the
        // io_uring-wiring branch coerced to that; but this codebase's convention is FileChannel's -1,
        // which JvmUserspaceChannelBackendTest asserts and callers distinguish from a legitimate
        // zero-length read. Flipping to 0 is a whole-codebase decision, not a per-callsite one.
        return count
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
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl =
        throw UnsupportedOperationException("Socket construction requires a supported uring SOCKET adapter")
}
