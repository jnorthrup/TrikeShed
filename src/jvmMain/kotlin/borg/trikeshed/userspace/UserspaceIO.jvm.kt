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
<<<<<<< HEAD
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private object JvmFileTable {
    private val files = ConcurrentHashMap<Int, FileChannel>()
    private val nextId = AtomicInteger(1)

    fun register(channel: FileChannel): Int {
        val fd = nextId.getAndIncrement()
        check(fd > 0) { "Userspace descriptor identities exhausted" }
        files[fd] = channel
        return fd
    }
=======

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
>>>>>>> codex/wire-nodejs-and-jvm-iouring

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

<<<<<<< HEAD
/**
 * JVM backend for [FunctionalUringFacade] using Java NIO.
 *
 * Maps [UringSubmission] -> NIO operations.
 * File I/O: direct FileChannel (blocking, but in real impl offloaded to thread pool).
 * Socket I/O: registered with [JvmReactorOperations] for async select.
 */
private class JvmUserspaceChannelBackend : UserspaceChannelBackend {
    private val socketSelector = lazy { Selector.open() }
    private val reactor by lazy { JvmReactorOperations(socketSelector.value) }
    override val capabilities: Long = UringOp.caps(UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
        UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE)
    private val ownedFiles = ConcurrentHashMap<Int, FileChannel>()
    @Volatile private var closed = false
=======
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
>>>>>>> codex/wire-nodejs-and-jvm-iouring

    @Synchronized
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
        submissions.map { SelectionResult(execute(it), it.userData) }

<<<<<<< HEAD
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        check(!closed) { "Userspace backend is closed" }
        if (submissions.isEmpty()) return emptyList()

        val results = mutableListOf<SelectionResult>()

        for (sub in submissions) {
            if (sub.opcode == UringOp.OPENAT) {
                results.add(SelectionResult(open(sub), sub.userData))
                continue
            }
            var wrapper = channels[sub.fd]
            if (wrapper == null) {
                // Auto-register if not present (for files opened via FilesImpl)
                if (sub.opcode in setOf(UringOp.READ, UringOp.WRITE, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE)) {
                    val fc = JvmFileTable.channel(sub.fd)
                    wrapper = if (fc == null) null else registerChannel(fc, sub.fd)
                } else {
                    results.add(SelectionResult(-95, sub.userData))
                    continue
                }
            }

            val res = if (wrapper != null) {
                when (sub.opcode) {
                    UringOp.READ, UringOp.READV -> wrapper.executeRead(sub)
                    UringOp.WRITE, UringOp.WRITEV -> wrapper.executeWrite(sub)
                    UringOp.FSYNC -> wrapper.executeSync()
                    UringOp.FTRUNCATE -> wrapper.executeTruncate(sub.offset)
                    UringOp.CLOSE -> {
                        val closed = wrapper.executeClose()
                        channels.remove(sub.fd)
                        ownedFiles.remove(sub.fd)
                        JvmFileTable.unregister(sub.fd)
                        closed
                    }
                    else -> {
                        results.add(SelectionResult(-95, sub.userData))
                        continue
                    }
                }
            } else {
                -9
=======
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
>>>>>>> codex/wire-nodejs-and-jvm-iouring
            }
        } catch (failure: Exception) { jvmIoError(failure) }
    }

<<<<<<< HEAD
    private fun open(sub: UringSubmission): Int = try {
        require(sub.fd == -100 && sub.addr == 0L && sub.flags == 0)
        require(sub.offset in 0..Int.MAX_VALUE.toLong())
        val flags = sub.offset.toInt()
        require(flags and (3 or 64 or 128 or 512).inv() == 0)
        val access = flags and 3
        require(access != 3 && (flags and (64 or 128 or 512) == 0 || access != 0))
        require(flags and 128 == 0 || flags and 64 != 0)
        val buffer = requireNotNull(sub.buffer).duplicate()
        require(sub.len > 0 && sub.len <= buffer.remaining())
        val path = ByteArray(sub.len).also { buffer.get(it) }.decodeToString(throwOnInvalidSequence = true)
        require('\u0000' !in path)
        val options = mutableSetOf<StandardOpenOption>()
        if (access != 1) options += StandardOpenOption.READ
        if (access != 0) options += StandardOpenOption.WRITE
        if (flags and 128 != 0) options += StandardOpenOption.CREATE_NEW
        else if (flags and 64 != 0) options += StandardOpenOption.CREATE
        if (flags and 512 != 0) options += StandardOpenOption.TRUNCATE_EXISTING
        val file = FileChannel.open(Path.of(path), options)
        try {
            val fd = JvmFileTable.register(file)
            ownedFiles[fd] = file
            registerChannel(file, fd)
            fd
        } catch (failure: Throwable) {
            file.close()
            throw failure
        }
    } catch (failure: Exception) { ioResult(failure) }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        for ((fd, file) in ownedFiles) {
            JvmFileTable.unregister(fd, file)
            try { file.close() } catch (caught: Exception) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        ownedFiles.clear()
        channels.clear()
        if (socketSelector.isInitialized()) {
            try { socketSelector.value.close() } catch (caught: Exception) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun registerChannel(ch: java.nio.channels.Channel, desiredFd: Int): ChannelWrapper? =
        when (ch) {
            is FileChannel -> FileWrapper(fc = ch, id = desiredFd).also { channels[desiredFd] = it }
            is java.nio.channels.SocketChannel -> SocketWrapper(sc = ch, id = desiredFd).also {
                channels[desiredFd] = it
                // Register with reactor
                reactor.bindChannel(ch, setOf(Interest.READ, Interest.WRITE))
=======
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
>>>>>>> codex/wire-nodejs-and-jvm-iouring
            }
            results.size j { results[it] }
        }

<<<<<<< HEAD
    private sealed interface ChannelWrapper {
        val id: Int
        fun close(): Int
        fun sync(metaData: Boolean): Int
        fun truncate(size: Long): Int
        fun map(mode: String, position: Long, size: Long): Int
        fun executeRead(sub: UringSubmission): Int
        fun executeWrite(sub: UringSubmission): Int
        fun executeSync(): Int
        fun executeTruncate(size: Long): Int
        fun executeClose(): Int
    }

    private data class FileWrapper(
        val fc: FileChannel,
        override val id: Int,
    ) : ChannelWrapper {

        override fun close(): Int = try { fc.close(); 0 } catch (_: Exception) { -1 }

        override fun sync(metaData: Boolean): Int = try { fc.force(metaData); 0 } catch (_: Exception) { -1 }

        override fun truncate(size: Long): Int = try { fc.truncate(size); 0 } catch (_: Exception) { -1 }

        override fun map(mode: String, position: Long, size: Long): Int = try {
            val mapMode = when (mode) {
                "r" -> java.nio.channels.FileChannel.MapMode.READ_ONLY
                "rw" -> java.nio.channels.FileChannel.MapMode.READ_WRITE
                "p" -> java.nio.channels.FileChannel.MapMode.PRIVATE
                else -> return -1
            }
            fc.map(mapMode, position, size)
            0
        } catch (_: Exception) { -1 }

        override fun executeRead(sub: UringSubmission): Int {
            val buf = sub.buffer ?: return -22
            if (buf.isReadOnly()) return -22
            return try {
                require(sub.len in 0..buf.remaining())
                val nioBuf = buf.toNioByteBuffer().limit(buf.position() + sub.len)
                val n = fc.read(nioBuf, sub.offset)
                if (n > 0) buf.position(buf.position() + n)
                n
            } catch (failure: Exception) { ioResult(failure) }
        }

        override fun executeWrite(sub: UringSubmission): Int {
            val buf = sub.buffer ?: return -22
            return try {
                require(sub.len in 0..buf.remaining())
                val nioBuf = buf.toNioByteBuffer().limit(buf.position() + sub.len)
                val n = fc.write(nioBuf, sub.offset)
                if (n > 0) buf.position(buf.position() + n)
                n
            } catch (failure: Exception) { ioResult(failure) }
        }

        override fun executeSync(): Int = try { fc.force(true); 0 } catch (failure: Exception) { ioResult(failure) }

        override fun executeTruncate(size: Long): Int = try {
            require(size >= 0)
            if (size > fc.size()) {
                // NIO truncate only shrinks; a positional zero byte provides ftruncate growth.
                check(fc.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)), size - 1) == 1)
            } else fc.truncate(size)
            0
        } catch (failure: Exception) { ioResult(failure) }

        override fun executeClose(): Int = try { fc.close(); 0 } catch (failure: Exception) { ioResult(failure) }
    }

    private data class SocketWrapper(
        val sc: java.nio.channels.SocketChannel,
        override val id: Int,
    ) : ChannelWrapper {

        override fun close(): Int = try { sc.close(); 0 } catch (_: Exception) { -1 }

        override fun sync(metaData: Boolean): Int = -1

        override fun truncate(size: Long): Int = -1

        override fun map(mode: String, position: Long, size: Long): Int = -1

        override fun executeRead(sub: UringSubmission): Int {
            val buf = sub.buffer ?: return -1
            val nioBuf = buf.toNioByteBuffer()
            return try {
                val n = sc.read(nioBuf)
                if (n > 0) buf.position(buf.position() + n)
                n
            } catch (_: Exception) { -1 }
        }

        override fun executeWrite(sub: UringSubmission): Int {
            val buf = sub.buffer ?: return -1
            val nioBuf = buf.toNioByteBuffer()
            return try {
                val n = sc.write(nioBuf)
                if (n > 0) buf.position(buf.position() + n)
                n
            } catch (_: Exception) { -1 }
        }

        override fun executeSync(): Int = -1
        override fun executeTruncate(size: Long): Int = -1
        override fun executeClose(): Int = try { sc.close(); 0 } catch (_: Exception) { -1 }
    }

    private data class ServerWrapper(
        val ssc: java.nio.channels.ServerSocketChannel,
        override val id: Int,
    ) : ChannelWrapper {

        override fun close(): Int = try { ssc.close(); 0 } catch (_: Exception) { -1 }

        override fun sync(metaData: Boolean): Int = -1
        override fun truncate(size: Long): Int = -1
        override fun map(mode: String, position: Long, size: Long): Int = -1
        override fun executeRead(sub: UringSubmission): Int = -1
        override fun executeWrite(sub: UringSubmission): Int = -1
        override fun executeSync(): Int = -1
        override fun executeTruncate(size: Long): Int = -1
        override fun executeClose(): Int = try { ssc.close(); 0 } catch (_: Exception) { -1 }
    }
    override suspend fun batchEnqueue(submissions: Series<UringOp.Companion.UringSubmission>): Series<UringCompletion> {
        val subs = mutableListOf<UringOp.Companion.UringSubmission>()
        for (op in submissions.toList()) {
            subs.add(op)
        }
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            submitBatch(subs)
        }
        val comps = mutableListOf<UringCompletion>()
        for (r in res) {
            comps.add(UringCompletion(r.userData, r.res, 0))
        }
        return seriesOf<UringCompletion>(comps)
