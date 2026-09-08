@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import kotlinx.cinterop.*
import zlinux_uring.IORING_FSYNC_DATASYNC
import zlinux_uring.io_uring
import zlinux_uring.io_uring_cqe
import zlinux_uring.io_uring_cqe_seen
import zlinux_uring.io_uring_get_sqe
import zlinux_uring.io_uring_peek_cqe
import zlinux_uring.io_uring_prep_accept
import zlinux_uring.io_uring_prep_close
import zlinux_uring.io_uring_prep_connect
import zlinux_uring.io_uring_prep_fsync
import zlinux_uring.io_uring_prep_ftruncate
import zlinux_uring.k_io_uring_prep_sendmsg
import zlinux_uring.k_io_uring_prep_recvmsg
import zlinux_uring.io_uring_prep_read
import zlinux_uring.io_uring_prep_write
import zlinux_uring.io_uring_queue_exit
import zlinux_uring.io_uring_queue_init
import zlinux_uring.io_uring_submit
import zlinux_uring.io_uring_wait_cqe

internal actual object LiburingImpl : LiburingFacade by LinuxLiburingFacade()

/** Each userspace backend owns one of these rings. The legacy singleton is separate. */
internal class LinuxLiburingFacade : LiburingFacade {
    private var ring: CPointer<io_uring>? = null
    private var inFlight = 0
    private val requests = mutableMapOf<Long, Int>()
    private var probe: CPointer<zlinux_uring.io_uring_probe>? = null
    var features: UInt = 0u
        private set
    fun supports(op: UringOp): Boolean = op.code >= 0 && probe?.let {
        zlinux_uring.io_uring_opcode_supported(it, op.code) != 0
    } == true

    private val handlers = mutableMapOf<Long, MutableList<(UringCompletion) -> Unit>>()

    override fun open(entries: Int, flags: Int): Result<Unit> {
        if (ring != null) return Result.success(Unit)
        if (entries <= 0) return failure("entries must be positive", -22)
        val allocated = nativeHeap.alloc<io_uring>()
        val rc = io_uring_queue_init(entries.toUInt(), allocated.ptr, flags.toUInt())
        if (rc < 0) {
            nativeHeap.free(allocated.ptr)
            return failure("io_uring_queue_init failed", rc)
        }
        ring = allocated.ptr
        features = allocated.features
        probe = zlinux_uring.io_uring_get_probe_ring(allocated.ptr)
        if (probe == null) {
            val probeError = platform.posix.errno
            close()
            return failure("io_uring_register probe failed", -probeError)
        }
        return Result.success(Unit)
    }

    /** Before borrowing any application resource, prove enter and CQ retrieval work. */
    fun probeExecution(): Result<Unit> {
        check(inFlight == 0) { "execution probe must precede application admission" }
        val token = Long.MIN_VALUE
        val prepared = prepSubmission(UringOp.Companion.Submissions.nop(token), 0)
        val submitted = if (prepared.isSuccess) submit() else Result.failure(prepared.exceptionOrNull()!!)
        val completion = if (submitted.getOrNull() == 1) waitCqe() else null
        val terminal = completion?.getOrNull()
        if (terminal?.userData == token && terminal.res == 0) return Result.success(Unit)
        val detail = prepared.exceptionOrNull() ?: submitted.exceptionOrNull() ?: completion?.exceptionOrNull()
        // Only a NOP has been prepared here: no caller buffer or descriptor is
        // borrowed, so discarding this probe cannot release application memory.
        val currentRing = ring
        if (currentRing != null) {
            io_uring_queue_exit(currentRing)
            nativeHeap.free(currentRing.rawValue)
            ring = null
        }
        probe?.let { zlinux_uring.io_uring_free_probe(it) }
        probe = null
        requests.clear()
        inFlight = 0
        return Result.failure(IllegalStateException("io_uring NOP execution probe failed: ${terminal?.res ?: detail?.message}"))
    }

