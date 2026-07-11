package borg.trikeshed.userspace

/**
 * Result of a single io_uring submission-queue-entry (SQE) completion.
 *
 * Single canonical type for userspace. SPI providers return this shape directly.
 */
data class UringCompletion(
    val userData: Long,
    val res: Int,
    val flags: Int,
) : FanoutEvent {
    override val eventType: Int = 0
}

/**
 * Canonical userspace liburing facade. Platform bindings hide behind [LiburingImpl].
 * `Spi` here is the ServiceLoader SPI marker — implementations are actuals.
 */
interface LiburingFacade {
    fun open(entries: Int = 2, flags: Int = 0): Result<Unit>
    fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit>
    fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit>
    fun prepAccept(fd: Int, userData: Long): Result<Unit>
    fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit>
    fun prepClose(fd: Int, userData: Long): Result<Unit>
    fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit>
    fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit>
    fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit>
    fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit>
    fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit>
    fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit>
    fun submit(): Result<Int>

    /**
     * Drain the CQ, dispatching each [UringCompletion] to handlers registered
     * via [registerFanoutHandler] for the matching userData token.
     * Returns the next peek-safe completion (or null) without dequeuing the ring.
     */
    fun waitCqe(): Result<UringCompletion?>
    fun peekCqe(): Result<UringCompletion?>
    fun cqAdvance(count: Int)
    fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit)
    fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit)
    fun drain(): Result<Unit>
    fun close(): Result<Unit>
}

/** Single entry point. Platform binding is in [LiburingImpl] (expect/actual). */
object Liburing : LiburingFacade by LiburingImpl

internal expect object LiburingImpl : LiburingFacade {
    override fun open(entries: Int, flags: Int): Result<Unit>
    override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit>
    override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit>
    override fun prepAccept(fd: Int, userData: Long): Result<Unit>
    override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit>
    override fun prepClose(fd: Int, userData: Long): Result<Unit>
    override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit>
    override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit>
    override fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit>
    override fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit>
    override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit>
    override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit>
    override fun submit(): Result<Int>
    override fun waitCqe(): Result<UringCompletion?>
    override fun peekCqe(): Result<UringCompletion?>
    override fun cqAdvance(count: Int)
    override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit)
    override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit)
    override fun drain(): Result<Unit>
    override fun close(): Result<Unit>
}
