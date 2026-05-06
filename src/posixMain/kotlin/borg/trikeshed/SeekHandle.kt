@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.ByteRegion
import kotlinx.cinterop.*
import platform.posix.*

/**
 * POSIX pread-based SeekHandle for native targets.
 *
 * Uses pread/pwrite for thread-safe random access without lseek.
 * No file position state — each operation is absolute.
 */
class PreadSeekHandle : SeekHandle {
   val fds = mutableMapOf<Long, Int>()
   var nextId: Long = 1

    override fun open(filename: String, readOnly: Boolean): Long {
        val flags = if (readOnly) O_RDONLY else O_RDWR
        val fd = platform.posix.open(filename, flags)
        if (fd < 0) {
            throw IllegalStateException("Failed to open $filename: $fd")
        }
        val id = nextId++
        fds[id] = fd
        return id
    }

    override fun close(handle: Long) {
        fds.remove(handle)?.let { platform.posix.close(it) }
    }

    override fun pread(handle: Long, dst: ByteRegion, fileOffset: Long): Int {
        val fd = fds[handle] ?: return -1
        val backing = dst.buffer.array()
        val offset = dst.buffer.arrayOffset() + dst.start
        return backing.usePinned { pinned ->
            platform.posix.pread(
                fd,
                pinned.addressOf(offset),
                dst.size.toULong(),
                fileOffset
            ).toInt()
        }
    }

    override fun pwrite(handle: Long, src: ByteSeries, fileOffset: Long): Int {
        val fd = fds[handle] ?: return -1
        val bytes = src.toArray()
        return bytes.usePinned { pinned ->
            platform.posix.pwrite(
                fd,
                pinned.addressOf(0),
                bytes.size.toULong(),
                fileOffset
            ).toInt()
        }
    }

    override fun size(handle: Long): Long {
        val fd = fds[handle] ?: return -1
        memScoped {
            val stat = alloc<stat>()
            if (fstat(fd, stat.ptr) == 0) {
                return stat.st_size
            }
        }
        return -1
    }

    override fun read(handle: Long, dst: ByteRegion): Int {
        // pread at current position using lseek
        val fd = fds[handle] ?: return -1
        val pos = platform.posix.lseek(fd, 0, SEEK_CUR)
        if (pos < 0) return -1
        return pread(handle, dst, pos).also { bytesRead ->
            if (bytesRead > 0) {
                platform.posix.lseek(fd, pos + bytesRead, SEEK_SET)
            }
        }
    }

    override fun write(handle: Long, src: ByteSeries): Int {
        val fd = fds[handle] ?: return -1
        val pos = platform.posix.lseek(fd, 0, SEEK_CUR)
        if (pos < 0) return -1
        return pwrite(handle, src, pos).also { bytesWritten ->
            if (bytesWritten > 0) {
                platform.posix.lseek(fd, pos + bytesWritten, SEEK_SET)
            }
        }
    }

    override fun seek(handle: Long, position: Long): Long {
        val fd = fds[handle] ?: return -1
        return platform.posix.lseek(fd, position, SEEK_SET)
    }
}

/** POSIX actual: returns pread-based implementation. */
actual fun platformSeekHandle(): SeekHandle = PreadSeekHandle()

/** io_uring not implemented yet — returns null to use pread fallback. */
actual fun ioUringHandle(): SeekHandle? = null
