/*
 * Copyright (c) 2017 TrikeShed Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package borg.trikeshed.userspace.volume

import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import borg.trikeshed.lib.Closeable
import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.locks.SynchronizedObject

class VolumeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class PosixVolume(
    val path: String, 
    override val blockSize: Int = 4096,
    capacityBytes: Long = blockSize.toLong() * 1024L
) : Volume, Closeable {

    private val fileOps = PosixFileOperations()
    private val fd: Int
    private val lock = SynchronizedObject()
    private var isClosed = false

    override val capacity: Long = capacityBytes / blockSize

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
                throw VolumeException("Failed to open file: $path (errno: $err)")
            }
            secondFd
        } else {
            initialFd
        }

        if (ftruncate(fd, capacityBytes) != 0) {
            val err = errno
            fileOps.close(fd)
            throw VolumeException("ftruncate failed (errno: $err)")
        }
    }

    override suspend fun read(lba: Long, count: Int): ByteArray {
        val offset = lba * blockSize
        val bytesToRead = count * blockSize
        val buf = ByteArray(bytesToRead)

        synchronized(lock) {
            check(!isClosed) { "Volume is closed" }
            if (lba < 0 || lba + count > capacity) {
                throw VolumeException("Read out of bounds")
            }

            if (bytesToRead > 0) {
                buf.usePinned { pinned ->
                    val result = pread(fd, pinned.addressOf(0), bytesToRead.convert(), offset.convert())
                    if (result < 0L) {
                        throw VolumeException("pread failed: $result (errno: $errno)")
                    }
                }
            }
        }
        return buf
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        val offset = lba * blockSize
        val bytesToWrite = data.size

        synchronized(lock) {
            check(!isClosed) { "Volume is closed" }
            if (bytesToWrite % blockSize != 0) {
                 throw VolumeException("write size ${data.size} is not a multiple of blockSize $blockSize")
            }
            val count = bytesToWrite / blockSize
            if (lba < 0 || lba + count > capacity) {
                 throw VolumeException("Write out of bounds")
            }

            if (bytesToWrite > 0) {
                data.usePinned { pinned ->
                    val result = pwrite(fd, pinned.addressOf(0), bytesToWrite.convert(), offset.convert())
                    if (result < 0L) {
                        throw VolumeException("pwrite failed: $result (errno: $errno)")
                    }
                }
            }
        }
    }

    override suspend fun sync() {
        synchronized(lock) {
            check(!isClosed) { "Volume is closed" }
            if (fsync(fd) != 0) {
                throw VolumeException("fsync failed (errno: $errno)")
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!isClosed) {
                if (fd >= 0) {
                    fsync(fd)
                    fileOps.close(fd)
                }
                isClosed = true
            }
        }
    }
}
