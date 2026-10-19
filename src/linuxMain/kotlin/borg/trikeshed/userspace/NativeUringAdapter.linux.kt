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
    private val requests = mutableMapOf<Long, Pair<UringSubmission, Pinned<ByteArray>?>>()
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
            val prepared = try {
                val address = if (pinned != null && start < bytes!!.size)
                    pinned.addressOf(start).rawValue.toLong() else submission.addr
                ring.prepSubmission(submission, address, token)
            } catch (failure: Throwable) {
                pinned?.unpin()
                throw failure
            }
            if (prepared.isFailure) {
                pinned?.unpin()
                prepared.getOrThrow()
            }
            requests[token] = submission to pinned
        }
        enter()
    }

    actual fun isPending(userData: Long): Boolean = requests.values.any { it.first.userData == userData }

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
                    if (capabilities and submission.opcode.mask == 0L || submission.opcode of pathOps) break
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
                request.second?.unpin()
                if (cancelled.isNotEmpty()) cancelled.remove(completion.userData)
                completed.add(UringCompletion(request.first.userData, completion.res, completion.flags))
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
            if (userData != null && request.first.userData !in userData) continue
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
        it.first.opcode == UringOp.READ_FIXED || it.first.opcode == UringOp.WRITE_FIXED
    }) Result.failure(IllegalStateException("fixed buffer requests are still in flight"))
    else ring.unregisterBuffers()

    actual fun close() {
        check(requests.isEmpty()) { "io_uring requests must settle before closing their ring" }
        batchResults?.let { nativeHeap.free(it) }
        batchResults = null
        ring.close().getOrThrow()
        cancellations.clear()
        cancelled.clear()
    }

    private companion object {
        /** The most SQEs [executeBatch] takes. */
        const val BATCH_LIMIT = 64
        val pathOps = UringOp.caps(UringOp.OPENAT, UringOp.MKDIRAT, UringOp.RENAMEAT, UringOp.UNLINKAT)
        // POLL_REMOVE and URING_CMD stay with the common backend: it holds the requests a removal names, and the
        // probe reports URING_CMD without its socket commands.
        val encoded = setOf(UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
            UringOp.SEND, UringOp.RECV, UringOp.CLOSE, UringOp.FSYNC, UringOp.FTRUNCATE,
            UringOp.READ_FIXED, UringOp.WRITE_FIXED, UringOp.FADVISE, UringOp.MADVISE,
            UringOp.STATX, UringOp.MKDIRAT, UringOp.RENAMEAT, UringOp.UNLINKAT, UringOp.SOCKET, UringOp.BIND,
            UringOp.LISTEN, UringOp.ACCEPT, UringOp.CONNECT, UringOp.SHUTDOWN, UringOp.POLL_ADD)
    }
}
