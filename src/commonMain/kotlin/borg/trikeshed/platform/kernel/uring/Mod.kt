package borg.trikeshed.platform.kernel.uring

/**
 * liburing-compatible wrapper with software emulation fallback
 *
 * Provides io_uring-style API that automatically falls back to
 * epoll-based emulation when io_uring is unavailable.
 * Note: Actual io_uring is Linux-specific.
 */

import borg.trikeshed.platform.kernel.RawFd

/**
 * io_uring operation types
 */
sealed class UringOp {
    data class Read(val len: Int) : UringOp()
    data class Write(val len: Int) : UringOp()
    data class ReadAt(val offset: Long, val len: Int) : UringOp()
    data class WriteAt(val offset: Long, val len: Int) : UringOp()
    object Nop : UringOp()
    data class PollAdd(val mask: Int) : UringOp()
}

/**
 * Uring completion result
 */
data class UringCompletion(
    val userData: Long,
    val result: Result<Int>
)

/**
 * Uring operation builder
 */
class UringOpBuilder(
    private val uring: Uring,
    val fd: RawFd,
    val op: UringOp,
    var userData: Long = 0L
) {
    fun userData(ud: Long) = apply { this.userData = ud }

    fun submit(): Result<Unit> {
        return when (op) {
            is UringOp.Read -> uring.backend.queueRead(fd, ByteArray(op.len), userData)
            is UringOp.Write -> uring.backend.queueWrite(fd, ByteArray(op.len), userData)
            is UringOp.ReadAt -> uring.backend.queueReadAt(fd, op.offset, ByteArray(op.len), userData)
            is UringOp.WriteAt -> uring.backend.queueWriteAt(fd, op.offset, ByteArray(op.len), userData)
            is UringOp.Nop -> uring.backend.queueNop(userData)
            is UringOp.PollAdd -> uring.backend.queuePollAdd(fd, op.mask, userData)
        }
    }
}

/**
 * Main Uring interface
 */
class Uring(entries: Int) {
    internal val backend = UringBackend.new(entries)
    private val completed = mutableListOf<UringCompletion>()

    companion object {
        fun create(entries: Int): Result<Uring> {
            return runCatching { Uring(entries) }
        }
    }

    fun submit(): Result<Long> = backend.submit().map { it.toLong() }
    fun wait(min: Int): Result<Long> = backend.wait(min).map { it.toLong() }

    fun completions(): List<UringCompletion> {
        val snapshot = completed.toList()
        completed.clear()
        return snapshot
    }

    fun read(fd: RawFd, len: Int): Result<UringOpBuilder> {
        return Result.success(UringOpBuilder(this, fd, UringOp.Read(len)))
    }

    fun write(fd: RawFd, len: Int): Result<UringOpBuilder> {
        return Result.success(UringOpBuilder(this, fd, UringOp.Write(len)))
    }

    fun readAt(fd: RawFd, offset: Long, len: Int): Result<UringOpBuilder> {
        return Result.success(UringOpBuilder(this, fd, UringOp.ReadAt(offset, len)))
    }

    fun writeAt(fd: RawFd, offset: Long, len: Int): Result<UringOpBuilder> {
        return Result.success(UringOpBuilder(this, fd, UringOp.WriteAt(offset, len)))
    }

    fun nop(): Result<UringOpBuilder> {
        return Result.success(UringOpBuilder(this, -1L, UringOp.Nop))
    }

    fun pollAdd(fd: RawFd, pollMask: Int): Result<UringOpBuilder> {
        return Result.success(UringOpBuilder(this, fd, UringOp.PollAdd(pollMask)))
    }
}
