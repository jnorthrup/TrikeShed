package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

/** A runtime adapter, owned by a single common submission/completion backend. */
internal expect class NativeUringAdapter(entries: Int) {
    val capabilities: Long
    val availability: String
    fun execute(submission: UringSubmission): UringCompletion
    fun fadvise(fd: Int, offset: Long, length: Int, advice: Int): Int
    fun registerBuffers(buffers: borg.trikeshed.lib.Series<MemoryMapping>): Result<Unit>
    fun unregisterBuffers(): Result<Unit>
    fun close()
}
