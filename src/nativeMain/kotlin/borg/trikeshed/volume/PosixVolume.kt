package borg.trikeshed.volume

import borg.trikeshed.userspace.volume.Volume
import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import borg.trikeshed.lib.Closeable
import kotlinx.cinterop.*
import platform.posix.*

class PosixVolume(
    path: String,
    override val blockSize: Int = 4096,
    capacityBytes: Long = blockSize.toLong() * 1024L
) : Volume, Closeable {

    override val capacity: Long = capacityBytes
    private val fd: Int
    private val fileOps = PosixFileOperations()

    init {
        require(capacityBytes % blockSize == 0L) {
            "capacity $capacityBytes not aligned to blockSize $blockSize"
        }

        fd = fileOps.open(path, readOnly = false)
        if (fd < 0) {
            throw RuntimeException("Failed to open file: $path")
        }

        if (ftruncate(fd, capacityBytes) != 0) {
            val err = errno
            fileOps.close(fd)
            throw RuntimeException("ftruncate failed (errno: $err)")
        }
    }

    override suspend fun read(lba: Long, count: Int): ByteArray {
        val offset = lba * blockSize
        val bytesToRead = count * blockSize
        val buf = ByteArray(bytesToRead)

        kotlinx.atomicfu.locks.synchronized(this) {
            if (offset >= capacity) {
                return ByteArray(blockSize)
            }

            buf.usePinned { pinned ->
                val result = platform.posix.pread(fd, pinned.addressOf(0), bytesToRead.convert(), offset.convert())
                if (result < 0L) {
                    throw RuntimeException("pread failed: $result (errno: $errno)")
                }
            }
        }
        return buf
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        if (data.size > blockSize) {
            throw IllegalArgumentException("write ${data.size} > blockSize $blockSize")
        }
        val offset = lba * blockSize

        kotlinx.atomicfu.locks.synchronized(this) {
            data.usePinned { pinned ->
                val result = platform.posix.pwrite(fd, pinned.addressOf(0), data.size.convert(), offset.convert())
                if (result < 0L) {
                    throw RuntimeException("pwrite failed: $result (errno: $errno)")
                }
            }
        }
    }

    override suspend fun sync() {
        kotlinx.atomicfu.locks.synchronized(this) {
            if (platform.posix.fsync(fd) != 0) {
                throw RuntimeException("fsync failed (errno: $errno)")
            }
        }
    }

    override fun close() {
        kotlinx.atomicfu.locks.synchronized(this) {
            if (fd >= 0) {
                platform.posix.fsync(fd)
                fileOps.close(fd)
            }
        }
    }
}
