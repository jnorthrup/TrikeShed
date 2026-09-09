package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.seriesOf
import borg.trikeshed.lib.toList
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.reactor.Interest
import java.nio.channels.FileChannel
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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

    fun channel(fd: Int): FileChannel? = files[fd]?.takeIf { it.isOpen }

    fun unregister(fd: Int, channel: FileChannel? = null) {
        if (channel == null) files.remove(fd) else files.remove(fd, channel)
    }
}

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

    // fd -> ChannelWrapper
    private val channels = ConcurrentHashMap<Int, ChannelWrapper>()

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
            }
            results.add(SelectionResult(res, sub.userData))
        }
        return results
    }

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
            }
            is java.nio.channels.ServerSocketChannel -> ServerWrapper(ssc = ch, id = desiredFd).also {
                channels[desiredFd] = it
                reactor.bindChannel(ch, setOf(Interest.ACCEPT))
            }
            else -> null
        }

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
    }
}

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend =
    JvmUserspaceChannelBackend()

private fun ByteBuffer.arrayAddress(): Long = java.nio.ByteBuffer.wrap(array(), arrayOffset(), capacity())
    .let { wrapper ->
        java.nio.Buffer::class.java.getDeclaredField("address").apply { isAccessible = true }
            .getLong(wrapper)
    }
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
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(-1)
}
