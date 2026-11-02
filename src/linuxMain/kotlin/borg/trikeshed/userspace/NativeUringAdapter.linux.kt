@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.cinterop.*

/** One ring, no ambient descriptor table or completion consumer. */
internal actual class NativeUringAdapter actual constructor(entries: Int, mode: UringBackendMode?) {
    private val ring = LinuxLiburingFacade()
    private val mode = mode ?: UringBackendMode.parse(platform.posix.getenv("TRIKESHED_URING_MODE")?.toKString())
    // `this.mode`: the constructor parameter of the same name is null when TRIKESHED_URING_MODE selects.
    private val opened: Result<Unit> = when {
        this.mode == UringBackendMode.EMULATED -> Result.failure(UnsupportedOperationException("TRIKESHED_URING_MODE=emulated"))
        LINUX_NATIVE_URING_LEVEL == 0 -> Result.failure(UnsupportedOperationException("LINUX_NATIVE_URING_LEVEL=0"))
        // SUBMIT_ALL where the kernel has it (5.18), so one enter can wait for every CQE of a batch; plain otherwise.
        else -> ring.open(entries, SETUP_SUBMIT_ALL).let { if (it.isFailure && ring.setupErrno == 22) ring.open(entries, 0) else it }
            .mapCatching { ring.probeExecution().getOrThrow() }
    }
    private val requests = mutableMapOf<Long, Admitted>()
    /** Free datagram msghdr records (see [message]). */
    val messages = ArrayList<CPointer<platform.posix.msghdr>>()
    private val cancellations = mutableMapOf<Long, Long>()
    private val cancelled = mutableSetOf<Long>()
    private var nextUserData = 0L
    private var transportFailure: String? = null
    private var completed = mutableListOf<UringCompletion>()
    /** [executeBatch]'s CQE results by SQE index, and the buffers it pins until they are reaped. */
    private var batchResults: CPointer<IntVar>? = null
    private val batchPins = arrayOfNulls<Pinned<ByteArray>>(BATCH_LIMIT)
    private val discoveredCapabilities: Long = if (opened.isSuccess) UringOp.entries.fold(0L) { bits, op ->
        if (op in encoded && ring.supports(op)) bits or op.mask else bits
    } else 0L
    init {
        if (this.mode == UringBackendMode.NATIVE) {
            opened.getOrThrow()
            if (discoveredCapabilities == 0L) {
                ring.close().getOrThrow()
                error("TRIKESHED_URING_MODE=native requires a supported io_uring opcode")
            }
        }
    }
    actual val capabilities: Long get() = discoveredCapabilities
    actual val setupErrno: Int? get() = ring.setupErrno
    actual val completionFd: Int get() = if (opened.isSuccess) ring.completionFd else -1
    actual val availability: String get() = transportFailure?.let { "native: io_uring transport failed: $it" }
        ?: if (opened.isSuccess) "native: io_uring setup, register and NOP execution probes succeeded; features=${ring.features}"
        else "emulated: ${opened.exceptionOrNull()?.message}"

    actual fun submit(submissions: List<UringSubmission>) {
        transportFailure?.let { error("io_uring transport failed: $it") }
        for (submission in submissions) {
            check(capabilities and submission.opcode.mask != 0L)
            if (ring.submissionSpace == 0) enter()
            check(ring.submissionSpace > 0) { "io_uring submission queue full" }
            val token = ++nextUserData
            check(token > 0) { "io_uring request identifiers exhausted" }
            val buffer = submission.buffer
            // Path operations get their own NUL-terminated copy; STATX keeps the caller's window,
            // which already holds the path's NUL and the struct statx the kernel writes.
            val copied = submission.opcode of pathOps
            val bytes = if (copied && buffer != null) {
                ByteArray(submission.len + 1).also {
                    buffer.array().copyInto(it, 0, buffer.arrayOffset() + buffer.position(),
                        buffer.arrayOffset() + buffer.position() + submission.len)
                }
            } else buffer?.array()
            val start = if (copied) 0 else
                (buffer?.arrayOffset() ?: 0) + (buffer?.position() ?: 0)
            val pinned = bytes?.takeIf { it.isNotEmpty() }?.pin()
            var message: CPointer<platform.posix.msghdr>? = null
            val prepared = try {
                val address = if (pinned != null && start < bytes!!.size)
                    pinned.addressOf(start).rawValue.toLong() else submission.addr
                // A datagram window goes to the kernel as a msghdr over it; a caller's own msghdr (no window) as given.
                if (buffer != null && submission.opcode of datagramOps)
                    message = message(address, submission.len, submission.opcode == UringOp.RECVMSG)
                ring.prepSubmission(submission, message?.rawValue?.toLong() ?: address, token)
            } catch (failure: Throwable) {
                pinned?.unpin()
                message?.let { messages.add(it) }
                throw failure
            }
            if (prepared.isFailure) {
                pinned?.unpin()
                message?.let { messages.add(it) }
                prepared.getOrThrow()
            }
            requests[token] = Admitted(submission, pinned, message)
        }
        enter()
    }

    /**
     * A datagram window's msghdr: one iovec over the payload from the window's start, and msg_name over its last
     * [UringOp.DATAGRAM_ADDRESS] bytes, which hold a send's destination or take a receive's sender (the kernel writes
     * only the address's length, as the emulation does; [nameReceived] clears the rest of an AF_UNIX slot). Records are
     * made once, recycled through [messages], and freed with the ring.
     */
    fun message(window: Long, len: Int, receive: Boolean): CPointer<platform.posix.msghdr> {
        val m = if (messages.isEmpty()) nativeHeap.alloc<platform.posix.msghdr>().also {
            it.msg_iov = nativeHeap.alloc<platform.posix.iovec>().ptr
            it.msg_iovlen = 1u
            it.msg_control = null
            it.msg_controllen = 0u
        }.ptr else messages.removeAt(messages.size - 1)
        val payload = len - UringOp.DATAGRAM_ADDRESS
        val name = window + payload
        val h = m.pointed
        h.msg_iov!!.pointed.iov_base = window.toCPointer<ByteVar>()
        h.msg_iov!!.pointed.iov_len = payload.toULong()
        h.msg_name = name.toCPointer<ByteVar>()
        // A receive offers the whole slot (its bytes are a previous datagram's); a send names its address's length.
        h.msg_namelen = (if (receive) UringOp.DATAGRAM_ADDRESS else nameLength(name.toCPointer<ByteVar>()!!)).toUInt()
        h.msg_flags = 0
        return m
    }

    /**
     * The length of the address a send names in its window's slot: 16 for AF_INET; for AF_UNIX the family and the name
     * through its last non-zero byte (the slot is zero-padded, and an abstract name's length is part of the name); the
     * whole slot otherwise, an AF_INET6 sockaddr.
     */
    fun nameLength(slot: CPointer<ByteVar>): Int = when (slot[0].toInt() and 255) {
        2 -> 16
        1 -> {
            var n = UringOp.DATAGRAM_ADDRESS
            while (n > 2 && slot[n - 1].toInt() == 0) n--
            n
        }
        else -> UringOp.DATAGRAM_ADDRESS
    }

    /**
     * A receive's sender name as the window's slot holds it. The kernel wrote [platform.posix.msghdr.msg_namelen] bytes
     * of it and left the rest as the previous datagram's. For AF_UNIX the rest is zeroed, so a name reads through its
     * last non-zero byte and compares as the whole slot. A sender that never bound has no name: the whole slot is
     * zeroed, family too. A name longer than the slot is refused (-ENAMETOOLONG): cut short, it would be another socket's.
     */
    fun nameReceived(message: CPointer<platform.posix.msghdr>, res: Int): Int {
        if (res < 0) return res
        val h = message.pointed
        val slot = h.msg_name!!.reinterpret<ByteVar>()
        val got = h.msg_namelen.toInt()
        if (got != 0 && (slot[0].toInt() and 255) != 1) return res
        if (got > UringOp.DATAGRAM_ADDRESS) return -platform.posix.ENAMETOOLONG
        var i = got
        while (i < UringOp.DATAGRAM_ADDRESS) slot[i++] = 0
        return res
    }

    actual fun isPending(userData: Long): Boolean = requests.values.any { it.submission.userData == userData }

    actual fun awaitCompletion(timeoutMicros: Long) {
        if (opened.isFailure || requests.isEmpty() || transportFailure != null) return
        enter()
        ring.awaitCqe(timeoutMicros)
    }

    actual fun executeBatch(submissions: List<UringSubmission>, results: IntArray): Boolean {
        val count = submissions.size
        if (opened.isFailure || transportFailure != null || count == 0 || count > BATCH_LIMIT || count > results.size ||
            requests.isNotEmpty() || cancellations.isNotEmpty() || completed.isNotEmpty() ||
            ring.pendingSubmissions != 0 || ring.submissionSpace < count) return false
        val scratch = batchResults ?: nativeHeap.allocArray<IntVar>(BATCH_LIMIT).also { batchResults = it }
        var prepared = 0
        try {
            try {
                while (prepared < count) {
                    val submission = submissions[prepared]
                    // Path copies and datagram msghdrs live in [requests]; a batch carries neither.
                    if (capabilities and submission.opcode.mask == 0L || submission.opcode of pathOps ||
                        submission.opcode of datagramOps) break
                    val buffer = submission.buffer
                    val bytes = buffer?.array()
                    val start = if (buffer == null) 0 else buffer.arrayOffset() + buffer.position()
                    val pinned = bytes?.takeIf { it.isNotEmpty() }?.pin()
                    batchPins[prepared] = pinned
                    val address = if (pinned != null && start < bytes.size) pinned.addressOf(start).rawValue.toLong() else submission.addr
                    if (!ring.prepIndexed(submission, address, prepared)) break
                    prepared++
                }
            } catch (failure: Throwable) {
                ring.unprepIndexed(prepared)
                throw failure
            }
            if (prepared < count) {
                ring.unprepIndexed(prepared)
                return false
            }
            // Returns only once every CQE is reaped: the pins below are released after the kernel is done with them.
            if (ring.submitReap(count, scratch) != 0) transportFailure = "foreign CQE during a batch"
            for (index in 0 until count) results[index] = scratch[index]
            return true
        } finally {
            for (index in 0..minOf(prepared, count - 1)) {
                batchPins[index]?.unpin()
                batchPins[index] = null
            }
        }
    }

    private fun enter() {
        if (ring.pendingSubmissions == 0) return
        ring.submit().onFailure { transportFailure = it.message }.getOrThrow()
    }

    actual fun reapCompletions(): List<UringCompletion> {
        if (opened.isFailure) return emptyList()
        if (completed.isEmpty()) {
            var failure = runCatching { enter() }.exceptionOrNull()
            fun record(error: Throwable) {
                transportFailure = error.message
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
            while (true) {
                val received = ring.peekCqe()
                if (received.isFailure) {
                    record(received.exceptionOrNull()!!)
                    break
                }
                val completion = received.getOrNull() ?: break
                if (cancellations.isNotEmpty() && cancellations.remove(completion.userData) != null) {
                    if (completion.res != 0 && completion.res != -platform.posix.ENOENT &&
                        completion.res != -platform.posix.EALREADY) {
                        record(IllegalStateException("io_uring cancellation failed: ${completion.res}"))
                    }
                    continue
                }
                val request = requests.remove(completion.userData)
                if (request == null) {
                    record(IllegalStateException("unknown io_uring completion ${completion.userData}"))
                    continue
                }
                // The cancellation CQE never releases this borrow. Only this
                // original request's terminal CQE proves the kernel is finished.
                val message = request.message
                val res = if (message != null && request.submission.opcode == UringOp.RECVMSG) nameReceived(message, completion.res) else completion.res
                request.pinned?.unpin()
                message?.let { messages.add(it) }
                if (cancelled.isNotEmpty()) cancelled.remove(completion.userData)
                completed.add(UringCompletion(request.submission.userData, res, completion.flags))
            }
            // Report malformed CQEs after examining this CQ batch. Keep every
            // valid terminal result available to the caller's cleanup reaper.
            failure?.let { throw it }
        }
        return completed.also { completed = mutableListOf() }
    }

    actual fun cancelPending(userData: Set<Long>?) {
        if (opened.isFailure) return
        enter()
        for ((target, request) in requests) {
            if (userData != null && request.submission.userData !in userData) continue
            if (target in cancelled) continue
            if (ring.submissionSpace == 0) enter()
            check(ring.submissionSpace > 0) { "io_uring cancellation queue full" }
            val token = ++nextUserData
            check(token > 0) { "io_uring request identifiers exhausted" }
            ring.prepCancel(target, token).getOrThrow()
            cancellations[token] = target
            cancelled.add(target)
        }
        enter()
    }

    actual fun registerBuffers(buffers: borg.trikeshed.lib.Series<MemoryMapping>): Result<Unit> =
        if (opened.isSuccess && transportFailure == null) ring.registerBuffers(buffers) else unsupported()

    actual fun fadvise(fd: Int, offset: Long, length: Int, advice: Int): Int =
        if (offset < 0 || length < 0 || advice !in 0..5) -22
        else -platform.posix.posix_fadvise(fd, offset, length.toLong(), advice)

    actual fun unregisterBuffers(): Result<Unit> = if (requests.values.any {
        it.submission.opcode == UringOp.READ_FIXED || it.submission.opcode == UringOp.WRITE_FIXED
    }) Result.failure(IllegalStateException("fixed buffer requests are still in flight"))
    else ring.unregisterBuffers()

    actual fun close() {
        check(requests.isEmpty()) { "io_uring requests must settle before closing their ring" }
        batchResults?.let { nativeHeap.free(it) }
        batchResults = null
        for (m in messages) {
            nativeHeap.free(m.pointed.msg_iov!!.pointed)
            nativeHeap.free(m.pointed)
        }
        messages.clear()
        ring.close().getOrThrow()
        cancellations.clear()
        cancelled.clear()
    }

    private companion object {
        /** The most SQEs [executeBatch] takes. */
        const val BATCH_LIMIT = 64
        val pathOps = UringOp.caps(UringOp.OPENAT, UringOp.MKDIRAT, UringOp.RENAMEAT, UringOp.UNLINKAT)
        val datagramOps = UringOp.caps(UringOp.SENDMSG, UringOp.RECVMSG)
        // POLL_REMOVE and URING_CMD stay with the common backend: it holds the requests a removal names, and the
        // probe reports URING_CMD without its socket commands.
        val encoded = setOf(UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
            UringOp.SEND, UringOp.RECV, UringOp.CLOSE, UringOp.FSYNC, UringOp.FTRUNCATE,
            UringOp.READ_FIXED, UringOp.WRITE_FIXED, UringOp.FADVISE, UringOp.MADVISE,
            UringOp.STATX, UringOp.MKDIRAT, UringOp.RENAMEAT, UringOp.UNLINKAT, UringOp.SOCKET, UringOp.BIND,
            UringOp.LISTEN, UringOp.ACCEPT, UringOp.CONNECT, UringOp.SHUTDOWN, UringOp.POLL_ADD,
            UringOp.SENDMSG, UringOp.RECVMSG)
    }
}

/** A request the kernel holds: its submission, the pinned window it reads or writes, and a datagram's msghdr. */
internal class Admitted(
    val submission: UringSubmission,
    val pinned: Pinned<ByteArray>?,
    val message: CPointer<platform.posix.msghdr>?,
)