    override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            io_uring_prep_read(sqe, fd, bufAddress.toCPointer<ByteVar>(), len.toUInt(), offset.toULong())
        }

    override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            io_uring_prep_write(sqe, fd, bufAddress.toCPointer<ByteVar>(), len.toUInt(), offset.toULong())
        }

    override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            io_uring_prep_accept(sqe, fd, null, null, 0)
        }

    override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            io_uring_prep_connect(
                sqe,
                fd,
                addrPtr.toCPointer<ByteVar>()?.reinterpret(),
                addrLen.toUInt(),
            )
        }

    override fun prepClose(fd: Int, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            io_uring_prep_close(sqe, fd)
        }

    override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> =
        prepare(userData) { sqe ->
            io_uring_prep_fsync(sqe, fd, if (datasync) IORING_FSYNC_DATASYNC.toUInt() else 0u)
        }

    override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            io_uring_prep_ftruncate(sqe, fd, size)
        }

    override fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit> =
        Result.failure(UnsupportedOperationException("mmap is not supported via io_uring directly"))

    override fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit> =
        Result.failure(UnsupportedOperationException("munmap is not supported via io_uring directly"))

    override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            k_io_uring_prep_sendmsg(sqe, fd, msgHdrPtr.toCPointer<COpaque>(), flags.toUInt())
        }

    override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        prepare(userData) { sqe ->
            k_io_uring_prep_recvmsg(sqe, fd, msgHdrPtr.toCPointer<COpaque>(), flags.toUInt())
        }

    override fun submit(): Result<Int> {
        val currentRing = ring ?: return failure("liburing ring is not open")
        val rc = io_uring_submit(currentRing)
        return if (rc < 0) failure("io_uring_submit failed", rc) else Result.success(rc)
    }

    override fun waitCqe(): Result<UringCompletion?> {
        val currentRing = ring ?: return failure("liburing ring is not open")
        memScoped {
            val cqe = alloc<CPointerVar<io_uring_cqe>>()
            var rc: Int
            do { rc = io_uring_wait_cqe(currentRing, cqe.ptr) } while (rc == -platform.posix.EINTR)
            if (rc < 0) return failure("io_uring_wait_cqe failed", rc)
            val ready = cqe.value ?: return failure("io_uring_wait_cqe returned no CQE")
            val completion = ready.toCompletion()
            io_uring_cqe_seen(currentRing, ready)
            settled(completion.userData)
            publish(completion)
            return Result.success(completion)
        }
    }

    override fun peekCqe(): Result<UringCompletion?> {
        val currentRing = ring ?: return failure("liburing ring is not open")
        memScoped {
            val cqe = alloc<CPointerVar<io_uring_cqe>>()
            val rc = io_uring_peek_cqe(currentRing, cqe.ptr)
            if (rc == -platform.posix.EAGAIN) return Result.success(null)
            if (rc < 0) return failure("io_uring_peek_cqe failed", rc)
            val ready = cqe.value ?: return Result.success(null)
            val completion = ready.toCompletion()
            io_uring_cqe_seen(currentRing, ready)
            settled(completion.userData)
            publish(completion)
            return Result.success(completion)
        }
    }

    override fun cqAdvance(count: Int) {
        // waitCqe/peekCqe transfer ownership and advance exactly once.
        require(count >= 0)
    }

    override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        handlers.getOrPut(token) { mutableListOf() }.add(handler)
    }

    override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        handlers[token]?.remove(handler)
        if (handlers[token].isNullOrEmpty()) handlers.remove(token)
    }

    override fun drain(): Result<Unit> {
        val currentRing = ring ?: return Result.success(Unit)
        val rc = io_uring_submit(currentRing)
        if (rc < 0) return failure("io_uring_submit (drain) failed", rc)
        while (inFlight > 0) {
            val completion = waitCqe()
            if (completion.isFailure) return Result.failure(completion.exceptionOrNull()!!)
        }
        return Result.success(Unit)
    }

    override fun close(): Result<Unit> {
        val currentRing = ring ?: return Result.success(Unit)
        val drained = drain()
        if (drained.isFailure) return drained
        probe?.let { zlinux_uring.io_uring_free_probe(it) }
        probe = null
        io_uring_queue_exit(currentRing)
        nativeHeap.free(currentRing.rawValue)
        ring = null
        handlers.clear()
        return Result.success(Unit)
    }

    fun prepSubmission(sub: UringOp.Companion.UringSubmission, address: Long): Result<Unit> {
        if (!supports(sub.opcode)) return failure("opcode not supported by kernel probe", -95)
        return prepare(sub.userData) { sqe ->
            val ptr = address.toCPointer<ByteVar>()
            when (sub.opcode) {
                UringOp.NOP -> zlinux_uring.io_uring_prep_nop(sqe)
                UringOp.OPENAT -> zlinux_uring.io_uring_prep_openat(sqe, sub.fd, ptr, sub.offset.toInt(), 438u)
                UringOp.READ -> io_uring_prep_read(sqe, sub.fd, ptr, sub.len.toUInt(), sub.offset.toULong())
                UringOp.WRITE -> io_uring_prep_write(sqe, sub.fd, ptr, sub.len.toUInt(), sub.offset.toULong())
                UringOp.SEND -> zlinux_uring.io_uring_prep_send(sqe, sub.fd, ptr, sub.len.toULong(), 0)
                UringOp.RECV -> zlinux_uring.io_uring_prep_recv(sqe, sub.fd, ptr, sub.len.toULong(), 0)
                UringOp.CLOSE -> io_uring_prep_close(sqe, sub.fd)
                UringOp.FSYNC -> io_uring_prep_fsync(sqe, sub.fd, 0u)
                UringOp.FTRUNCATE -> io_uring_prep_ftruncate(sqe, sub.fd, sub.offset)
                else -> error("No native encoder for ${sub.opcode}")
            }
        }
    }

    private inline fun prepare(userData: Long, block: (CPointer<zlinux_uring.io_uring_sqe>) -> Unit): Result<Unit> {
        val currentRing = ring ?: return failure("liburing ring is not open")
        val sqe = io_uring_get_sqe(currentRing) ?: return failure("io_uring_get_sqe returned null")
        block(sqe)
        sqe.pointed.user_data = userData.toULong()
        inFlight++
        requests[userData] = (requests[userData] ?: 0) + 1
        return Result.success(Unit)
    }

    private fun settled(userData: Long) {
        val count = requests[userData] ?: return
        if (count == 1) requests.remove(userData) else requests[userData] = count - 1
        inFlight--
    }

    private fun publish(completion: UringCompletion) {
        handlers[completion.userData]?.toList()?.forEach { it(completion) }
    }

    private fun CPointer<io_uring_cqe>.toCompletion(): UringCompletion =
        UringCompletion(
            userData = pointed.user_data.toLong(),
            res = pointed.res,
            flags = pointed.flags.toInt(),
        )
}

private fun <T> failure(message: String, rc: Int? = null): Result<T> =
    Result.failure(IllegalStateException(if (rc == null) message else "$message: $rc"))
