package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

internal actual class NativeUringAdapter actual constructor(entries: Int) {
    actual val capabilities: Long = 0L
    actual val availability: String = "emulated: Darwin has no Linux io_uring kernel"
    actual fun execute(submission: UringSubmission): UringCompletion =
        UringCompletion(submission.userData, -95, 0)
    actual fun registerBuffers(buffers: borg.trikeshed.lib.Series<MemoryMapping>): Result<Unit> = unsupported()
    actual fun unregisterBuffers(): Result<Unit> = unsupported()
    actual fun close() {}
}
