@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.cinterop.*

/** One ring, no ambient descriptor table or completion consumer. */
internal actual class NativeUringAdapter actual constructor(entries: Int) {
    private val ring = LinuxLiburingFacade()
    private val mode = UringBackendMode.parse(platform.posix.getenv("TRIKESHED_URING_MODE")?.toKString())
    private val opened: Result<Unit> = when {
        mode == UringBackendMode.EMULATED -> Result.failure(UnsupportedOperationException("TRIKESHED_URING_MODE=emulated"))
        LINUX_NATIVE_URING_LEVEL == 0 -> Result.failure(UnsupportedOperationException("LINUX_NATIVE_URING_LEVEL=0"))
        else -> ring.open(entries, 0).mapCatching { ring.probeExecution().getOrThrow() }
    }
    private val requests = mutableMapOf<Long, Pair<UringSubmission, Pinned<ByteArray>?>>()
    private val cancellations = mutableMapOf<Long, Long>()
    private val cancelled = mutableSetOf<Long>()
    private var nextUserData = 0L
    private var transportFailure: String? = null
    private var completed = mutableListOf<UringCompletion>()
    private val discoveredCapabilities: Long = if (opened.isSuccess) UringOp.entries.fold(0L) { bits, op ->
        if (op in encoded && ring.supports(op)) bits or op.mask else bits
    } else 0L
    init {
        if (mode == UringBackendMode.NATIVE) {
            opened.getOrThrow()
            if (discoveredCapabilities == 0L) {
                ring.close().getOrThrow()
                error("TRIKESHED_URING_MODE=native requires a supported io_uring opcode")
            }
        }
    }
    actual val capabilities: Long get() = discoveredCapabilities
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
            val bytes = if (submission.opcode == UringOp.OPENAT && buffer != null) {
                ByteArray(submission.len + 1).also {
                    buffer.array().copyInto(it, 0, buffer.arrayOffset() + buffer.position(),
                        buffer.arrayOffset() + buffer.position() + submission.len)
                }
            } else buffer?.array()
            val start = if (submission.opcode == UringOp.OPENAT) 0 else
                (buffer?.arrayOffset() ?: 0) + (buffer?.position() ?: 0)
            val pinned = bytes?.takeIf { it.isNotEmpty() }?.pin()
            val prepared = runCatching {
                val address = if (pinned != null && start < bytes!!.size)
                    pinned.addressOf(start).rawValue.toLong() else submission.addr
                ring.prepSubmission(submission.copy(userData = token), address).getOrThrow()
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
                if (cancellations.remove(completion.userData) != null) {
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
                cancelled.remove(completion.userData)
                completed.add(completion.copy(userData = request.first.userData))
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
        ring.close().getOrThrow()
        cancellations.clear()
        cancelled.clear()
    }

    private companion object {
        val encoded = setOf(UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
            UringOp.SEND, UringOp.RECV, UringOp.CLOSE, UringOp.FSYNC, UringOp.FTRUNCATE,
            UringOp.READ_FIXED, UringOp.WRITE_FIXED, UringOp.FADVISE, UringOp.MADVISE)
    }
}
