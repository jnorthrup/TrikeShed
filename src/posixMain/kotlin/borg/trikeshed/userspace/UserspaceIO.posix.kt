@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.PosixUringIO
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.seriesOf
import borg.trikeshed.lib.toList
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.EBADF
import platform.posix.EINVAL
import platform.posix.EIO
import platform.posix.ENAMETOOLONG
import platform.posix.EOPNOTSUPP
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.PATH_MAX
import platform.posix.errno
import platform.posix.open

private class PosixUserspaceChannelBackend(
    private val entries: Int,
) : UserspaceChannelBackend {
    override val capabilities: Long = UringOp.caps(UringOp.OPENAT, UringOp.READ, UringOp.READV,
        UringOp.WRITE, UringOp.WRITEV, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.CLOSE)
    private val ownedFds = mutableSetOf<Int>()
    private var closed = false

    init {
        require(entries > 0) { "entries must be positive" }
    }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = submissions.map { sub ->
        val result = if (closed) -EBADF else try {
            execute(sub)
        } catch (_: IllegalArgumentException) {
            -EINVAL
        } catch (_: Exception) {
            -EIO
        }
        SelectionResult(result, sub.userData)
    }

    private fun execute(sub: UringSubmission): Int = when (sub.opcode) {
        UringOp.OPENAT -> open(sub)
        UringOp.READ, UringOp.READV -> transfer(sub, write = false)
        UringOp.WRITE, UringOp.WRITEV -> transfer(sub, write = true)
        UringOp.FSYNC -> if (sub.fd < 0) -EBADF else PosixUringIO.fsync(sub.fd, entries)
        UringOp.FTRUNCATE -> when {
            sub.fd < 0 -> -EBADF
            sub.offset < 0 -> -EINVAL
            else -> PosixUringIO.ftruncate(sub.fd, sub.offset, entries)
        }
        UringOp.CLOSE -> {
            // A failed close may have released the identity; drain must not retry it.
            ownedFds.remove(sub.fd)
            if (sub.fd < 0) -EBADF else PosixUringIO.closeFd(sub.fd, entries)
        }
        else -> -EOPNOTSUPP
    }

    private fun open(sub: UringSubmission): Int {
        require(sub.fd == -100 && sub.addr == 0L && sub.flags == 0)
        require(sub.offset in 0..Int.MAX_VALUE.toLong())
        val flags = sub.offset.toInt()
        require(flags and (3 or 64 or 128 or 512).inv() == 0)
        val access = flags and 3
        require(access != 3 && (flags and (64 or 128 or 512) == 0 || access != 0))
        require(flags and 128 == 0 || flags and 64 != 0)
        val buffer = requireNotNull(sub.buffer).duplicate()
        require(sub.len > 0 && sub.len <= buffer.remaining())
        if (sub.len >= PATH_MAX) return -ENAMETOOLONG
        val bytes = ByteArray(sub.len).also { buffer.get(it) }
        val path = try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { return -EINVAL }
        require('\u0000' !in path)

        // SQEs carry Linux flag values; Darwin's O_CREAT/O_EXCL/O_TRUNC differ.
        var nativeFlags = when (access) { 0 -> O_RDONLY; 1 -> O_WRONLY; else -> O_RDWR }
        if (flags and 64 != 0) nativeFlags = nativeFlags or O_CREAT
        if (flags and 128 != 0) nativeFlags = nativeFlags or O_EXCL
        if (flags and 512 != 0) nativeFlags = nativeFlags or O_TRUNC
        val fd = platform.posix.open(path, nativeFlags, 438u)
        if (fd < 0) return -errno
        ownedFds.add(fd)
        return fd
    }

    private fun transfer(sub: UringSubmission, write: Boolean): Int {
        val buffer = sub.buffer ?: return -EINVAL
        if (sub.fd < 0) return -EBADF
        require(sub.len in 0..buffer.remaining() && sub.offset >= 0)
        require(write || !buffer.isReadOnly())
        val position = buffer.position()
        val start = buffer.arrayOffset() + position
        val result = if (write) {
            PosixUringIO.writeAt(sub.fd, buffer.array(), start, sub.len, sub.offset, entries)
        } else {
            PosixUringIO.readAt(sub.fd, buffer.array(), start, sub.len, sub.offset, entries)
        }
        if (result > sub.len) return -EIO
        if (result > 0) buffer.position(position + result)
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (fd in ownedFds) {
            try {
                val result = PosixUringIO.closeFd(fd, entries)
                check(result == 0) { "CLOSE failed for owned descriptor $fd: $result" }
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        ownedFds.clear()
        failure?.let { throw it }
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val subs = submissions.toList()
        val res = withContext(Dispatchers.Default) { submitBatch(subs) }
        return seriesOf(res.map { UringCompletion(it.userData, it.res, 0) })
    }
}

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = PosixUserspaceChannelBackend(entries)

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {
        if (id >= 0) PosixUringIO.closeFd(id)
    }
    actual fun size(): Long = PosixUringIO.fileSize(id)
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl {
        val flags = if (readOnly) O_RDONLY else (O_RDWR or O_CREAT)
        return FileImpl(open(path, flags, 438u))
    }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(platform.posix.socket(domain, type, protocol))
}
