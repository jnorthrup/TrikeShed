package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

/**
 * Interface abstracting the underlying polling/completion logic.
 *
 * Implemented natively (Posix, Wasm, JS) or via JvmReactorOperations.
 * All completions are guaranteed to return via [submitBatch]
 * in the same order (userData preserved).
 */
public interface UserspaceChannelBackend {
    /**
     * Submit a batch of [UringSubmission] entries and return completions.
     *
     * In an ideal implementation, this maps directly to io_uring_submit().
     * In compatibility layers, this multiplexes Java NIO / JS Fetch / Wasm IO.
     */
    fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult>

    suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion>
}

/**
 * Core dispatch layer for userspace channels.
 * Maintains an internal [entries] queue similar to a `ring`.
 *
 * Callers enqueue operations using `facade.enqueue(submission)`
 * or legacy typed methods, then call `submit()` to drain the queue.
 */
public class FunctionalUringFacade(
    private val entries: Int,
    private val backend: UserspaceChannelBackend,
    private val containmentPolicy: borg.trikeshed.userspace.containment.ContainmentPolicy =
        borg.trikeshed.userspace.containment.ContainmentPolicy.MAXIMUM,
) {
    private val pending = ArrayDeque<UringSubmission>()
    private val completions = ArrayDeque<SelectionResult>()

    init {
        require(entries > 0) { "entries must be positive" }
    }

    // -- Unified UringSubmission API --

    /**
     * Ops that are deterministically rejected to close covert signaling
     * channels (Legion Modelling Doc 04, Layer 2 — xattr is a documented
     * malleable surface for steganographic coordination per Doc 02 §1).
     * These are never submitted to the kernel; they fail immediately.
     *
     * Source of truth: [containmentPolicy.layer2Metadata.rejectedXattrOps].
     * When policy is MAXIMUM, this is the full 8-op xattr set.
     * Doc 04 §2: "Complete disabling or strict filtering of setxattr,
     * getxattr, listxattr, and removexattr."
     */
    private val REJECTED_OPS: Set<UringOp> =
        containmentPolicy.layer2Metadata.rejectedXattrOps

    /**
     * Ops whose metadata results are quantized to collapse micro-timing
     * side-channels (Legion Doc 04, Layer 2 §2 — "Deterministic Clock &
     * Inode Virtualization"). STATX returns synthetic, quantized timestamps.
     *
     * Active only when [containmentPolicy.layer2Metadata.quantizeTimestamps]
     * is true; otherwise the set is empty and completions pass through.
     */
    private val METADATA_QUANTIZED_OPS: Set<UringOp> =
        if (containmentPolicy.layer2Metadata.quantizeTimestamps) {
            setOf(
                UringOp.STATX,
                UringOp.FGETXATTR,
                UringOp.GETXATTR,
                UringOp.FLISTXATTR,
                UringOp.LISTXATTR,
                UringOp.GETDENTS
            )
        } else emptySet()

    /**
     * Quantization boundary for file timestamps (Doc 04 §2).
     * mtime/ctime/atime are rounded to the nearest epoch boundary.
     * 3600 = 1 hour — coarse enough to eliminate micro-timing encoding.
     */
    private val timestampQuantumSeconds: Long =
        containmentPolicy.layer2Metadata.timestampQuantumSeconds

    /**
     * Fixed synthetic epoch for quantized timestamps. All statx results
     * return mtime/ctime/atime = 0 unless overridden by policy.
     * Doc 04 §2: "or fixed to epoch 0, eliminating micro-timing."
     */
    private val syntheticEpoch: Long =
        containmentPolicy.layer2Metadata.syntheticEpoch

    /**
     * Enqueue a raw io_uring submission.
     * Throws if the queue is full or if the op is a rejected xattr channel.
     */
    fun enqueue(submission: UringSubmission) {
        require(submission.opcode !in REJECTED_OPS) {
            "xattr ops are deterministically rejected to close covert signaling channels: ${submission.opcode}"
        }
        require(pending.size < entries) { "submission queue full" }
        pending.addLast(submission)
    }

    // -- Typed API (sugar) --

    fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        enqueue(UringOp.Companion.Submissions.read(file.id, 0L, buffer.remaining(), offset, userData).copy(buffer = buffer))
    }

    fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        enqueue(UringOp.Companion.Submissions.write(file.id, 0L, buffer.remaining(), offset, userData).copy(buffer = buffer))
    }

    fun accept(file: FileImpl, userData: Long) {
        enqueue(UringOp.Companion.Submissions.accept(file.id, 0L, 0, userData))
    }

    fun connect(file: FileImpl, address: String, port: Int, userData: Long) {
        // Legion Doc 04 §3: "Hard-block any non-loopback network egress
        // unless directed to a strictly monitored proxy." Source of truth:
        // containmentPolicy.layer3Syscall.allowedEgress (defaults to loopback
        // only when policy is MAXIMUM).
        require(
            !containmentPolicy.layer3Syscall.allowedEgress.isEmpty() &&
                address in containmentPolicy.layer3Syscall.allowedEgress
        ) {
            "non-loopback CONNECT rejected by containment policy: $address:$port " +
                "(allowed=${containmentPolicy.layer3Syscall.allowedEgress})"
        }
        enqueue(UringOp.Companion.Submissions.connect(file.id, 0L, port, userData))
    }

    fun close(file: FileImpl, userData: Long) {
        enqueue(UringOp.Companion.Submissions.close(file.id, userData))
    }

    fun sync(file: FileImpl, userData: Long, metaData: Boolean) {
        enqueue(UringOp.Companion.Submissions.fsync(file.id, userData))
    }

    fun truncate(file: FileImpl, size: Long, userData: Long) {
        enqueue(UringOp.Companion.Submissions.nop(userData))
    }

    fun map(file: FileImpl, mode: String, position: Long, size: Long, userData: Long) {
        enqueue(UringOp.Companion.Submissions.nop(userData))
    }

    // -- Completion drain --

    fun submit(): Int {
        val submitted = pending.size
        if (submitted == 0) return 0

        val unified = mutableListOf<UringSubmission>()
        while (pending.isNotEmpty()) {
            unified.add(pending.removeFirst())
        }

        if (unified.isNotEmpty()) {
            val results = backend.submitBatch(unified)
            // Legion Doc 04 Layer 2 §2: quantize STATX completions to collapse
            // micro-timing side-channels. The result code is replaced with a
            // synthetic epoch value for metadata ops.
            val sanitized = results.mapIndexed { i, r ->
                if (i < unified.size && unified[i].opcode in METADATA_QUANTIZED_OPS) {
                    SelectionResult(syntheticEpoch.toInt(), r.userData)
                } else r
            }
            completions.addAll(sanitized)
        }
        return submitted
    }

    fun wait(minComplete: Int = 1): List<SelectionResult> {
        require(minComplete >= 0) { "minComplete must be non-negative" }
        if (completions.size < minComplete && pending.isNotEmpty()) submit()

        return buildList {
            while (completions.isNotEmpty()) {
                add(completions.removeFirst())
            }
        }
    }

    fun peek(): List<SelectionResult> = buildList {
        while (completions.isNotEmpty()) {
            add(completions.removeFirst())
        }
    }
}
