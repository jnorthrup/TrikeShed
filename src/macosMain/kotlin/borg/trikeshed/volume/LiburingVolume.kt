package borg.trikeshed.volume

import borg.trikeshed.userspace.volume.Volume

class LiburingVolume private constructor() : Volume {
    override val blockSize: Int = 4096
    override val capacity: Long = 0L
    override suspend fun read(lba: Long, count: Int): ByteArray =
        throw UnsupportedOperationException("LiburingVolume is Linux-only; io_uring is unavailable on macOS")
    override suspend fun write(lba: Long, data: ByteArray): Unit =
        throw UnsupportedOperationException("LiburingVolume is Linux-only; io_uring is unavailable on macOS")
    override suspend fun sync(): Unit =
        throw UnsupportedOperationException("LiburingVolume is Linux-only; io_uring is unavailable on macOS")
    companion object { fun unsupported(): LiburingVolume = LiburingVolume() }
}