=======
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        owned.forEach { JvmFileTable.close(it) }
        owned.clear()
>>>>>>> codex/wire-nodejs-and-jvm-iouring
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
<<<<<<< HEAD
// ^ Note: In real impl, use JNR/Unsafe/foreign.MemorySegment to get native address

private fun ByteBuffer.toNioByteBuffer(): java.nio.ByteBuffer {
    val nio = java.nio.ByteBuffer.wrap(array(), arrayOffset(), capacity()).slice()
    nio.position(position())
    nio.limit(limit())
    return nio
}

actual class FileImpl actual constructor(actual val id: Int) {
    @PublishedApi internal var path: String = ""
    @PublishedApi internal var jvmChannel: java.nio.channels.FileChannel? = JvmFileTable.channel(id)
    actual fun isOpen(): Boolean = jvmChannel?.isOpen ?: false
    actual fun close() {
        val channel = jvmChannel
        jvmChannel = null
        JvmFileTable.unregister(id, channel)
        channel?.close()
    }
    actual fun size(): Long {
        jvmChannel?.let { return it.size() }
        return try {
            java.nio.file.Files.size(java.nio.file.Paths.get(path))
        } catch (_: Exception) {
            -1L
        }
    }
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl {
        val channel = java.nio.channels.FileChannel.open(
                java.nio.file.Paths.get(path),
                if (readOnly) java.util.EnumSet.of(java.nio.file.StandardOpenOption.READ)
                else java.util.EnumSet.of(java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)
            )
        try {
            return FileImpl(JvmFileTable.register(channel)).also { it.path = path }
        } catch (failure: Throwable) {
            channel.close()
            throw failure
        }
    }
}

private fun ioResult(failure: Exception): Int = when (failure) {
    is java.nio.file.NoSuchFileException -> -2
    is java.nio.file.AccessDeniedException, is SecurityException -> -13
    is java.nio.file.FileAlreadyExistsException -> -17
    is java.nio.channels.ClosedChannelException,
    is java.nio.channels.NonReadableChannelException,
    is java.nio.channels.NonWritableChannelException -> -9
    is IllegalArgumentException -> -22
    else -> -5
=======
}

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = JvmFileTable.descriptor(id)?.isOpen() ?: false
    actual fun close() { JvmFileTable.close(id) }
    actual fun size(): Long = JvmFileTable.descriptor(id)?.size() ?: -1L
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl = FileImpl(jvmOpen(path, if (readOnly) 0L else 2L))
>>>>>>> codex/wire-nodejs-and-jvm-iouring
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl =
        throw UnsupportedOperationException("Socket construction requires a supported uring SOCKET adapter")
}
