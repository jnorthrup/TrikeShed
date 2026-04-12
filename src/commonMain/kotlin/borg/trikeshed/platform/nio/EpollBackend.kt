package borg.trikeshed.platform.nio

import borg.trikeshed.platform.nio.BackendConfig
import borg.trikeshed.platform.nio.Completion
import borg.trikeshed.platform.nio.Interest
import borg.trikeshed.platform.nio.OpType
import borg.trikeshed.platform.nio.PlatformBackend
import borg.trikeshed.platform.nio.Token

/**
 * Linux epoll NIO backend
 *
 * Provides the fallback NIO backend for Linux using epoll,
 * when io_uring is not available or not desired.
 *
 * Note: Actual epoll is Linux-specific; this declares the interface.
 */
class EpollPlatformBackend(config: BackendConfig) : PlatformBackend {
    // FFI handle for epoll fd
    private var epollFd: Long = -1L
    private val registeredFds = mutableMapOf<Long, Token>()
    private val pendingCompletions = mutableListOf<Completion>()

     fun register(fd: Long, token: Token, interest: Interest): Result<Unit> {
        // Linux-specific: epoll_ctl EPOLL_CTL_ADD
        registeredFds[fd] = token
        return Result.success(Unit)
    }

     fun reregister(fd: Long, token: Token, interest: Interest): Result<Unit> {
        // Linux-specific: epoll_ctl EPOLL_CTL_MOD
        registeredFds[fd] = token
        return Result.success(Unit)
    }

      fun unregister(fd: Long): Result<Unit> {
        // Linux-specific: epoll_ctl EPOLL_CTL_DEL
        registeredFds.remove(fd)
        return Result.success(Unit)
    }

      fun submitRead(fd: Long, buf: ByteArray, userData: Long): Result<Unit> {
        // Linux-specific: read()
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.Read))
        return Result.success(Unit)
    }

      fun submitWrite(fd: Long, buf: ByteArray, userData: Long): Result<Unit> {
        // Linux-specific: write()
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.Write))
        return Result.success(Unit)
    }

      fun submitReadAt(fd: Long, offset: Long, buf: ByteArray, userData: Long): Result<Unit> {
        // Linux-specific: pread()
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.Read))
        return Result.success(Unit)
    }

      fun submitWriteAt(fd: Long, offset: Long, buf: ByteArray, userData: Long): Result<Unit> {
        // Linux-specific: pwrite()
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.Write))
        return Result.success(Unit)
    }

     fun submitPoll(fd: Long, interest: Interest, userData: Long): Result<Unit> {
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.PollAdd))
        return Result.success(Unit)
    }

     fun submitNop(userData: Long): Result<Unit> {
        pendingCompletions.add(Completion(userData, Result.success(0), OpType.Nop))
        return Result.success(Unit)
    }

     fun submit(): Result<Long> {
        return Result.success(pendingCompletions.size.toLong())
    }

     fun wait(min: Int): Result<Long> {
        // Linux-specific: epoll_wait()
        return Result.success(pendingCompletions.size.toLong())
    }

     fun peek(): Result<Long> = wait(0)

     fun pollCompletion(): Result<Completion?> {
        return Result.success(pendingCompletions.removeLastOrNull())
    }

     fun pollCompletions(completions: Array<Completion?>): Result<Int> {
        val count = minOf(completions.size, pendingCompletions.size)
        for (i in 0 until count) {
            completions[i] = pendingCompletions.removeAt(0)
        }
        return Result.success(count)
    }

     fun asRawFd(): Long? = epollFd.takeIf { it >= 0 }

    fun close() {
        if (epollFd >= 0) {
            // Linux-specific: close(epollFd)
            epollFd = -1L
        }
    }
}
