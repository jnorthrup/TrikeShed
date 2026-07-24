package borg.trikeshed.userspace.volume

import borg.trikeshed.lib.Closeable

expect class LiburingVolume(
    path: String,
    blockSize: Int = 4096,
    capacityBytes: Long = blockSize.toLong() * 1024L
) : Volume, Closeable {
    override val blockSize: Int
    override val capacity: Long
    override suspend fun read(lba: Long, count: Int): ByteArray
    override suspend fun write(lba: Long, data: ByteArray)
    override suspend fun sync()
    override fun close()
}
