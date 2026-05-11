package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import java.nio.channels.FileChannel

private class JvmUserspaceChannelBackend : UserspaceChannelBackend {
    private val channels = mutableMapOf<Int, FileChannel>()

    override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val ch = channels[file.id] ?: return -1
        return try {
            ch.read(buffer.toNioByteBuffer(), offset).also { if (it > 0) buffer.position(buffer.position() + it) }
        } catch (_: Exception) { -1 }
    }

    override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val ch = channels[file.id] ?: return -1
        return try {
            ch.write(buffer.toNioByteBuffer(), offset).also { if (it > 0) buffer.position(buffer.position() + it) }
        } catch (_: Exception) { -1 }
    }

    override fun accept(file: FileImpl): Int = -1
    override fun connect(file: FileImpl, address: String, port: Int): Int = -1
    override fun close(file: FileImpl): Int = channels.remove(file.id)?.use { 0 } ?: 0

    override fun sync(file: FileImpl, metaData: Boolean): Int {
        val ch = channels[file.id] ?: return -1
        return try {
            if (metaData) ch.force(true) else ch.force(false)
            0
        } catch (_: Exception) { -1 }
    }

    override fun truncate(file: FileImpl, size: Long): Int {
        val ch = channels[file.id] ?: return -1
        return try {
            ch.truncate(size)
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
            ch.map(mapMode, position, size)
            0
        } catch (_: Exception) { -1 }
    }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        if (submissions.isEmpty()) return emptyList()
        val results = mutableListOf<SelectionResult>()
        submissions.forEach { sub ->
            val ch = channels[sub.fd]
            if (ch == null) {
                results.add(SelectionResult(-1, sub.userData))
                return@forEach
            }
            val nioBuf = sub.buffer?.toNioByteBuffer()
            val res = when (sub.opcode) {
                UringOp.READV -> try { ch.read(nioBuf, sub.offset).also { if (it > 0 && nioBuf != null) sub.buffer.position(sub.buffer.position() + it) } } catch (_: Exception) { -1 }
                UringOp.WRITEV -> try { ch.write(nioBuf, sub.offset).also { if (it > 0 && nioBuf != null) sub.buffer.position(sub.buffer.position() + it) } } catch (_: Exception) { -1 }
                UringOp.FSYNC -> try { ch.force(false); 0 } catch (_: Exception) { -1 }
                UringOp.FTRUNCATE -> try { ch.truncate(sub.offset); 0 } catch (_: Exception) { -1 }
                UringOp.CLOSE -> try { channels.remove(sub.fd)?.close(); 0 } catch (_: Exception) { -1 }
                else -> -1
            }
            results.add(SelectionResult(res, sub.userData))
        }
        return results
    }
}

internal actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = JvmUserspaceChannelBackend()

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