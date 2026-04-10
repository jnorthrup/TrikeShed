package borg.trikeshed.platform.nio

import borg.trikeshed.platform.nio.BackendConfig
import borg.trikeshed.platform.nio.Completion
import borg.trikeshed.platform.nio.Interest
import borg.trikeshed.platform.nio.OpType
import borg.trikeshed.platform.nio.PlatformBackend
import borg.trikeshed.platform.nio.Token

/**
 * macOS/BSD kqueue NIO backend
 *
 * Provides NIO backend for macOS and BSD systems using kqueue.
 * Note: Actual kqueue is platform-specific; this declares the interface.
 */
class KqueuePlatformBackend(config: BackendConfig) : PlatformBackend {
    // FFI handle for kqueue fd
    private var kqueueFd: Long = -1L
    private val registeredFds = mutableMapOf<Long, Token>()
    private val pendingCompletions = mutableListOf<Completion>()

    override fun register(fd: Long, token: Token, interest: Interest): Result<Unit> {
        // macOS/BSD-specific: kevent EVFILT_READ/EVFILT_WRITE with EV_ADD
        registeredFds[fd] = token
        return Result.success(Unit)
    }

    override fun reregister(fd: Long, token: Token, interest: Interest): Result<Unit> {
        // Remove existing registrations then re-add
        unregister(fd)
        return register(fd, token, interest)
    }

    override fun unregister(fd: Long): Result<Unit> {
        // macOS/BSD-specific: kevent EV_DELETE
        registeredFds.remove(fd)
        return Result.success(Unit)
    }

    override fun submitRead(fd: Long, buf: ByteArray, userData: Long): Result<Unit> {
        // kqueue doesn't have direct submit_read; read when FD is readable
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.Read))
        return Result.success(Unit)
    }

    override fun submitWrite(fd: Long, buf: ByteArray, userData: Long): Result<Unit> {
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.Write))
        return Result.success(Unit)
    }

    override fun submitReadAt(fd: Long, offset: Long, buf: ByteArray, userData: Long): Result<Unit> {
        // Use pread for positioned reads
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.Read))
        return Result.success(Unit)
    }

    override fun submitWriteAt(fd: Long, offset: Long, buf: ByteArray, userData: Long): Result<Unit> {
        // Use pwrite for positioned writes
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.Write))
        return Result.success(Unit)
    }

    override fun submitPoll(fd: Long, interest: Interest, userData: Long): Result<Unit> {
        // macOS/BSD-specific: kevent for poll
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.PollAdd))
        return Result.success(Unit)
    }

    override fun submitNop(userData: Long): Result<Unit> {
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.Nop))
        return Result.success(Unit)
    }

    override fun submit(): Result<Long> {
        // kqueue doesn't have a separate submit step
        return Result.success(pendingCompletions.size.toLong())
    }

    override fun wait(min: Int): Result<Long> {
        // macOS/BSD-specific: kevent()
        return Result.success(pendingCompletions.size.toLong())
    }

    override fun peek(): Result<Long> = wait(0)

    override fun pollCompletion(): Result<Completion?> {
        return Result.success(pendingCompletions.removeLastOrNull())
    }

    override fun pollCompletions(completions: Array<Completion?>): Result<Int> {
        val count = minOf(completions.size, pendingCompletions.size)
        for (i in 0 until count) {
            completions[i] = pendingCompletions.removeAt(0)
        }
        return Result.success(count)
    }

    override fun asRawFd(): Long? = kqueueFd.takeIf { it >= 0 }

    fun close() {
        if (kqueueFd >= 0) {
            // macOS/BSD-specific: close(kqueueFd)
            kqueueFd = -1L
        }
    }
}
