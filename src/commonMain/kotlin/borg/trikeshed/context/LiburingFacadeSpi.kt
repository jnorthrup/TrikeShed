package borg.trikeshed.context

/**
 * Result of a single io_uring submission queue entry (SQE) completion.
 */
data class UringCompletion(
    /** Caller-supplied correlation token from the original SQE. */
    val userData: Long,
    /** Return value: >= 0 on success, negative errno on error. */
    val res: Int,
    /** CQE flags word from the kernel. */
    val flags: Int
)

/**
 * SPI for the liburing facade.
 *
 * Abstracts the JNI/JNA/CInterop binding to liburing so that higher-level
 * components (Reactor, NIO backend) remain platform-agnostic.
 *
 * Implementations are discovered via ServiceLoader / META-INF/services.
 *
 * Lifecycle: open() -> [prepRead/prepWrite/submit/waitCqe/peekCqe]* -> close()
 *
 * Fanout: a single userData token may be fanned out to multiple [UringCompletion]
 * consumers via [registerFanoutHandler]. The facade guarantees at-least-once
 * delivery to all registered handlers for a given userData.
 */
interface LiburingFacadeSpi {

    /**
     * Initialise the io_uring instance.
     * @param entries number of SQEs in the submission ring
     * @param flags io_uring_setup flags (e.g. IORING_SETUP_SQPOLL)
     */
    fun open(entries: Int = 2, flags: Int = 0): Result<Unit>

    /**
     * Prepare a read SQE: read [len] bytes from [fd] into [bufAddress] at offset [offset].
     * @param bufAddress native memory address of the destination buffer
     * @param userData caller correlation token
     */
    fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit>

    /**
     * Prepare a write SQE: write [len] bytes from [bufAddress] to [fd] at offset [offset].
     * @param bufAddress native memory address of the source buffer
     * @param userData caller correlation token
     */
    fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit>

    /**
     * Prepare an accept SQE on a listening [fd].
     * @param userData caller correlation token
     */
    fun prepAccept(fd: Int, userData: Long): Result<Unit>

    /**
     * Prepare a connect SQE on [fd].
     * @param addrPtr native pointer to a sockaddr struct
     * @param addrLen size of the sockaddr struct
     * @param userData caller correlation token
     */
    fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit>

    /**
     * Prepare a close SQE for [fd].
     * @param userData caller correlation token
     */
    fun prepClose(fd: Int, userData: Long): Result<Unit>

    /**
     * Submit all pending SQEs to the kernel.
     * @return number of SQEs submitted
     */
    fun submit(): Result<Int>

    /**
     * Wait until at least one CQE is available, then return without dequeuing.
     */
    fun waitCqe(): Result<UringCompletion>

    /**
     * Peek at the next CQE without blocking.
     * Returns null if the CQ is empty.
     */
    fun peekCqe(): Result<UringCompletion?>

    /**
     * Advance the CQ head by [count] slots, marking completions as seen.
     */
    fun cqAdvance(count: Int)

    /**
     * Register a fanout handler invoked for every [UringCompletion] whose
     * userData matches [token]. Multiple handlers produce channelized fanout.
     */
    fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit)

    /**
     * Remove a previously-registered fanout handler for [token].
     */
    fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit)

    /**
     * Drain: no new SQEs are accepted; all in-flight SQEs run to completion.
     */
    fun drain(): Result<Unit>

    /**
     * Close the io_uring instance and release the ring file descriptor.
     */
    fun close(): Result<Unit>
}
