package borg.trikeshed.userspace

/**
 * Result of a single io_uring submission-queue-entry (SQE) completion.
 *
 * Single canonical type for userspace. SPI providers return this shape directly.
 */
import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.ebpf.UringEbpfContext
import borg.trikeshed.userspace.nio.ebpf.UringEbpfPhase
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import kotlin.concurrent.Volatile

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
    fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit>
    fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit>

    /** io_uring_prep_openat(sqe, dfd, path, flags, mode). The new fd arrives on the completion. */
    fun prepOpenat(dfd: Int, path: String, flags: Int, mode: Int, userData: Long): Result<Unit> = unsupported()

    /** io_uring_prep_statx(sqe, dfd, path, flags, mask, statxbuf). */
    fun prepStatx(dfd: Int, path: String, flags: Int, mask: Int, bufAddress: Long, userData: Long): Result<Unit> = unsupported()

    /** io_uring_prep_fallocate(sqe, fd, mode, offset, len) -- mode first, as liburing has it. */
    fun prepFallocate(fd: Int, mode: Int, offset: Long, len: Long, userData: Long): Result<Unit> = unsupported()

    /** io_uring_prep_fadvise(sqe, fd, offset, len, advice). POSIX_FADV_WILLNEED is 3. */
    fun prepFadvise(fd: Int, offset: Long, len: Int, advice: Int, userData: Long): Result<Unit> = unsupported()

    /** io_uring_prep_madvise(sqe, addr, length, advice) -- by address, not fd. MADV_WILLNEED is 3. */
    fun prepMadvise(addr: Long, length: Int, advice: Int, userData: Long): Result<Unit> = unsupported()

    /** io_uring_prep_renameat(sqe, olddfd, oldpath, newdfd, newpath, flags). */
    fun prepRenameat(oldDfd: Int, oldPath: String, newDfd: Int, newPath: String, flags: Int, userData: Long): Result<Unit> = unsupported()

    /** io_uring_prep_unlinkat(sqe, dfd, path, flags). */
    fun prepUnlinkat(dfd: Int, path: String, flags: Int, userData: Long): Result<Unit> = unsupported()

    /** io_uring_prep_mkdirat(sqe, dfd, path, mode). */
    fun prepMkdirat(dfd: Int, path: String, mode: Int, userData: Long): Result<Unit> = unsupported()

    /** Kernel registration of native memory, or retained common emulation when unsupported. */
    fun registerBuffers(buffers: Series<MemoryMapping>): Result<Unit> = unsupported()

    /** Compatibility registration for emulated heap buffers. Does not pin memory or replace a set. */
    fun registerBuffers(buffers: List<ByteBuffer>): Result<Int> = unsupported()

    /** io_uring_unregister_buffers. */
    fun unregisterBuffers(): Result<Unit> = unsupported()

    /** io_uring_register_files: a fixed fd table, so a submission names an index instead of an fd. */
    fun registerFiles(fds: IntArray): Result<Int> = unsupported()

    /** io_uring_unregister_files. */
    fun unregisterFiles(): Result<Unit> = unsupported()

    /** io_uring_prep_read_fixed(sqe, fd, buf, nbytes, offset, buf_index). */
    fun prepReadFixed(fd: Int, bufIndex: Int, len: Int, offset: Long, userData: Long): Result<Unit> = unsupported()

    /** io_uring_prep_write_fixed(sqe, fd, buf, nbytes, offset, buf_index). */
    fun prepWriteFixed(fd: Int, bufIndex: Int, len: Int, offset: Long, userData: Long): Result<Unit> = unsupported()
    fun submit(): Result<Int>

    /**
     * Transfer one CQE and dispatch it to handlers registered for its userData.
     * The native ring advances exactly once when the completion is returned.
     */
    fun waitCqe(): Result<UringCompletion?>
    /** Nonblocking transfer of one CQE; null means no ready completion. */
    fun peekCqe(): Result<UringCompletion?>
    /** Compatibility acknowledgement; transferred CQEs have already advanced. */
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
 * reaches the ring. Any other value admits it. COMPLETE programs observe the actual outcome;
 * their return values cannot rewrite the executed byte count or errno.
 *
 * With no programs attached the gate is two null checks on an array reference, which is what
 * "profiles to nothing" has to mean: no allocation, no boxing, no iterator, and no generic
 * container in the submission path.
 */
object Liburing : LiburingFacade by LiburingImpl {

    @Volatile private var submitPrograms: Array<UringEbpfProgram>? = null
    @Volatile private var completePrograms: Array<UringEbpfProgram>? = null

    /**
     * Attach a program to its declared phase.
     *
     * Attach at setup, before submissions flow: there is no lock here because commonMain has no
     * portable one, and a ring belongs to one thread anyway. Reads on the submission path are
     * volatile, so a program attached before the first prep is seen by it.
     */
    fun attach(program: UringEbpfProgram) {
        when (program.phase) {
            UringEbpfPhase.SUBMIT -> submitPrograms = (submitPrograms ?: emptyArray()) + program
            UringEbpfPhase.COMPLETE -> completePrograms = (completePrograms ?: emptyArray()) + program
        }
    }

    /** Detach everything. Same rule as [attach]: not while submissions are in flight. */
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

