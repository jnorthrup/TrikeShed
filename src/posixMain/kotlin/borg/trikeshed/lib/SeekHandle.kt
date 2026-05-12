@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.lib

import borg.trikeshed.PosixUringIO
import borg.trikeshed.userspace.ByteRegion
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.*

class PreadSeekHandle : SeekHandle {
    private val fds = mutableMapOf<Long, Int>()
    private var nextId: Long = 1

    override fun open(filename: CharSequence, readOnly: Boolean): Long {
        val fd = if (readOnly) open(filename.toString(), O_RDONLY) else open(filename.toString(), O_RDWR or O_CREAT, 438)
        if (fd < 0) {
            throw IllegalStateException("Failed to open $filename: $fd")
        }
        return nextId++.also { fds[it] = fd }
    }

    override fun close(handle: Long) {
        fds.remove(handle)?.let(::close)
    }

    override fun pread(handle: Long, dst: ByteRegion, fileOffset: Long): Int {
        val fd = fds[handle] ?: return -1
        val backing = dst.buffer.array()
        val start = dst.buffer.arrayOffset() + dst.start
        return PosixUringIO.readAt(fd, backing, start, dst.size, fileOffset)
    }

    override fun pwrite(handle: Long, src: ByteSeries, fileOffset: Long): Int {
        val fd = fds[handle] ?: return -1
        val bytes = src.toArray()
        return PosixUringIO.writeAt(fd, bytes, 0, bytes.size, fileOffset)
    }

    override fun size(handle: Long): Long {
        val fd = fds[handle] ?: return -1
        return memScoped {
            val statBuf = alloc<stat>()
            if (fstat(fd, statBuf.ptr) == 0) statBuf.st_size else -1L
        }
    }

    override fun read(handle: Long, dst: ByteRegion): Int {
        val fd = fds[handle] ?: return -1
        val pos = lseek(fd, 0, SEEK_CUR)
        if (pos < 0) return -1
        return pread(handle, dst, pos).also { bytesRead ->
            if (bytesRead > 0) lseek(fd, pos + bytesRead, SEEK_SET)
        }
    }

    override fun write(handle: Long, src: ByteSeries): Int {
        val fd = fds[handle] ?: return -1
        val pos = lseek(fd, 0, SEEK_CUR)
        if (pos < 0) return -1
        return pwrite(handle, src, pos).also { bytesWritten ->
            if (bytesWritten > 0) lseek(fd, pos + bytesWritten, SEEK_SET)
        }
    }

    override fun seek(handle: Long, position: Long): Long {
        val fd = fds[handle] ?: return -1
        return lseek(fd, position, SEEK_SET)
    }
}

class UringSeekHandle : SeekHandle {
    private val fds: MutableMap<Long, Int> = mutableMapOf<Long, Int>()
    private val positions: MutableMap<Long, Long> = mutableMapOf<Long, Long>()
    private var nextId: Long = 1

    fun isAvailable(): Boolean = PosixUringIO.isAvailable()

    override fun open(filename: CharSequence, readOnly: Boolean): Long {
        val fd = if (readOnly) open(filename.toString(), O_RDONLY) else open(filename.toString(), O_RDWR or O_CREAT, 438)
        if (fd < 0) {
            throw IllegalStateException("Failed to open $filename: $fd")
        }
        val id = nextId++
        fds[id] = fd
        positions[id] = 0L
        return id
    }

    override fun close(handle: Long) {
        positions.remove(handle)
        fds.remove(handle)?.let { PosixUringIO.closeFd(it) }
    }

    override fun pread(handle: Long, dst: ByteRegion, fileOffset: Long): Int {
        val fd = fds[handle] ?: return -1
        val backing = dst.buffer.array()
        val start = dst.buffer.arrayOffset() + dst.start
        return PosixUringIO.readAt(fd, backing, start, dst.size, fileOffset)
    }

    override fun pwrite(handle: Long, src: ByteSeries, fileOffset: Long): Int {
        val fd: Int = fds[handle] ?: return -1
        val bytes: ByteArray = src.toArray()
        return PosixUringIO.writeAt(fd, bytes, 0, bytes.size, fileOffset)
    }

    override fun size(handle: Long): Long {
        val fd: Int = fds[handle] ?: return -1
        return memScoped {
            val statBuf = alloc<stat>()
            if (fstat(fd, statBuf.ptr) == 0) statBuf.st_size else -1L
        }
    }

    override fun read(handle: Long, dst: ByteRegion): Int {
        val pos: Long = positions[handle] ?: return -1
        return pread(handle, dst, pos).also { bytesRead ->
            if (bytesRead > 0) positions[handle] = pos + bytesRead
        }
    }

    override fun write(handle: Long, src: ByteSeries): Int {
        val pos: Long = positions[handle] ?: return -1
        return pwrite(handle, src, pos).also { bytesWritten ->
            if (bytesWritten > 0) positions[handle] = pos + bytesWritten
        }
    }

    override fun seek(handle: Long, position: Long): Long {
        if (!fds.containsKey(handle)) return -1
        positions[handle] = position
        return position
    }
}

private val posixUringSeekHandle: SeekHandle? by lazy {
    UringSeekHandle().takeIf { it.isAvailable() }
}

actual fun platformSeekHandle(): SeekHandle = ioUringHandle() ?: PreadSeekHandle()

actual fun ioUringHandle(): SeekHandle? = posixUringSeekHandle
