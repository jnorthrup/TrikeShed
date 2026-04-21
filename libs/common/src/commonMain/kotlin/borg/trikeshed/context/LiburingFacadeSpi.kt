package borg.trikeshed.context

/**
 * SPI for the liburing submission/completion queue facade.
 * Implementations bridge Kotlin coroutines to io_uring SQE/CQE pairs.
 */
interface LiburingFacadeSpi {
    /** Submit a read into buf; returns bytes read or negative errno. */
    suspend fun submitRead(fd: Int, buf: ByteArray): Int
    /** Submit a write from buf; returns bytes written or negative errno. */
    suspend fun submitWrite(fd: Int, buf: ByteArray): Int
    /** Drain the completion queue; returns list of completion events. */
    suspend fun poll(): List<Any>
}
