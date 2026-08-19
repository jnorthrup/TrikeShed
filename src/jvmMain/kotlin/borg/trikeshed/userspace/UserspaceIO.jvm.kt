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


    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        if (submissions.isEmpty()) return emptyList()

        val results = mutableListOf<SelectionResult>()

        for (sub in submissions) {
            var wrapper = channels[sub.fd]
            if (wrapper == null) {
                // Auto-register if not present (for files opened via FilesImpl)
                if (sub.opcode in setOf(UringOp.READ, UringOp.WRITE, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE)) {
                    // File operation - create wrapper lazily
                    val fc = java.nio.channels.FileChannel.open(
                        java.nio.file.Paths.get(""),
                        java.util.EnumSet.noneOf(java.nio.file.StandardOpenOption::class.java)
                    )
                    val newWrapper = registerChannel(fc, sub.fd)
                    if (newWrapper != null) {
                        wrapper = newWrapper
                        channels[sub.fd] = wrapper
                    }
                } else {
                    results.add(SelectionResult(-1, sub.userData))
                    continue
                }
            }

            val res = if (wrapper != null) {
                when (sub.opcode) {
                    UringOp.READ, UringOp.READV -> wrapper.executeRead(sub)
                    UringOp.WRITE, UringOp.WRITEV -> wrapper.executeWrite(sub)
                    UringOp.FSYNC -> wrapper.executeSync()
                    UringOp.FTRUNCATE -> wrapper.executeTruncate(sub.offset)
                    UringOp.CLOSE -> wrapper.executeClose()
                    else -> {
                        results.add(SelectionResult(-1, sub.userData))
                        continue
                    }
                }
            } else {
                -1
            }
            results.add(SelectionResult(res, sub.userData))
        }
        return results
    }

    @Suppress("UNUSED_PARAMETER")
    private fun registerChannel(ch: java.nio.channels.Channel, desiredFd: Int): ChannelWrapper? =
        when (ch) {
            is FileChannel -> FileWrapper(fc = ch, id = fdCounter.incrementAndGet()).also { channels[desiredFd] = it }
            is java.nio.channels.SocketChannel -> SocketWrapper(sc = ch, id = fdCounter.incrementAndGet()).also {
                channels[desiredFd] = it
                // Register with reactor
                reactor.bindChannel(ch, setOf(Interest.READ, Interest.WRITE))
            }
            is java.nio.channels.ServerSocketChannel -> ServerWrapper(ssc = ch, id = fdCounter.incrementAndGet()).also {
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
            val buf = sub.buffer ?: return -1
            val nioBuf = buf.toNioByteBuffer()
            return try {
                val n = fc.read(nioBuf, sub.offset)
                if (n > 0) buf.position(buf.position() + n)
                n
            } catch (_: Exception) { -1 }
        }

        override fun executeWrite(sub: UringSubmission): Int {
            val buf = sub.buffer ?: return -1
            val nioBuf = buf.toNioByteBuffer()
            return try {
                val n = fc.write(nioBuf, sub.offset)
                if (n > 0) buf.position(buf.position() + n)
                n
            } catch (_: Exception) { -1 }
        }

        override fun executeSync(): Int = try { fc.force(false); 0 } catch (_: Exception) { -1 }

        override fun executeTruncate(size: Long): Int = try { fc.truncate(size); 0 } catch (_: Exception) { -1 }

        override fun executeClose(): Int = try { fc.close(); 0 } catch (_: Exception) { -1 }
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