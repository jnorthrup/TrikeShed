package borg.trikeshed.userspace

/**
 * Result of a single io_uring submission-queue-entry (SQE) completion.
 *
 * Single canonical type for userspace. SPI providers return this shape directly.
 */
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ebpf.UringEbpfContext
import borg.trikeshed.userspace.nio.ebpf.UringEbpfPhase
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram

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

/**
 * Single entry point. Platform binding is in [LiburingImpl] (expect/actual).
 *
 * Every submission crosses an eBPF SUBMIT program and every completion a COMPLETE program, and
 * this object is the only route to [LiburingImpl] -- so the gate is exclusive by construction
 * rather than by discipline. There is no second path to filter, count or veto a submission, which
 * is the point: an observer bolted beside the ring drifts from it, an observer the ring cannot be
 * used without does not.
 *
 * A SUBMIT program returning 0 vetoes the submission; the prep fails with EPERM (-1) and never
 * reaches the ring. Any other value admits it. A COMPLETE program's return value replaces the
 * completion's `res`, so a program can rewrite an outcome as well as watch it.
 *
 * With no programs attached the gate is two null checks on an array reference, which is what
 * "profiles to nothing" has to mean: no allocation, no boxing, no iterator, and no generic
 * container in the submission path.
 */
object Liburing : LiburingFacade by LiburingImpl {

    @Volatile private var submitPrograms: Array<UringEbpfProgram>? = null
    @Volatile private var completePrograms: Array<UringEbpfProgram>? = null

    /** Attach a program to its declared phase. Attachment is rare; submission is not. */
    @Synchronized
    fun attach(program: UringEbpfProgram) {
        when (program.phase) {
            UringEbpfPhase.SUBMIT -> submitPrograms = (submitPrograms ?: emptyArray()) + program
            UringEbpfPhase.COMPLETE -> completePrograms = (completePrograms ?: emptyArray()) + program
        }
    }

    /** Detach everything. */
    @Synchronized
    fun detachAll() { submitPrograms = null; completePrograms = null }

    /** -1 is EPERM: the veto is an error the caller sees, not a silently dropped submission. */
    private fun admit(submission: UringSubmission): Boolean {
        val programs = submitPrograms ?: return true
        var index = 0
        while (index < programs.size) {
            if (programs[index].run(UringEbpfContext(UringEbpfPhase.SUBMIT, submission, null), 1L) == 0L) return false
            index++
        }
        return true
    }

    private fun vetoed(): Result<Unit> = Result.failure(UringVetoed)

    private fun observe(completion: UringCompletion?, submission: UringSubmission): UringCompletion? {
        val programs = completePrograms ?: return completion
        if (completion == null) return null
        var res = completion.res.toLong()
        var index = 0
        while (index < programs.size) {
            res = programs[index].run(UringEbpfContext(UringEbpfPhase.COMPLETE, submission, completion), res)
            index++
        }
        return if (res == completion.res.toLong()) completion else completion.copy(res = res.toInt())
    }

    /** The submission a completion is answering is not tracked here; COMPLETE sees a NOP shape. */
    private val completionShape = UringSubmission(UringOp.NOP, -1, 0L, 0, 0L)

    override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.READ, fd, bufAddress, len, offset, 0, userData))) LiburingImpl.prepRead(fd, bufAddress, len, offset, userData) else vetoed()

    override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.WRITE, fd, bufAddress, len, offset, 0, userData))) LiburingImpl.prepWrite(fd, bufAddress, len, offset, userData) else vetoed()

    override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.ACCEPT, fd, 0L, 0, 0L, 0, userData))) LiburingImpl.prepAccept(fd, userData) else vetoed()

    override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.CONNECT, fd, addrPtr, addrLen, 0L, 0, userData))) LiburingImpl.prepConnect(fd, addrPtr, addrLen, userData) else vetoed()

    override fun prepClose(fd: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.CLOSE, fd, 0L, 0, 0L, 0, userData))) LiburingImpl.prepClose(fd, userData) else vetoed()

    override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> =
        if (admit(UringSubmission(UringOp.FSYNC, fd, 0L, 0, 0L, if (datasync) 1 else 0, userData))) LiburingImpl.prepFsync(fd, userData, datasync) else vetoed()

    override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.FTRUNCATE, fd, 0L, 0, size, 0, userData))) LiburingImpl.prepFtruncate(fd, size, userData) else vetoed()

    override fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.MAP, fd, addr, len, offset, flags, userData))) LiburingImpl.prepMmap(fd, addr, len, prot, flags, offset, userData) else vetoed()

    override fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.MUNMAP, -1, addr, len, 0L, 0, userData))) LiburingImpl.prepMunmap(addr, len, userData) else vetoed()

    override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.SENDMSG, fd, msgHdrPtr, 0, 0L, flags, userData))) LiburingImpl.prepSendmsg(fd, msgHdrPtr, flags, userData) else vetoed()

    override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.RECVMSG, fd, msgHdrPtr, 0, 0L, flags, userData))) LiburingImpl.prepRecvmsg(fd, msgHdrPtr, flags, userData) else vetoed()

    override fun waitCqe(): Result<UringCompletion?> = LiburingImpl.waitCqe().map { observe(it, completionShape) }

    override fun peekCqe(): Result<UringCompletion?> = LiburingImpl.peekCqe().map { observe(it, completionShape) }
}

/** A SUBMIT program refused this submission. EPERM, surfaced rather than swallowed. */
object UringVetoed : RuntimeException("submission vetoed by an attached eBPF SUBMIT program") {
    private fun readResolve(): Any = UringVetoed
}

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

internal fun <T> unsupported(): Result<T> =
    Result.failure(UnsupportedOperationException("liburing facade is only available on linux"))

internal fun unsupportedUnit(): Unit {
    throw UnsupportedOperationException("liburing facade is only available on linux")
}
