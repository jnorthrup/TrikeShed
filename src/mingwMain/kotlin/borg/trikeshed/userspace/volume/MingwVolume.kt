@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.volume

import borg.trikeshed.lib.Closeable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import platform.posix.*

class VolumeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The POSIX-call volume on Windows is the C runtime's: NativeVolumeTest runs the same contract against it. */
typealias PosixVolume = MingwVolume

/**
 * A block volume over one C runtime file in binary mode, sized to its capacity with _chsize_s: PosixVolume's
 * contract on Windows (pread/pwrite become _lseeki64 then read/write under the volume's lock, fsync is _commit).
 */
class MingwVolume(
    val path: String,
    override val blockSize: Int = 4096,
    capacityBytes: Long = blockSize.toLong() * 1024L,
) : Volume, Closeable {
    val fd: Int
    val lock = SynchronizedObject()
    var isClosed = false
    override val capacity: Long = capacityBytes / blockSize

    init {
        require(capacityBytes % blockSize == 0L) { "capacity $capacityBytes not aligned to blockSize $blockSize" }
        fd = open(path, O_RDWR or O_CREAT or O_BINARY, 420)
        if (fd < 0) throw VolumeException("Failed to open file: $path (errno: $errno)")
        if (_chsize_s(fd, capacityBytes) != 0) {
            val err = errno
            close(fd)
            throw VolumeException("_chsize_s failed (errno: $err)")
        }
    }

    /** [length] bytes at [offset] through [move] (read or write), looping over short transfers. */
    fun transfer(offset: Long, bytes: ByteArray, length: Int, move: (CPointer<ByteVar>, UInt) -> Int, verb: String) {
        if (length == 0) return
        if (_lseeki64(fd, offset, SEEK_SET) < 0) throw VolumeException("$verb seek failed (errno: $errno)")
        bytes.usePinned { pinned ->
            var at = 0
            while (at < length) {
                val n = move(pinned.addressOf(at), (length - at).toUInt())
                if (n < 0) throw VolumeException("$verb failed (errno: $errno)")
                if (n == 0) break
                at += n
            }
        }
    }

    override suspend fun read(lba: Long, count: Int): ByteArray {
        val buf = ByteArray(count * blockSize)
        synchronized(lock) {
            check(!isClosed) { "Volume is closed" }
            if (lba < 0 || lba + count > capacity) throw VolumeException("Read out of bounds")
            transfer(lba * blockSize, buf, buf.size, { p, n -> read(fd, p, n) }, "read")
        }
        return buf
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        synchronized(lock) {
            check(!isClosed) { "Volume is closed" }
            if (data.size % blockSize != 0) throw VolumeException("write size ${data.size} is not a multiple of blockSize $blockSize")
            if (lba < 0 || lba + data.size / blockSize > capacity) throw VolumeException("Write out of bounds")
            transfer(lba * blockSize, data, data.size, { p, n -> write(fd, p, n) }, "write")
        }
    }

    override suspend fun sync() {
        synchronized(lock) {
            check(!isClosed) { "Volume is closed" }
            if (_commit(fd) != 0) throw VolumeException("_commit failed (errno: $errno)")
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!isClosed) {
                _commit(fd)
                close(fd)
                isClosed = true
            }
        }
    }
}

/** Windows has no kernel ring: the liburing volume is the C runtime volume, as macOS's is PosixVolume. */
actual class LiburingVolume actual constructor(
    val path: String,
    actual override val blockSize: Int,
    capacityBytes: Long,
) : Volume, Closeable {
    val volume = MingwVolume(path, blockSize, capacityBytes)
    actual override val capacity: Long = volume.capacity
    actual override suspend fun read(lba: Long, count: Int): ByteArray = volume.read(lba, count)
    actual override suspend fun write(lba: Long, data: ByteArray) = volume.write(lba, data)
    actual override suspend fun sync() = volume.sync()
    actual override fun close() = volume.close()
}
