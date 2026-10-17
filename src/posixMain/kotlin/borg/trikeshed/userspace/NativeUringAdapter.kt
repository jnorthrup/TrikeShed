package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

/** A runtime adapter of the backend [mode] requests (null: TRIKESHED_URING_MODE), owned by a single common backend. */
internal expect class NativeUringAdapter(entries: Int, mode: UringBackendMode?) {
    val capabilities: Long
    val availability: String
    /** The errno, in Linux numbering, of a kernel ring setup that failed; null when none was attempted or it succeeded. */
    val setupErrno: Int?
    fun submit(submissions: List<UringSubmission>)
    fun isPending(userData: Long): Boolean
    fun reapCompletions(): List<UringCompletion>
    /** Blocks up to [timeoutMicros] for a kernel CQE while requests are in flight, leaving it for [reapCompletions]. */
    fun awaitCompletion(timeoutMicros: Long)
    /**
     * One kernel enter for [submissions], with no request of this adapter in flight: each SQE's user_data is its index,
     * every CQE is reaped before this returns and its res lands in [results] at that index. False, with nothing
     * submitted, when the adapter cannot take the batch that way (no kernel ring, requests in flight, a full SQ, an op
     * the kernel lacks).
     */
    fun executeBatch(submissions: List<UringSubmission>, results: IntArray): Boolean
    fun cancelPending(userData: Set<Long>? = null)
    fun fadvise(fd: Int, offset: Long, length: Int, advice: Int): Int
    fun registerBuffers(buffers: borg.trikeshed.lib.Series<MemoryMapping>): Result<Unit>
    fun unregisterBuffers(): Result<Unit>
    fun close()
}
