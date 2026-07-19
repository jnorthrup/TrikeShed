package borg.trikeshed.userspace.volume

class LiburingVolume(val path: String, override val blockSize: Int = 4096) : Volume {
    // Falls back to PosixVolume due to missing io_uring integration in target platform test setup.
    private val posixVolume = PosixVolume(path, blockSize)

    override val capacity: Long
        get() = posixVolume.capacity

    override suspend fun read(lba: Long, count: Int): ByteArray {
        return posixVolume.read(lba, count)
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        posixVolume.write(lba, data)
    }

    override suspend fun sync() {
        posixVolume.sync()
    }
}
