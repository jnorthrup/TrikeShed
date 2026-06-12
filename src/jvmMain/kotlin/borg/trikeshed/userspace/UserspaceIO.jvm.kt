package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import java.nio.channels.FileChannel
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM backend for [FunctionalUringFacade] using Java NIO.
 *
 * Maps [UringSubmission] -> NIO operations.
 * File I/O: direct FileChannel (blocking, but in real impl offloaded to thread pool).
 * Socket I/O: registered with [JvmReactorOperations] for async select.
 */
private class JvmUserspaceChannelBackend(
    private val reactor: JvmReactorOperations = JvmReactorOperations(),
) : UserspaceChannelBackend {

    // fd -> ChannelWrapper
    private val channels = ConcurrentHashMap<Int, ChannelWrapper>()
    private val fdCounter = AtomicInteger(3000)

    override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int =
        submitBatch(listOf(UringOp.Companion.Submissions.read(file.id, buffer.arrayAddress(), buffer.capacity(), offset, 0)))
            .firstOrNull()?.res ?: -1

    override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int =
        submitBatch(listOf(UringOp.Companion.Submissions.write(file.id, buffer.arrayAddress(), buffer.capacity(), offset, 0)))
            .firstOrNull()?.res ?: -1

    override fun accept(file: FileImpl): Int = -1
    override fun connect(file: FileImpl, address: String, port: Int): Int = -1

    override fun close(file: FileImpl): Int = channels.remove(file.id)?.close() ?: run { 0 }

    override fun sync(file: FileImpl, metaData: Boolean): Int {
        val ch = channels[file.id] ?: return -1
        return try {
            ch.fc.force(metaData)
            0
        } catch (_: Exception) { -1 }
    }

    override fun truncate(file: FileImpl, size: Long): Int {
        val ch = channels[file.id] ?: return -1
        return try {
            ch.fc.truncate(size)
            0
        } catch (_: Exception) { -1 }
    }

    override fun map(file: FileImpl, mode: String, position: Long, size: Long): Int {
        val ch = channels[file.id] ?: return -1
        return try {
            val mapMode = when (mode) {
                "r" -> java.nio.channels.FileChannel.MapMode.READ_ONLY
                "rw" -> java.nio.channels.FileChannel.MapMode.READ_WRITE
                "p" -> java.nio.channels.FileChannel.MapMode.PRIVATE
                else -> return -1
            }
            ch.fc.map(mapMode, position, size)
            0
        } catch (_: Exception) { -1 }
    }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        if (submissions.isEmpty()) return emptyList()

        val results = mutableListOf<SelectionResult>()

        for (sub in submissions) {
            val wrapper = channels[sub.fd]
            if (wrapper == null) {
                // Auto-register if not present (for files opened via FilesImpl)
                if (sub.opcode in setOf(UringOp.READ, UringOp.WRITE, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE)) {
                    // File operation - create wrapper lazily
                    val fc = java.nio.channels.FileChannel.open(
                        java.nio.file.Paths.get(""),
                        java.util.EnumSet.noneOf(java.nio.file.StandardOpenOption::class.java)
                    )
                    wrapper = registerChannel(fc, sub.fd)
                    channels[sub.fd] = wrapper!!
                } else {
                    results.add(SelectionResult(-1, sub.userData))
                    continue
                }
            }

            val res = when (sub.opcode) {
                UringOp.READ, UringOp.READV -> executeRead(wrapper!!, sub)
                UringOp.WRITE, UringOp.WRITEV -> executeWrite(wrapper!!, sub)
                UringOp.FSYNC -> executeSync(wrapper!!)
                UringOp.FTRUNCATE -> executeTruncate(wrapper!!, sub.offset)
                UringOp.CLOSE -> executeClose(wrapper!!)
                else -> {
                    results.add(SelectionResult(-1, sub.userData))
                    continue
                }
            }
            results.add(SelectionResult(res, sub.userData))
        }
        return results
    }

    private fun executeRead(w: ChannelWrapper, sub: UringSubmission): Int {
        val nioBuf = sub.buffer?.toNioByteBuffer() ?: return -1
        return try {
            when (w) {
                is FileWrapper -> w.fc.read(nioBuf, sub.offset).also { if (it > 0) sub.buffer?.position(sub.buffer.position() + it) }
                is SocketWrapper -> w.sc.read(nioBuf).also { if (it > 0) sub.buffer?.position(sub.buffer.position() + it) }
                else -> -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun executeWrite(w: ChannelWrapper, sub: UringSubmission): Int {
        val nioBuf = sub.buffer?.toNioByteBuffer() ?: return -1
        return try {
            when (w) {
                is FileWrapper -> w.fc.write(nioBuf, sub.offset).also { if (it > 0) sub.buffer?.position(sub.buffer.position() + it) }
                is SocketWrapper -> w.sc.write(nioBuf).also { if (it > 0) sub.buffer?.position(sub.buffer.position() + it) }
                else -> -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun executeSync(w: ChannelWrapper): Int = when (w) {
        is FileWrapper -> try { w.fc.force(false); 0 } catch (_: Exception) { -1 }
        else -> -1
    }

    private fun executeTruncate(w: ChannelWrapper, size: Long): Int = when (w) {
        is FileWrapper -> try { w.fc.truncate(size); 0 } catch (_: Exception) { -1 }
        else -> -1
    }

    private fun executeClose(w: ChannelWrapper): Int = when (w) {
        is FileWrapper -> try { w.fc.close(); 0 } catch (_: Exception) { -1 }
        is SocketWrapper -> try { w.sc.close(); 0 } catch (_: Exception) { -1 }
    }

    /**
     * Register a channel backend.
     * Returns wrapper or null if channel type unsupported.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun registerChannel(ch: java.nio.channels.Channel, desiredFd: Int): ChannelWrapper? =
        when (ch) {
            is FileChannel -> FileWrapper(ch).also { channels[desiredFd] = it }
            is java.nio.channels.SocketChannel -> SocketWrapper(ch).also {
                channels[desiredFd] = it
                // Register with reactor
                reactor.bindChannel(ch, setOf(Interest.READ, Interest.WRITE))
            }
            is java.nio.channels.ServerSocketChannel -> ServerWrapper(ch).also {
                channels[desiredFd] = it
                reactor.bindChannel(ch, setOf(Interest.ACCEPT))
            }
            else -> null
        }

    private sealed interface ChannelWrapper {
        val id: Int
    }

    private data class FileWrapper(
        val fc: FileChannel,
        override val id: Int = fdCounter.incrementAndGet(),
    ) : ChannelWrapper

    private data class SocketWrapper(
        val sc: java.nio.channels.SocketChannel,
        override val id: Int = fdCounter.incrementAndGet(),
    ) : ChannelWrapper

    private data class ServerWrapper(
        val ssc: java.nio.channels.ServerSocketChannel,
        override val id: Int = fdCounter.incrementAndGet(),
    ) : ChannelWrapper
}

internal actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend =
    JvmUserspaceChannelBackend()

private fun ByteBuffer.arrayAddress(): Long = java.nio.ByteBuffer.wrap(array(), arrayOffset(), capacity())
    .let { java.lang.reflect.Field::class.java.getDeclaredField("address").apply { isAccessible = true } }
    .run { getLong(this) }
// ^ Note: In real impl, use JNR/Unsafe/foreign.MemorySegment to get native address

private fun ByteBuffer.toNioByteBuffer(): java.nio.ByteBuffer {
    val nio = java.nio.ByteBuffer.wrap(array(), arrayOffset(), capacity())
    nio.position(position())
    nio.limit(limit())
    return nio
}

actual class FileImpl actual constructor(actual val id: Int) {
    @PublishedApi internal var path: String = ""
    @PublishedApi internal var jvmChannel: java.nio.channels.FileChannel? = null
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {
        jvmChannel?.close()
        jvmChannel = null
    }
    actual fun size(): Long = jvmChannel?.size() ?: java.io.File(path).let { if (it.exists()) it.length() else -1L }
}

internal actual object FilesImpl {
    private var nextId = 1
    actual fun open(path: String, readOnly: Boolean): FileImpl =
        FileImpl(nextId++).also { fi ->
            fi.path = path
            fi.jvmChannel = java.nio.channels.FileChannel.open(
                java.nio.file.Paths.get(path),
                if (readOnly) java.util.EnumSet.of(java.nio.file.StandardOpenOption.READ)
                else java.util.EnumSet.of(java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)
            )
        }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(-1)
}