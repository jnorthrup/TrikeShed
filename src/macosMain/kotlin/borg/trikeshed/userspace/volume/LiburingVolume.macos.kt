package borg.trikeshed.userspace.volume

import borg.trikeshed.lib.Closeable

actual class LiburingVolume actual constructor(
    val path: String,
    actual override val blockSize: Int,
    capacityBytes: Long
) : Volume, Closeable {

    private val posixVolume = PosixVolume(path, blockSize, capacityBytes)

    actual override val capacity: Long = posixVolume.capacity

    actual override suspend fun read(lba: Long, count: Int): ByteArray {
        return posixVolume.read(lba, count)
    }

    actual override suspend fun write(lba: Long, data: ByteArray) {
        posixVolume.write(lba, data)
    }

    actual override suspend fun sync() {
        posixVolume.sync()
    }
    
    actual override fun close() {
        posixVolume.close()
    }
}
