package borg.trikeshed.platform.kernel.uring

import borg.trikeshed.platform.kernel.RawFd

/**
 * io_uring emulator using event loop for non-Linux platforms or fallback
 *
 * This provides a software emulation of io_uring operations.
 */

/**
 * Pending operation
 */
private data class PendingOp(
    val fd: RawFd,
    val op: UringOp,
    val userData: Long
)

/**
 * Completed operation
 */
private data class CompletedOp(
    val userData: Long,
    val result: Result<Int>
)

/**
 * Uring backend interface
 */
sealed interface UringBackend {
    fun submit(): Result<Int>
    fun wait(min: Int): Result<Int>
    fun queueRead(fd: RawFd, buf: ByteArray, userData: Long): Result<Unit>
    fun queueWrite(fd: RawFd, buf: ByteArray, userData: Long): Result<Unit>
    fun queueReadAt(fd: RawFd, offset: Long, buf: ByteArray, userData: Long): Result<Unit>
    fun queueWriteAt(fd: RawFd, offset: Long, buf: ByteArray, userData: Long): Result<Unit>
    fun queueNop(userData: Long): Result<Unit>
    fun queuePollAdd(fd: RawFd, pollMask: Int, userData: Long): Result<Unit>

    companion object {
        fun new(entries: Int): UringBackend {
            return UringEmulator(entries)
        }
    }
}

/**
 * io_uring emulator
 */
class UringEmulator(private val entries: Int) : UringBackend {
    private val pendingOps = mutableListOf<PendingOp>()
    private val completedOps = mutableListOf<CompletedOp>()

    override fun queueRead(fd: RawFd, buf: ByteArray, userData: Long): Result<Unit> {
        pendingOps.add(PendingOp(fd, UringOp.Read(buf.size), userData))
        return Result.success(Unit)
    }

    override fun queueWrite(fd: RawFd, buf: ByteArray, userData: Long): Result<Unit> {
        pendingOps.add(PendingOp(fd, UringOp.Write(buf.size), userData))
        return Result.success(Unit)
    }

    override fun queueReadAt(fd: RawFd, offset: Long, buf: ByteArray, userData: Long): Result<Unit> {
        pendingOps.add(PendingOp(fd, UringOp.ReadAt(offset, buf.size), userData))
        return Result.success(Unit)
    }

    override fun queueWriteAt(fd: RawFd, offset: Long, buf: ByteArray, userData: Long): Result<Unit> {
        pendingOps.add(PendingOp(fd, UringOp.WriteAt(offset, buf.size), userData))
        return Result.success(Unit)
    }

    override fun queueNop(userData: Long): Result<Unit> {
        pendingOps.add(PendingOp(-1L, UringOp.Nop, userData))
        return Result.success(Unit)
    }

    override fun queuePollAdd(fd: RawFd, pollMask: Int, userData: Long): Result<Unit> {
        pendingOps.add(PendingOp(fd, UringOp.PollAdd(pollMask), userData))
        return Result.success(Unit)
    }

    override fun submit(): Result<Int> {
        val count = pendingOps.size

        pendingOps.forEach { op ->
            executeOp(op)
        }
        pendingOps.clear()

        return Result.success(count)
    }

    private fun executeOp(op: PendingOp) {
        when (op.op) {
            is UringOp.Read -> {
                completedOps.add(CompletedOp(op.userData, Result.success(0)))
            }
            is UringOp.Write -> {
                completedOps.add(CompletedOp(op.userData, Result.success(0)))
            }
            is UringOp.ReadAt -> {
                completedOps.add(CompletedOp(op.userData, Result.success(0)))
            }
            is UringOp.WriteAt -> {
                completedOps.add(CompletedOp(op.userData, Result.success(0)))
            }
            is UringOp.Nop -> {
                completedOps.add(CompletedOp(op.userData, Result.success(0)))
            }
            is UringOp.PollAdd -> {
                completedOps.add(CompletedOp(op.userData, Result.success(0)))
            }
        }
    }

    override fun wait(min: Int): Result<Int> {
        processReadyOps()
        return Result.success(completedOps.size)
    }

    private fun processReadyOps() {
        val ready = pendingOps.filter { op ->
            when (op.op) {
                is UringOp.Read, is UringOp.ReadAt -> true
                is UringOp.Write, is UringOp.WriteAt -> true
                is UringOp.Nop, is UringOp.PollAdd -> true
            }
        }

        ready.forEach { op ->
            executeOp(op)
            pendingOps.remove(op)
        }
    }

    fun popCompleted(): UringCompletion? {
        return completedOps.removeLastOrNull()?.let { UringCompletion(it.userData, it.result) }
    }

    fun getCompletions(): List<UringCompletion> {
        val snapshot = completedOps.map { UringCompletion(it.userData, it.result) }
        completedOps.clear()
        return snapshot
    }
}
