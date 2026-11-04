package borg.trikeshed.userspace.nio.file.spi

/** Read one opened file with a bounded reusable buffer. The callback must not retain the buffer. */
interface ChunkedFileReader {
    fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long
}

class FileChangedException : IllegalStateException("File path changed during reading")
