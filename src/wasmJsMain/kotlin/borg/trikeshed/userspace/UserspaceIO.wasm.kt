package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = false
    actual fun close() {}
    actual fun size(): Long = -1L
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl =
        throw UnsupportedOperationException("Wasm host file primitives are unavailable")
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl =
        throw UnsupportedOperationException("Wasm host socket primitives are unavailable")
}

private class WasmUserspaceChannelBackend : UserspaceChannelBackend {
    override val capabilities: Long get() = UringOp.NOP.mask
    override val availability: String get() = "emulated: restricted Wasm host; file primitives unavailable"
    private var closed = false
    private fun execute(sub: UringSubmission): Int = when {
        closed -> -9
        sub.flags != 0 || sub.opcode != UringOp.NOP -> -95
        else -> 0
    }
    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> =
        submissions.map { SelectionResult(execute(it), it.userData) }
    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val results = Array(submissions.size) { index ->
            val sub = submissions[index]
            UringCompletion(sub.userData, execute(sub), 0)
        }
        return results.size j { results[it] }
    }
    override fun close() { closed = true }
}

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend {
    require(entries > 0)
    return WasmUserspaceChannelBackend()
}
