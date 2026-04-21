package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.userspace.nio.Completion
import borg.trikeshed.userspace.nio.Interest

/**
 * SPI for TrikeShed Userspace NIO backends.
 *
 * Implementations are discovered via [java.util.ServiceLoader] using the
 * META-INF/services descriptor or equivalent KSP-generated registration.
 *
 * Lifecycle: open() -> [read/write/fanout]* -> drain() -> close()
 *
 * Fanout semantics: a single fd/token pair may fan out completions to multiple
 * downstream consumers; the backend drives the completion loop and delivers to
 * all registered fanout handlers atomically per completion event.
 */
interface UserspaceNioSpi {

    /**
     * Open and initialise the backend with the given queue depth.
     * Must be called before any other operation.
     * @param queueDepth the number of in-flight SQEs the ring can hold
     */
    suspend fun open(queueDepth: Int = 256): Result<Unit>

    /**
     * Submit an async read of [buf] from [fd].
     * @param fd the raw file descriptor
     * @param buf destination buffer; ownership transfers to the backend until completion
     * @param userData caller-supplied correlation token returned on [pollCompletion]
     */
    suspend fun read(fd: Int, buf: ByteArray, userData: Long): Result<Unit>

    /**
     * Submit an async write of [buf] to [fd].
     * @param fd the raw file descriptor
     * @param buf source buffer; ownership transfers to the backend until completion
     * @param userData caller-supplied correlation token returned on [pollCompletion]
     */
    suspend fun write(fd: Int, buf: ByteArray, userData: Long): Result<Unit>

    /**
     * Register [fd] for interest-based event notification with the given [interest] mask.
     * Must be called before submitting reads/writes on [fd].
     * @param token caller-supplied correlation token
     */
    suspend fun register(fd: Int, token: Long, interest: Interest): Result<Unit>

    /**
     * Update the interest mask for a previously-registered [fd].
     */
    suspend fun reregister(fd: Int, token: Long, interest: Interest): Result<Unit>

    /**
     * Deregister [fd] from the backend and release internal tracking state.
     */
    suspend fun unregister(fd: Int): Result<Unit>

    /**
     * Flush pending SQEs to the kernel / platform backend.
     * @return number of SQEs submitted
     */
    suspend fun flush(): Result<Long>

    /**
     * Block until at least [minCompletions] CQEs are available.
     * @return number of completions now ready
     */
    suspend fun waitForCompletions(minCompletions: Int = 1): Result<Long>

    /**
     * Poll and dequeue one completion event without blocking.
     * Returns null if the CQ is empty.
     */
    suspend fun pollCompletion(): Result<Completion?>

    /**
     * Register a fanout handler to receive completions for [token].
     * Multiple handlers for the same token produce channelized fanout.
     * @param token correlation token previously passed to [read] or [write]
     * @param handler invoked for each matching [Completion]
     */
    fun addFanoutHandler(token: Long, handler: suspend (Completion) -> Unit)

    /**
     * Remove a previously-registered fanout handler for [token].
     */
    fun removeFanoutHandler(token: Long, handler: suspend (Completion) -> Unit)

    /**
     * Stop accepting new work; process all in-flight operations to completion,
     * then transition to closed state.
     */
    suspend fun drain(): Result<Unit>

    /**
     * Close the backend immediately, releasing all OS resources.
     * Any pending completions are discarded.
     */
    suspend fun close(): Result<Unit>
}
