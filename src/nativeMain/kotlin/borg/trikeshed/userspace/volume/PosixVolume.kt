package borg.trikeshed.userspace.volume

import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import borg.trikeshed.lib.Closeable
import platform.posix.*
import kotlinx.cinterop.*

class PosixVolume(
    val path: String, 
    override val blockSize: Int = 4096,
    capacityBytes: Long = blockSize.toLong() * 1024L
) : Volume, Closeable {
    private val fileOps = PosixFileOperations()
    private val fd: Int

    override val capacity: Long = capacityBytes

    init {
        require(capacityBytes % blockSize == 0L) {
            "capacity $capacityBytes not aligned to blockSize $blockSize"
        }

        val initialFd = fileOps.open(path, readOnly = false)
        fd = if (initialFd < 0) {
            fileOps.write(path, ByteArray(0))
            val secondFd = fileOps.open(path, readOnly = false)
            if (secondFd < 0) {
                val err = errno
                throw RuntimeException("Failed to open file: $path (errno: $err)")
            }
            secondFd
        } else {
            initialFd
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

        if (bytesToRead > 0) {
            buf.usePinned { pinned ->
                val result = pread(fd, pinned.addressOf(0), bytesToRead.convert(), offset.convert())
                if (result < 0L) {
                    throw RuntimeException("pread failed: $result (errno: $errno)")
                }
            }
        }
        return buf
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        val offset = lba * blockSize
        val bytesToWrite = data.size

        if (bytesToWrite > 0) {
            data.usePinned { pinned ->
                val result = pwrite(fd, pinned.addressOf(0), bytesToWrite.convert(), offset.convert())
                if (result < 0L) {
                    throw RuntimeException("pwrite failed: $result (errno: $errno)")
                }
            }
        }
    }

    override suspend fun sync() {
        if (fsync(fd) != 0) {
            throw RuntimeException("fsync failed (errno: $errno)")
        }
    }

    override fun close() {
        if (fd >= 0) {
            fsync(fd)
            fileOps.close(fd)
        }
    }
}
