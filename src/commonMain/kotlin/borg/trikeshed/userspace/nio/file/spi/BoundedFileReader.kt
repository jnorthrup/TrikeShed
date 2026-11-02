package borg.trikeshed.userspace.nio.file.spi

/**
 * A file reader that enforces its limit while reading, not by a separate size check.
 * Its filesystem implementation remains trusted; this contract does not bound I/O
 * latency or authorize access to the requested path.
 */
interface BoundedFileReader {
    fun readBounded(path: String, maxBytes: Int): ByteArray
}

/** The object exists but did not fit in the caller's read budget. */
class BoundedReadLimitException(val limit: Int) : IllegalStateException("Object exceeds the $limit-byte read limit")
