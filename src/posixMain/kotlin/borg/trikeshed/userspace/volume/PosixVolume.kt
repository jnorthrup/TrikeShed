package borg.trikeshed.userspace.volume

import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import platform.posix.*
import kotlinx.cinterop.*

class PosixVolume(val path: String, override val blockSize: Int = 4096) : Volume {
    private val fileOps = PosixFileOperations()
    private var fd: Int = -1

    init {
        fd = fileOps.open(path, readOnly = false)
        if (fd < 0) {
            val file = fopen(path, "wb")
            if (file != null) {
                fclose(file)
            }
            fd = fileOps.open(path, readOnly = false)
            if (fd < 0) {
                throw RuntimeException("Failed to open or create file $path")
            }
        }
    }

    override val capacity: Long
        get() = fileOps.size(fd) / blockSize

    override suspend fun read(lba: Long, count: Int): ByteArray {
        val offset = lba * blockSize
        val bytesToRead = count * blockSize
        val buf = ByteArray(bytesToRead)

        memScoped {
            val result = pread(fd, buf.refTo(0), bytesToRead.convert(), offset.convert())
            if (result < 0L) {
                throw RuntimeException("pread failed: $result")
            }
        }
        return buf
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        val offset = lba * blockSize
        val bytesToWrite = data.size

        memScoped {
            val result = pwrite(fd, data.refTo(0), bytesToWrite.convert(), offset.convert())
            if (result < 0L) {
                throw RuntimeException("pwrite failed: $result")
            }
        }
    }

    override suspend fun sync() {
        val result = fsync(fd)
        if (result < 0) {
            throw RuntimeException("fsync failed: $result")
        }
    }
}
