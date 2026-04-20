package borg.trikeshed.common

import borg.trikeshed.ccek.KeyedService
import kotlin.coroutines.CoroutineContext

/**
 * Platform-specific I/O primitive for seek-based reading.
 * Each platform provides an actual implementation wrapping
 * FileChannel (JVM), POSIX read/pread, or io_uring (Linux opt-in).
 */
interface SeekHandle {
    /** Open the file for reading. Returns handle ID or throws. */
    fun open(filename: String, readOnly: Boolean = true): Long

    /** Close the handle. */
    fun close(handle: Long)

    /** Read bytes at specific offset. Returns bytes read or -1 for EOF. */
    fun pread(handle: Long, buf: ByteArray, offset: Int, length: Int, fileOffset: Long): Int

    /** File size in bytes, or -1 if unknown. */
    fun size(handle: Long): Long

    /** Sequential read from current position (for windowed buffer fills). Returns bytes read or -1. */
    fun read(handle: Long, buf: ByteArray, offset: Int, length: Int): Int

    /** Seek to position for sequential access. Returns new position or -1. */
    fun seek(handle: Long, position: Long): Long
}

/**
 * Type-safe I/O platform enum for runtime dispatch and testing.
 */
enum class IoPlatform {
    JVM_FILE_CHANNEL,        // FileChannel on JVM - portable across JDKs
    POSIX_PREAD,             // pread for thread-safe random access
    LINUX_IO_URING,          // io_uring with optional batching
    LINUX_IO_URING_POLL,     // io_uring with busy-polling (bypasses kernel)
    ;

    companion object {
        fun default(): IoPlatform = when {
            System.getProperty("os.name")?.contains("Linux") == true -> POSIX_PREAD
            else -> JVM_FILE_CHANNEL
        }
    }
}

/**
 * Get a SeekHandle for the current platform.
 * Callers can override via environment or system property.
 */
expect fun platformSeekHandle(): SeekHandle

/**
 * Optional: Get io_uring handle if available, else fallback.
 */
expect fun ioUringHandle(): SeekHandle?

/** CCEK keyed service exposing an open SeekHandle to the coroutine context. */
data class SeekHandleService(val handle: SeekHandle) : KeyedService {
    companion object Key : CoroutineContext.Key<SeekHandleService>
    override val key: CoroutineContext.Key<*> get() = Key
}
