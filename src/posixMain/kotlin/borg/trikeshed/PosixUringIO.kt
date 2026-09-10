@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed

import borg.trikeshed.userspace.posixCompletion
import kotlinx.cinterop.*
import platform.posix.*

/** POSIX primitives for emulated SQEs. Kernel execution belongs to the owning backend ring. */
internal object PosixUringIO {
    fun readAt(fd: Int, bytes: ByteArray, start: Int, length: Int, offset: Long, entries: Int = 2): Int {
        require(start >= 0 && length >= 0 && start <= bytes.size - length)
        if (offset < -1) return -22
        return bytes.usePinned { pinned ->
            val ptr = if (length == 0) null else pinned.addressOf(start)
            posixCompletion(if (offset == -1L) read(fd, ptr, length.convert()).toInt()
            else pread(fd, ptr, length.convert(), offset).toInt())
        }
    }

    fun writeAt(fd: Int, bytes: ByteArray, start: Int, length: Int, offset: Long, entries: Int = 2): Int {
        require(start >= 0 && length >= 0 && start <= bytes.size - length)
        if (offset < -1) return -22
        return bytes.usePinned { pinned ->
            val ptr = if (length == 0) null else pinned.addressOf(start)
            posixCompletion(if (offset == -1L) write(fd, ptr, length.convert()).toInt()
            else pwrite(fd, ptr, length.convert(), offset).toInt())
        }
    }

    fun closeFd(fd: Int, entries: Int = 2): Int = posixCompletion(close(fd))

    fun fileSize(fd: Int): Long = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) == 0) st.st_size else -1L
    }

    fun fsync(fd: Int, entries: Int = 2): Int = posixCompletion(platform.posix.fsync(fd))
    fun fdatasync(fd: Int, entries: Int = 2): Int = fsync(fd, entries)
    fun ftruncate(fd: Int, size: Long, entries: Int = 256): Int =
        if (size < 0) -22 else posixCompletion(platform.posix.ftruncate(fd, size))

    fun mmap(addr: Long, length: Int, prot: Int, flags: Int, fd: Int, offset: Long): Long {
        val base = if (addr == 0L) null else addr.toCPointer<ByteVar>()
        return platform.posix.mmap(base, length.toULong(), prot, flags, fd, offset)?.toLong() ?: -1L
    }
}
