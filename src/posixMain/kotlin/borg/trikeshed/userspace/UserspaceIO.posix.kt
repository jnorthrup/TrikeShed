@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.PosixUringIO
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.open

private class PosixUserspaceChannelBackend(
    private val entries: Int,
) : UserspaceChannelBackend {
    override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val bytes = buffer.array()
        val start = buffer.arrayOffset() + buffer.position()
        val length = buffer.remaining()
        if (length <= 0) return 0
        val result = PosixUringIO.readAt(file.id, bytes, start, length, offset, entries)
        if (result > 0) buffer.position(buffer.position() + result)
        return result
    }

    override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val bytes = buffer.array()
        val start = buffer.arrayOffset() + buffer.position()
        val length = buffer.remaining()
        if (length <= 0) return 0
        val result = PosixUringIO.writeAt(file.id, bytes, start, length, offset, entries)
        if (result > 0) buffer.position(buffer.position() + result)
        return result
    }

    override fun accept(file: FileImpl): Int = -1
    override fun connect(file: FileImpl, address: CharSequence, port: Int): Int = -1
    override fun close(file: FileImpl): Int = PosixUringIO.closeFd(file.id, entries)
    override fun sync(file: FileImpl, metaData: Boolean): Int = if (metaData) PosixUringIO.fsync(file.id, entries) else PosixUringIO.fdatasync(file.id, entries)
    override fun truncate(file: FileImpl, size: Long): Int = PosixUringIO.ftruncate(file.id, size, entries)
    override fun map(file: FileImpl, mode: CharSequence, position: Long, size: Long): Int {
        if (file.id < 0) return -1
        val prot = when (mode) {
            "r" -> 1 // PROT_READ
            "rw" -> 3 // PROT_READ | PROT_WRITE
            else -> return -1
        }
        val flags = 0x01 // MAP_SHARED
        return PosixUringIO.mmap(0, size.toInt(), prot, flags, file.id, position).toInt()
    }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        if (submissions.isEmpty()) return emptyList()
        val results = mutableListOf<SelectionResult>()
        submissions.forEach { sub ->
            when (sub.opcode) {
                UringOp.READV -> {
                    val bytes = sub.buffer?.array() ?: return@forEach
                    val start = (sub.buffer?.arrayOffset() ?: 0) + (sub.buffer?.position() ?: 0)
                    val len = sub.buffer?.remaining() ?: 0
                    val n = PosixUringIO.readAt(sub.fd, bytes, start, len, sub.offset, entries)
                    results.add(SelectionResult(n, sub.userData))
                }
                UringOp.WRITEV -> {
                    val bytes = sub.buffer?.array() ?: return@forEach
                    val start = (sub.buffer?.arrayOffset() ?: 0) + (sub.buffer?.position() ?: 0)
                    val len = sub.buffer?.remaining() ?: 0
                    val n = PosixUringIO.writeAt(sub.fd, bytes, start, len, sub.offset, entries)
                    results.add(SelectionResult(n, sub.userData))
                }
                UringOp.FSYNC -> {
                    val n = PosixUringIO.fsync(sub.fd, entries)
                    results.add(SelectionResult(n, sub.userData))
                }
                UringOp.FTRUNCATE -> {
                    val n = PosixUringIO.ftruncate(sub.fd, sub.offset, entries)
                    results.add(SelectionResult(n, sub.userData))
                }
                else -> results.add(SelectionResult(-1, sub.userData))
            }
        }
        return results
    }
}

internal actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = PosixUserspaceChannelBackend(entries)

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {
        if (id >= 0) PosixUringIO.closeFd(id)
    }
    actual fun size(): Long = PosixUringIO.fileSize(id)
}

internal actual object FilesImpl {
    actual fun open(path: CharSequence, readOnly: Boolean): FileImpl {
        val flags = if (readOnly) O_RDONLY else (O_RDWR or O_CREAT)
        return FileImpl(open(path.toString(), flags, 438u))
    }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(platform.posix.socket(domain, type, protocol))
}
