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
) {
    private val pending = ArrayDeque<UringSubmission>()
    private val completions = ArrayDeque<SelectionResult>()

    init {
        require(entries > 0) { "entries must be positive" }
    }

    // -- Unified UringSubmission API --

    /**
     * Enqueue a raw io_uring submission.
     * Throws if the queue is full.
     */
    fun enqueue(submission: UringSubmission) {
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
            completions.addAll(results)
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
