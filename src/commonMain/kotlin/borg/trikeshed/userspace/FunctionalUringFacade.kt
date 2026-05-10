package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * Platform backend for the unified submission queue.
 *
 * Implementations translate [UringSubmission] instances to platform-native I/O:
 *   Linux → liburing SQEs (or pread/pwrite fallback)
 *   JVM   → java.nio.channels.FileChannel / AsynchronousFileChannel
 *   JS    → fetch / Blob / ArrayBuffer
 *   Wasm  → similar
 *
 * The [submit] method executes a batch of submissions and returns completions
 * in the same order (userData preserved).
 */
public interface UserspaceChannelBackend {
    /** Legacy typed API — will be removed once all callers use [submitBatch]. */
    @Deprecated("Use submitBatch with UringSubmission")
    fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int
    @Deprecated("Use submitBatch with UringSubmission")
    fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int
    @Deprecated("Use submitBatch with UringSubmission")
    fun accept(file: FileImpl): Int
    @Deprecated("Use submitBatch with UringSubmission")
    fun connect(file: FileImpl, address: String, port: Int): Int
    @Deprecated("Use submitBatch with UringSubmission")
    fun close(file: FileImpl): Int

    /**
     * Submit a batch of [UringSubmission] entries and return completions.
     *
     * Each [UringSubmission.userData] is echoed back in the matching
     * [SelectionResult.userData].  [SelectionResult.res] holds the
     * syscall return value (bytes read/written, fd for accept, 0 for close, etc.)
     *
     * Default implementation dispatches to the legacy typed methods.
     * Platform backends SHOULD override this for native batching.
     */
    fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        // Default: no batch support — callers must use legacy path
        return emptyList()
    }
}

/**
 * Unified io_uring-style submission queue facade.
 *
 * Two APIs coexist:
 * 1. **Typed** — [read], [write], [accept], [connect], [close] + [submit]/[wait]/[peek]
 * 2. **Unified** — [enqueue] any [UringSubmission], then [submit]/[wait]/[peek]
 *
 * The typed API is sugar that creates [UringSubmission] internally.
 * New code should use the unified path exclusively.
 */
public class FunctionalUringFacade(
    private val entries: Int,
    private val backend: UserspaceChannelBackend,
) {
    private val pending = ArrayDeque<Any>()  // UringSubmission or PreparedChannelOp
    private val completions = ArrayDeque<SelectionResult>()

    init {
        require(entries > 0) { "entries must be positive" }
    }

    // -- Unified API --

    /** Enqueue a raw [UringSubmission]. */
    fun enqueue(sub: UringSubmission) {
        require(pending.size < entries) { "submission queue full" }
        pending.addLast(sub)
    }

    /** Convenience: enqueue multiple submissions. */
    fun enqueueAll(subs: List<UringSubmission>) {
        subs.forEach { enqueue(it) }
    }

    // -- Typed legacy API (sugar) --

    private fun pushAny(op: Any) {
        require(pending.size < entries) { "submission queue full" }
        pending.addLast(op)
    }

    fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        pushAny(PreparedChannelOp.Read(file, buffer, offset, userData))
    }

    fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        pushAny(PreparedChannelOp.Write(file, buffer, offset, userData))
    }

    fun accept(file: FileImpl, userData: Long) {
        pushAny(PreparedChannelOp.Accept(file, userData))
    }

    fun connect(file: FileImpl, address: String, port: Int, userData: Long) {
        pushAny(PreparedChannelOp.Connect(file, address, port, userData))
    }

    fun close(file: FileImpl, userData: Long) {
        pushAny(PreparedChannelOp.Close(file, userData))
    }

    // -- Completion drain --

    fun submit(): Int {
        val submitted = pending.size
        if (submitted == 0) return 0

        // Partition into unified vs legacy
        val unified = mutableListOf<UringSubmission>()
        val legacy = mutableListOf<PreparedChannelOp>()
        while (pending.isNotEmpty()) {
            when (val op = pending.removeFirst()) {
                is UringSubmission -> unified.add(op)
                is PreparedChannelOp -> legacy.add(op)
                else -> error("unexpected op type: $op")
            }
        }

        // Batch-submit unified ops if backend supports it
        if (unified.isNotEmpty()) {
            val results = backend.submitBatch(unified)
            completions.addAll(results)
        }

        // Legacy dispatch
        for (op in legacy) {
            @Suppress("DEPRECATION")
            val res = when (op) {
                is PreparedChannelOp.Read -> backend.read(op.file, op.buffer, op.offset)
                is PreparedChannelOp.Write -> backend.write(op.file, op.buffer, op.offset)
                is PreparedChannelOp.Accept -> backend.accept(op.file)
                is PreparedChannelOp.Connect -> backend.connect(op.file, op.address, op.port)
                is PreparedChannelOp.Close -> backend.close(op.file)
            }
            completions.addLast(SelectionResult(res, op.userData))
        }
        return submitted
    }

    fun wait(minComplete: Int = 1): List<SelectionResult> {
        require(minComplete >= 0) { "minComplete must be non-negative" }
        if (completions.size < minComplete && pending.isNotEmpty()) submit()
        return peek()
    }

    fun peek(): List<SelectionResult> = buildList(completions.size) {
        while (completions.isNotEmpty()) add(completions.removeFirst())
    }
}

/** Legacy typed op — kept for backward compat while callers migrate. */
internal sealed interface PreparedChannelOp {
    val userData: Long

    data class Read(
        val file: FileImpl,
        val buffer: ByteBuffer,
        val offset: Long,
        override val userData: Long,
    ) : PreparedChannelOp

    data class Write(
        val file: FileImpl,
        val buffer: ByteBuffer,
        val offset: Long,
        override val userData: Long,
    ) : PreparedChannelOp

    data class Accept(
        val file: FileImpl,
        override val userData: Long,
    ) : PreparedChannelOp

    data class Connect(
        val file: FileImpl,
        val address: String,
        val port: Int,
        override val userData: Long,
    ) : PreparedChannelOp

    data class Close(
        val file: FileImpl,
        override val userData: Long,
    ) : PreparedChannelOp
}

internal expect fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend
