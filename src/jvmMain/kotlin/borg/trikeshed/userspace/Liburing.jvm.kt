package borg.trikeshed.userspace

import borg.trikeshed.context.loadUserspaceNioSpi
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * The JVM binding.
 *
 * A real liburing is used when the SPI offers one. When it does not, the facade is served by the
 * emulated backend instead of failing: the point of the waist is that commonMain writes one
 * submission vocabulary and gets an answer everywhere, subperformant where it must be. A facade
 * that answers "liburing unavailable" off Linux pushes every caller back to java.nio, which is the
 * creep these preps exist to end.
 *
 * The emulation is SQ/CQ shaped because the facade is: prep* stages a submission, submit() runs
 * the staged batch through the backend and turns each result into a completion, waitCqe/peekCqe
 * reap. Staging is synchronised because a ring is shared; the per-op work underneath is not.
 */
internal actual object LiburingImpl : LiburingFacade {
    private val delegate: LiburingFacade? by lazy {
        runCatching { loadUserspaceNioSpi().liburing }.getOrNull()
    }

    private var backend: UserspaceChannelBackend? = null
    private val sq = ArrayDeque<UringSubmission>()
    private val cq = ArrayDeque<UringCompletion>()

    /** The separator between renameat's two paths, by code point so the source stays ASCII. */
    private val NUL: String = 0.toChar().toString()

    /** Stage one SQE. The completion appears after [submit]. */
    private fun stage(submission: UringSubmission): Result<Unit> {
        if (backend == null) return Result.failure(IllegalStateException("ring is not open"))
        synchronized(sq) { sq.addLast(submission) }
        return Result.success(Unit)
    }

    /**
     * A path rides in the submission buffer. renameat carries both halves NUL-separated: an SQE
     * has one address field, and inventing a second channel for the second path would be a shape
     * only this backend understands.
     */
    private fun pathBuffer(vararg parts: String): ByteBuffer =
        ByteBuffer(parts.joinToString(NUL).encodeToByteArray())

    actual override fun open(entries: Int, flags: Int): Result<Unit> =
        delegate?.open(entries, flags) ?: runCatching {
            require(entries > 0)
            backend = openUserspaceChannelBackend(entries)
        }

    actual override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        delegate?.prepRead(fd, bufAddress, len, offset, userData)
            ?: stage(UringSubmission(UringOp.READ, fd, bufAddress, len, offset, 0, userData))

    actual override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        delegate?.prepWrite(fd, bufAddress, len, offset, userData)
            ?: stage(UringSubmission(UringOp.WRITE, fd, bufAddress, len, offset, 0, userData))

    actual override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        delegate?.prepAccept(fd, userData)
            ?: stage(UringSubmission(UringOp.ACCEPT, fd, 0L, 0, 0L, 0, userData))

    actual override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        delegate?.prepConnect(fd, addrPtr, addrLen, userData)
            ?: stage(UringSubmission(UringOp.CONNECT, fd, addrPtr, addrLen, 0L, 0, userData))

    actual override fun prepClose(fd: Int, userData: Long): Result<Unit> =
        delegate?.prepClose(fd, userData)
            ?: stage(UringSubmission(UringOp.CLOSE, fd, 0L, 0, 0L, 0, userData))

    actual override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> =
        delegate?.prepFsync(fd, userData, datasync)
            ?: stage(UringSubmission(UringOp.FSYNC, fd, 0L, 0, 0L, if (datasync) 1 else 0, userData))

    actual override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        delegate?.prepFtruncate(fd, size, userData)
            ?: stage(UringSubmission(UringOp.FTRUNCATE, fd, 0L, 0, size, 0, userData))

    actual override fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit> =
        delegate?.prepMmap(fd, addr, len, prot, flags, offset, userData)
            ?: stage(UringSubmission(UringOp.MAP, fd, addr, len, offset, prot, userData))

    actual override fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit> =
        delegate?.prepMunmap(addr, len, userData)
            ?: stage(UringSubmission(UringOp.MUNMAP, addr.toInt(), 0L, len, 0L, 0, userData))

    actual override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        delegate?.prepSendmsg(fd, msgHdrPtr, flags, userData)
            ?: stage(UringSubmission(UringOp.SENDMSG, fd, msgHdrPtr, 0, 0L, flags, userData))

    actual override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        delegate?.prepRecvmsg(fd, msgHdrPtr, flags, userData)
            ?: stage(UringSubmission(UringOp.RECVMSG, fd, msgHdrPtr, 0, 0L, flags, userData))

    // the syscalls, in liburing's own argument order

    override fun prepOpenat(dfd: Int, path: String, flags: Int, mode: Int, userData: Long): Result<Unit> =
        delegate?.prepOpenat(dfd, path, flags, mode, userData)
            ?: stage(UringSubmission(UringOp.OPENAT, -100, 0L, path.length, flags.toLong(), mode, userData, pathBuffer(path)))

    override fun prepStatx(dfd: Int, path: String, flags: Int, mask: Int, bufAddress: Long, userData: Long): Result<Unit> =
        delegate?.prepStatx(dfd, path, flags, mask, bufAddress, userData)
            ?: stage(UringSubmission(UringOp.STATX, dfd, bufAddress, 256, 0L, flags, userData, pathBuffer(path)))

    override fun prepFallocate(fd: Int, mode: Int, offset: Long, len: Long, userData: Long): Result<Unit> =
        delegate?.prepFallocate(fd, mode, offset, len, userData)
            ?: stage(UringSubmission(UringOp.FALLOCATE, fd, 0L, len.toInt(), offset, mode, userData))

    override fun prepFadvise(fd: Int, offset: Long, len: Int, advice: Int, userData: Long): Result<Unit> =
        delegate?.prepFadvise(fd, offset, len, advice, userData)
            ?: stage(UringSubmission(UringOp.FADVISE, fd, 0L, len, offset, advice, userData))

    override fun prepMadvise(addr: Long, length: Int, advice: Int, userData: Long): Result<Unit> =
        delegate?.prepMadvise(addr, length, advice, userData)
            ?: stage(UringSubmission(UringOp.MADVISE, addr.toInt(), 0L, length, 0L, advice, userData))

    override fun prepMsync(addr: Long, length: Int, flags: Int, userData: Long): Result<Unit> =
        delegate?.prepMsync(addr, length, flags, userData)
            ?: stage(UringSubmission(UringOp.MSYNC, addr.toInt(), 0L, length, 0L, flags, userData))

    override fun prepRenameat(oldDfd: Int, oldPath: String, newDfd: Int, newPath: String, flags: Int, userData: Long): Result<Unit> =
        delegate?.prepRenameat(oldDfd, oldPath, newDfd, newPath, flags, userData)
            ?: stage(UringSubmission(UringOp.RENAMEAT, oldDfd, 0L, oldPath.length, newDfd.toLong(), flags, userData, pathBuffer(oldPath, newPath)))

    override fun prepUnlinkat(dfd: Int, path: String, flags: Int, userData: Long): Result<Unit> =
        delegate?.prepUnlinkat(dfd, path, flags, userData)
            ?: stage(UringSubmission(UringOp.UNLINKAT, dfd, 0L, path.length, 0L, flags, userData, pathBuffer(path)))

    override fun prepMkdirat(dfd: Int, path: String, mode: Int, userData: Long): Result<Unit> =
        delegate?.prepMkdirat(dfd, path, mode, userData)
            ?: stage(UringSubmission(UringOp.MKDIRAT, dfd, 0L, path.length, 0L, mode, userData, pathBuffer(path)))

    // ring

    actual override fun submit(): Result<Int> {
        delegate?.let { return it.submit() }
        val ring = backend ?: return Result.failure(IllegalStateException("ring is not open"))
        val batch = synchronized(sq) { if (sq.isEmpty()) return Result.success(0) else sq.toList().also { sq.clear() } }
        return runCatching {
            val results = ring.submitBatch(batch)
            synchronized(cq) { for (r in results) cq.addLast(UringCompletion(r.userData, r.res, 0)) }
            batch.size
        }
    }

    /**
     * The emulation completes inside [submit], so there is nothing to block on here: a wait that
     * found an empty queue would sleep forever rather than briefly.
     */
    actual override fun waitCqe(): Result<UringCompletion?> =
        delegate?.waitCqe() ?: Result.success(synchronized(cq) { cq.removeFirstOrNull() })

    actual override fun peekCqe(): Result<UringCompletion?> =
        delegate?.peekCqe() ?: Result.success(synchronized(cq) { cq.firstOrNull() })

    actual override fun cqAdvance(count: Int) {
        val bound = delegate
        if (bound != null) bound.cqAdvance(count)
        else synchronized(cq) { repeat(minOf(count, cq.size)) { cq.removeFirst() } }
    }

    actual override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        delegate?.registerFanoutHandler(token, handler)
    }

    actual override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        delegate?.removeFanoutHandler(token, handler)
    }

    actual override fun drain(): Result<Unit> =
        delegate?.drain() ?: submit().map { }

    actual override fun close(): Result<Unit> =
        delegate?.close() ?: runCatching {
            backend?.close(); backend = null
            synchronized(sq) { sq.clear() }; synchronized(cq) { cq.clear() }
        }
}
