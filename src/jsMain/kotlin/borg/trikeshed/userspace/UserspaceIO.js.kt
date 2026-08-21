package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {}
    actual fun size(): Long = -1L
}

internal actual object FilesImpl {
    private var nextId = 1
    actual fun open(path: String, readOnly: Boolean): FileImpl {
        return FileImpl(nextId++)
    }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(-1)
}

private class JsUserspaceChannelBackend : UserspaceChannelBackend {
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = emptyList()
    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = borg.trikeshed.lib.EmptySeries as Series<UringCompletion>
}

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = JsUserspaceChannelBackend()
