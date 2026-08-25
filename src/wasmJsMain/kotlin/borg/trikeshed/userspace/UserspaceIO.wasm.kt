package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer

private object WasmFileRegistry {
    private var nextId = 1
    fun open(): FileImpl = FileImpl(nextId++)
}

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {}
    actual fun size(): Long = -1L
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl = WasmFileRegistry.open()
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(-1)
}

private class WasmUserspaceChannelBackend : UserspaceChannelBackend {
    override fun submitBatch(submissions: List<UringOp.Companion.UringSubmission>): List<SelectionResult> = emptyList()
    override suspend fun batchEnqueue(
        submissions: borg.trikeshed.lib.Series<UringOp.Companion.UringSubmission>,
    ): borg.trikeshed.lib.Series<UringCompletion> =
        @Suppress("UNCHECKED_CAST") (borg.trikeshed.lib.EmptySeries as borg.trikeshed.lib.Series<UringCompletion>)
}

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = WasmUserspaceChannelBackend()