    private fun observe(completion: UringCompletion?): UringCompletion? {
        val programs = completePrograms ?: return completion
        if (completion == null) return null
        val submission = answering(completion)
        var index = 0
        while (index < programs.size) {
            runCatching { programs[index].run(UringEbpfContext(UringEbpfPhase.COMPLETE, submission, completion), completion.res.toLong()) }
            index++
        }
        return completion
    }

    /** Legacy completion hooks receive a correlation-only context, not the original SQE. */
    private fun answering(completion: UringCompletion): UringSubmission =
        UringSubmission(UringOp.NOP, -1, 0L, 0, 0L, userData = completion.userData)

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
        if (admit(UringSubmission(UringOp.FSYNC, fd, 0L, 0, 0L, userData = userData, operationFlags = if (datasync) 1 else 0))) LiburingImpl.prepFsync(fd, userData, datasync) else vetoed()

    override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.FTRUNCATE, fd, 0L, 0, size, 0, userData))) LiburingImpl.prepFtruncate(fd, size, userData) else vetoed()

    override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.SENDMSG, fd, msgHdrPtr, 0, 0L, userData = userData, operationFlags = flags))) LiburingImpl.prepSendmsg(fd, msgHdrPtr, flags, userData) else vetoed()

    override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.RECVMSG, fd, msgHdrPtr, 0, 0L, userData = userData, operationFlags = flags))) LiburingImpl.prepRecvmsg(fd, msgHdrPtr, flags, userData) else vetoed()

    override fun prepOpenat(dfd: Int, path: String, flags: Int, mode: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.OPENAT, dfd, 0L, path.length, flags.toLong(), userData = userData, operationFlags = mode))) LiburingImpl.prepOpenat(dfd, path, flags, mode, userData) else vetoed()

    override fun prepStatx(dfd: Int, path: String, flags: Int, mask: Int, bufAddress: Long, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.STATX, dfd, bufAddress, path.length, mask.toLong(), userData = userData, operationFlags = flags))) LiburingImpl.prepStatx(dfd, path, flags, mask, bufAddress, userData) else vetoed()

    override fun prepFallocate(fd: Int, mode: Int, offset: Long, len: Long, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.FALLOCATE, fd, 0L, len.toInt(), offset, userData = userData, operationFlags = mode))) LiburingImpl.prepFallocate(fd, mode, offset, len, userData) else vetoed()

    override fun prepFadvise(fd: Int, offset: Long, len: Int, advice: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.FADVISE, fd, 0L, len, offset, userData = userData, operationFlags = advice))) LiburingImpl.prepFadvise(fd, offset, len, advice, userData) else vetoed()

    override fun prepMadvise(addr: Long, length: Int, advice: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.MADVISE, -1, addr, length, 0L, userData = userData, operationFlags = advice))) LiburingImpl.prepMadvise(addr, length, advice, userData) else vetoed()

    override fun prepRenameat(oldDfd: Int, oldPath: String, newDfd: Int, newPath: String, flags: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.RENAMEAT, oldDfd, 0L, oldPath.length, newDfd.toLong(), userData = userData, operationFlags = flags))) LiburingImpl.prepRenameat(oldDfd, oldPath, newDfd, newPath, flags, userData) else vetoed()

    override fun prepUnlinkat(dfd: Int, path: String, flags: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.UNLINKAT, dfd, 0L, path.length, 0L, userData = userData, operationFlags = flags))) LiburingImpl.prepUnlinkat(dfd, path, flags, userData) else vetoed()

    override fun prepMkdirat(dfd: Int, path: String, mode: Int, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.MKDIRAT, dfd, 0L, path.length, 0L, userData = userData, operationFlags = mode))) LiburingImpl.prepMkdirat(dfd, path, mode, userData) else vetoed()

    // Registration is control, not submission: nothing crosses the gate because nothing is being
    // submitted. Straight through.
    override fun registerBuffers(buffers: Series<MemoryMapping>): Result<Unit> = LiburingImpl.registerBuffers(buffers)
    override fun registerBuffers(buffers: List<ByteBuffer>): Result<Int> = LiburingImpl.registerBuffers(buffers)
    override fun unregisterBuffers(): Result<Unit> = LiburingImpl.unregisterBuffers()
    override fun registerFiles(fds: IntArray): Result<Int> = LiburingImpl.registerFiles(fds)
    override fun unregisterFiles(): Result<Unit> = LiburingImpl.unregisterFiles()

    override fun prepReadFixed(fd: Int, bufIndex: Int, len: Int, offset: Long, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.READ_FIXED, fd, 0L, len, offset, userData = userData, bufferIndex = bufIndex))) LiburingImpl.prepReadFixed(fd, bufIndex, len, offset, userData) else vetoed()

    override fun prepWriteFixed(fd: Int, bufIndex: Int, len: Int, offset: Long, userData: Long): Result<Unit> =
        if (admit(UringSubmission(UringOp.WRITE_FIXED, fd, 0L, len, offset, userData = userData, bufferIndex = bufIndex))) LiburingImpl.prepWriteFixed(fd, bufIndex, len, offset, userData) else vetoed()

    override fun waitCqe(): Result<UringCompletion?> = LiburingImpl.waitCqe().map { observe(it) }

    override fun peekCqe(): Result<UringCompletion?> = LiburingImpl.peekCqe().map { observe(it) }
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
    Result.failure(UnsupportedOperationException("operation is not implemented by this backend"))

internal fun unsupportedUnit(): Unit {
    throw UnsupportedOperationException("operation is not implemented by this backend")
}
