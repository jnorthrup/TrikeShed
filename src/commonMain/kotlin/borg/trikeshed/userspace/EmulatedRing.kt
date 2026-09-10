package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * A [LiburingFacade] served by a [UserspaceChannelBackend].
 *
 * Every platform had the same hole: the facade answered "liburing unavailable" unless a native
 * binding existed, while a backend implementing the same operations sat beside it. Two entry
 * points, one wired. A facade that fails anywhere pushes callers back to the platform's own IO
 * library, and that is the fraying the single vocabulary exists to prevent.
 *
 * This is the fallback for all of them, written once in commonMain rather than five times. It is
 * SQ/CQ shaped because the facade is: [stage] queues a submission, [submit] runs the batch through
 * the backend and turns each result into a completion, [waitCqe] and [peekCqe] reap.
 *
 * Whether the backend underneath is emulation or a real ring is not this class's business.
 * `openUserspaceChannelBackend` already probes -- `discoverJvmUringBackend` on the JVM,
 * `discoverNodeUringBackend` on Node -- so a host with io_uring gets it through here without this
 * class knowing, and a host without it gets the emulation at the same call.
 *
 * **A ring belongs to one thread.** There is no locking here, which is io_uring's own model: a
 * ring is owned by its creator and sharing one requires the caller's own synchronisation.
 */
internal class EmulatedRing(private val backend: UserspaceChannelBackend) : LiburingFacade {

    private val sq = ArrayDeque<UringSubmission>()
    private val cq = ArrayDeque<UringCompletion>()

    /** The separator between renameat's two paths, by code point so the source stays ASCII. */
    private val nul: String = 0.toChar().toString()

    private fun stage(submission: UringSubmission): Result<Unit> {
        sq.addLast(submission)
        return Result.success(Unit)
    }

    /**
     * A path rides in the submission buffer. renameat carries both halves NUL-separated: an SQE
     * has one address field, and a second channel for the second path would be a shape only this
     * backend understands.
     */
    private fun paths(vararg parts: String): ByteBuffer =
        ByteBuffer(parts.joinToString(nul).encodeToByteArray())

    override fun open(entries: Int, flags: Int): Result<Unit> = Result.success(Unit)

    override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.READ, fd, bufAddress, len, offset, 0, userData))

    override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.WRITE, fd, bufAddress, len, offset, 0, userData))

    override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.ACCEPT, fd, 0L, 0, 0L, 0, userData))

    override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.CONNECT, fd, addrPtr, addrLen, 0L, 0, userData))

    override fun prepClose(fd: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.CLOSE, fd, 0L, 0, 0L, 0, userData))

    override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> =
        stage(UringSubmission(UringOp.FSYNC, fd, 0L, 0, 0L, if (datasync) 1 else 0, userData))

    override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.FTRUNCATE, fd, 0L, 0, size, 0, userData))

    override fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.MAP, fd, addr, len, offset, prot, userData))

    override fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.MUNMAP, addr.toInt(), 0L, len, 0L, 0, userData))

    override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.SENDMSG, fd, msgHdrPtr, 0, 0L, flags, userData))

    override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.RECVMSG, fd, msgHdrPtr, 0, 0L, flags, userData))

    override fun prepOpenat(dfd: Int, path: String, flags: Int, mode: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.OPENAT, -100, 0L, path.length, flags.toLong(), mode, userData, paths(path)))

    override fun prepStatx(dfd: Int, path: String, flags: Int, mask: Int, bufAddress: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.STATX, dfd, bufAddress, 256, 0L, flags, userData, paths(path)))

    override fun prepFallocate(fd: Int, mode: Int, offset: Long, len: Long, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.FALLOCATE, fd, 0L, len.toInt(), offset, mode, userData))

    override fun prepFadvise(fd: Int, offset: Long, len: Int, advice: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.FADVISE, fd, 0L, len, offset, advice, userData))

    override fun prepMadvise(addr: Long, length: Int, advice: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.MADVISE, addr.toInt(), 0L, length, 0L, advice, userData))

    override fun prepMsync(addr: Long, length: Int, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.MSYNC, addr.toInt(), 0L, length, 0L, flags, userData))

    override fun prepRenameat(oldDfd: Int, oldPath: String, newDfd: Int, newPath: String, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.RENAMEAT, oldDfd, 0L, oldPath.length, newDfd.toLong(), flags, userData, paths(oldPath, newPath)))

    override fun prepUnlinkat(dfd: Int, path: String, flags: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.UNLINKAT, dfd, 0L, path.length, 0L, flags, userData, paths(path)))

    override fun prepMkdirat(dfd: Int, path: String, mode: Int, userData: Long): Result<Unit> =
        stage(UringSubmission(UringOp.MKDIRAT, dfd, 0L, path.length, 0L, mode, userData, paths(path)))

    override fun submit(): Result<Int> {
        if (sq.isEmpty()) return Result.success(0)
        val batch = sq.toList()
        sq.clear()
        return runCatching {
            for (result in backend.submitBatch(batch)) cq.addLast(UringCompletion(result.userData, result.res, 0))
            batch.size
        }
    }

    /**
     * Not blocking: the work completes inside [submit], so a wait that found the queue empty would
     * sleep forever rather than briefly. Answering null beats hanging on a semantic this cannot
     * honour.
     */
    override fun waitCqe(): Result<UringCompletion?> = Result.success(cq.removeFirstOrNull())

    override fun peekCqe(): Result<UringCompletion?> = Result.success(cq.firstOrNull())

    override fun cqAdvance(count: Int) {
        var remaining = count
        while (remaining-- > 0 && cq.isNotEmpty()) cq.removeFirst()
    }

    /** Fanout is the native ring's mechanism; there is nothing asynchronous here to fan out from. */
    override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {}
    override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {}

    override fun drain(): Result<Unit> = submit().map { }

    override fun close(): Result<Unit> = runCatching {
        backend.close(); sq.clear(); cq.clear()
    }
}
