@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import platform.posix.sockaddr
import zlinux_uring.IORING_FSYNC_DATASYNC
import zlinux_uring.io_uring
import zlinux_uring.io_uring_cq_advance
import zlinux_uring.io_uring_cqe
import zlinux_uring.io_uring_cqe_seen
import zlinux_uring.io_uring_get_sqe
import zlinux_uring.io_uring_peek_cqe
import zlinux_uring.io_uring_prep_accept
import zlinux_uring.io_uring_prep_close
import zlinux_uring.io_uring_prep_connect
import zlinux_uring.io_uring_prep_fsync
import zlinux_uring.io_uring_prep_ftruncate
import zlinux_uring.io_uring_prep_mmap
import zlinux_uring.io_uring_prep_munmap
import zlinux_uring.io_uring_prep_read
import zlinux_uring.io_uring_prep_write
import zlinux_uring.io_uring_queue_exit
import zlinux_uring.io_uring_queue_init
import zlinux_uring.io_uring_submit
import zlinux_uring.io_uring_wait_cqe

internal actual object LiburingImpl : LiburingFacade {
    private var ring: CPointer<io_uring>? = null
    private val handlers = mutableMapOf<Long, MutableList<(UringCompletion) -> Unit>>()

    actual override fun open(entries: Int, flags: Int): Result<Unit> {
        if (ring != null) return Result.success(Unit)
        val allocated = nativeHeap.alloc<io_uring>()
        val rc = io_uring_queue_init(entries, allocated.ptr, flags.toUInt())
        if (rc < 0) {
            nativeHeap.free(allocated.ptr)
            return failure("io_uring_queue_init failed", rc)
        }
        ring = allocated.ptr
        return Result.success(Unit)
    }

    actual override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            sqe.pointed.user_data = userData.toULong()
            io_uring_prep_read(sqe, fd, bufAddress.toCPointer<ByteVar>(), len.toUInt(), offset)
        }

    actual override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            sqe.pointed.user_data = userData.toULong()
            io_uring_prep_write(sqe, fd, bufAddress.toCPointer<ByteVar>(), len.toUInt(), offset)
        }

    actual override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            sqe.pointed.user_data = userData.toULong()
            io_uring_prep_accept(sqe, fd, null, null, 0)
        }

    actual override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            sqe.pointed.user_data = userData.toULong()
            io_uring_prep_connect(sqe, fd, addrPtr.toCPointer<sockaddr>(), addrLen.toUInt())
        }

    actual override fun prepClose(fd: Int, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            sqe.pointed.user_data = userData.toULong()
            io_uring_prep_close(sqe, fd)
        }

    actual override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> =
        prepare(userData) { sqe ->
            sqe.pointed.user_data = userData.toULong()
            io_uring_prep_fsync(sqe, fd, if (datasync) IORING_FSYNC_DATASYNC.toUInt() else 0u)
        }

    actual override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            sqe.pointed.user_data = userData.toULong()
            io_uring_prep_ftruncate(sqe, fd, size)
        }

    actual override fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            sqe.pointed.user_data = userData.toULong()
            io_uring_prep_mmap(sqe, fd, addr, len, prot, flags, offset)
        }

    actual override fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            sqe.pointed.user_data = userData.toULong()
            io_uring_prep_munmap(sqe, addr, len)
        }

    actual override fun submit(): Result<Int> {
        val currentRing = ring ?: return failure("liburing ring is not open")
        val rc = io_uring_submit(currentRing)
        return if (rc < 0) failure("io_uring_submit failed", rc) else Result.success(rc)
    }

    actual override fun waitCqe(): Result<UringCompletion> {
        val currentRing = ring ?: return failure("liburing ring is not open")
        memScoped {
            val cqe = alloc<CPointerVar<io_uring_cqe>>()
            val rc = io_uring_wait_cqe(currentRing, cqe.ptr)
            if (rc < 0) return failure("io_uring_wait_cqe failed", rc)
            val ready = cqe.value ?: return failure("io_uring_wait_cqe returned no CQE")
            val completion = ready.toCompletion()
            io_uring_cqe_seen(currentRing, ready)
            publish(completion)
            return Result.success(completion)
        }
    }

    actual override fun peekCqe(): Result<UringCompletion?> {
        val currentRing = ring ?: return failure("liburing ring is not open")
        memScoped {
            val cqe = alloc<CPointerVar<io_uring_cqe>>()
            val rc = io_uring_peek_cqe(currentRing, cqe.ptr)
            if (rc < 0) return failure("io_uring_peek_cqe failed", rc)
            val ready = cqe.value ?: return Result.success(null)
            val completion = ready.toCompletion()
            io_uring_cqe_seen(currentRing, ready)
            publish(completion)
            return Result.success(completion)
        }
    }

    actual override fun cqAdvance(count: Int) {
        val currentRing = ring ?: return
        io_uring_cq_advance(currentRing, count.toUInt())
    }

    actual override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        handlers.getOrPut(token) { mutableListOf() }.add(handler)
    }

    actual override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        handlers[token]?.remove(handler)
        if (handlers[token].isNullOrEmpty()) handlers.remove(token)
    }

    actual override fun drain(): Result<Unit> {
        val currentRing = ring ?: return Result.success(Unit)
        val rc = io_uring_submit(currentRing)
        return if (rc < 0) failure("io_uring_submit (drain) failed", rc) else Result.success(Unit)
    }

    actual override fun close(): Result<Unit> {
        val currentRing = ring ?: return Result.success(Unit)
        io_uring_queue_exit(currentRing)
        nativeHeap.free(currentRing)
        ring = null
        handlers.clear()
        return Result.success(Unit)
    }

    private inline fun prepare(userData: Long, block: (CPointer<zlinux_uring.io_uring_sqe>) -> Unit): Result<Unit> {
        val currentRing = ring ?: return failure("liburing ring is not open")
        val sqe = io_uring_get_sqe(currentRing) ?: return failure("io_uring_get_sqe returned null")
        block(sqe)
        return Result.success(Unit)
    }

    private fun publish(completion: UringCompletion) {
        handlers[completion.userData]?.toList()?.forEach { it(completion) }
    }

    private fun CPointer<io_uring_cqe>.toCompletion(): UringCompletion =
        UringCompletion(
            userData = pointed.user_data.toLong(),
            res = pointed.res,
            flags = pointed.flags,
        )
}

private fun <T> failure(message: CharSequence, rc: Int? = null): Result<T> =
    Result.failure(IllegalStateException(if (rc == null) message else "$message: $rc"))
