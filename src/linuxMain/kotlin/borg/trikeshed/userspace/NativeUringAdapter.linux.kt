@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.cinterop.*

/** One ring, no ambient descriptor table or completion consumer. */
internal actual class NativeUringAdapter actual constructor(entries: Int) {
    private val ring = LinuxLiburingFacade()
    private val opened = ring.open(entries, 0).mapCatching { ring.probeExecution().getOrThrow() }
    private val retained = mutableListOf<Pinned<ByteArray>>()
    private var transportFailure: String? = null
    private val quarantined = mutableListOf<UringCompletion>()
    private val discoveredCapabilities: Long = if (opened.isSuccess) UringOp.entries.fold(0L) { bits, op ->
        if (op in encoded && ring.supports(op)) bits or op.mask else bits
    } else 0L
    actual val capabilities: Long get() = if (transportFailure == null) discoveredCapabilities else 0L
    actual val availability: String get() = transportFailure?.let { "emulated: io_uring transport disabled: $it" }
        ?: if (opened.isSuccess) "native: io_uring setup, register and NOP execution probes succeeded; features=${ring.features}"
        else "emulated: ${opened.exceptionOrNull()?.message}"

    actual fun execute(submission: UringSubmission): UringCompletion {
        check(capabilities and submission.opcode.mask != 0L)
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
        if (pinned != null) retained.add(pinned)
        val address = if (pinned != null && start < bytes!!.size)
            pinned.addressOf(start).rawValue.toLong() else submission.addr
        val prepared = ring.prepSubmission(submission, address)
        if (prepared.isFailure) {
            if (pinned != null) { retained.remove(pinned); pinned.unpin() }
            prepared.getOrThrow()
        }
        // A prepared SQE is never built again. liburing retries only SQ entries
        // not consumed by the kernel after a failed enter. The call retains the
        // borrow, even if transport failure prevents it from completing.
        var entered = false
        var terminal: UringCompletion? = null
        while (terminal == null) {
            if (!entered) {
                val sent = ring.submit()
                entered = sent.isSuccess
                if (sent.isFailure) transportFailure = sent.exceptionOrNull()?.message ?: "submit failed"
            }
            val received = if (entered) ring.waitCqe() else ring.peekCqe()
            if (received.isFailure) transportFailure = received.exceptionOrNull()?.message ?: "completion retrieval failed"
            val completion = received.getOrNull()
            if (completion != null) {
                if (completion.userData == submission.userData) terminal = completion
                else {
                    quarantined.add(completion)
                    transportFailure = "foreign completion ${completion.userData}"
                }
            }
            if (terminal == null) platform.posix.usleep(1000u)
        }
        if (pinned != null) {
            retained.remove(pinned)
            pinned.unpin()
        }
        return terminal
    }

    actual fun registerBuffers(buffers: borg.trikeshed.lib.Series<MemoryMapping>): Result<Unit> =
        if (opened.isSuccess && transportFailure == null) ring.registerBuffers(buffers) else unsupported()

    actual fun unregisterBuffers(): Result<Unit> = ring.unregisterBuffers()

    actual fun close() {
        ring.close().getOrThrow()
        retained.forEach { it.unpin() }
        retained.clear()
    }

    private companion object {
        val encoded = setOf(UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
            UringOp.SEND, UringOp.RECV, UringOp.CLOSE, UringOp.FSYNC, UringOp.FTRUNCATE,
            UringOp.READ_FIXED, UringOp.WRITE_FIXED, UringOp.FADVISE, UringOp.MADVISE)
    }
}
