package borg.trikeshed.userspace

/**
 * Result of a single io_uring submission queue entry completion.
 */
data class UringCompletion(
    val userData: Long,
    val res: Int,
    val flags: Int,
)

/**
 * Canonical userspace liburing facade.
 * Platform bindings are hidden behind LiburingImpl actuals.
 */
interface LiburingFacade {
    fun open(entries: Int = 256, flags: Int = 0): Result<Unit>
    fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit>
    fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit>
    fun prepAccept(fd: Int, userData: Long): Result<Unit>
    fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit>
    fun prepClose(fd: Int, userData: Long): Result<Unit>
    fun submit(): Result<Int>
    fun waitCqe(): Result<UringCompletion>
    fun peekCqe(): Result<UringCompletion?>
    fun cqAdvance(count: Int)
    fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit)
    fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit)
    fun drain(): Result<Unit>
    fun close(): Result<Unit>
}

object Liburing : LiburingFacade by LiburingImpl

internal expect object LiburingImpl : LiburingFacade {
    override fun open(entries: Int, flags: Int): Result<Unit>
    override fun prepRead(
        fd: Int,
        bufAddress: Long,
        len: Int,
        offset: Long,
        userData: Long
    ): Result<Unit>

    override fun prepWrite(
        fd: Int,
        bufAddress: Long,
        len: Int,
        offset: Long,
        userData: Long
    ): Result<Unit>

    override fun prepAccept(fd: Int, userData: Long): Result<Unit>
    override fun prepConnect(
        fd: Int,
        addrPtr: Long,
        addrLen: Int,
        userData: Long
    ): Result<Unit>

    override fun prepClose(fd: Int, userData: Long): Result<Unit>
    override fun submit(): Result<Int>
    override fun waitCqe(): Result<UringCompletion>
    override fun peekCqe(): Result<UringCompletion?>
    override fun cqAdvance(count: Int)
    override fun registerFanoutHandler(
        token: Long,
        handler: (UringCompletion) -> Unit
    )

    override fun removeFanoutHandler(
        token: Long,
        handler: (UringCompletion) -> Unit
    )

    override fun drain(): Result<Unit>
    override fun close(): Result<Unit>
}
