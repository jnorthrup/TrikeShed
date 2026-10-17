package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.wasi.Wasi

actual class FileImpl actual constructor(actual val id: Int) {
    private var closed = id < 0
    actual fun isOpen(): Boolean = !closed && Wasi.isOpen(id)
    actual fun close() {
        if (!closed) { closed = true; Wasi.close(id) }
    }
    actual fun size(): Long = if (closed) -1L else Wasi.stat(id)?.size ?: -1L
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl {
        val fd = Wasi.open(path.encodeToByteArray(), if (readOnly) UringOp.O_RDONLY else UringOp.O_RDWR or UringOp.O_CREAT)
        if (fd < 0) throw IllegalStateException("open($path) errno=${-fd}")
        return FileImpl(fd)
    }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl =
        throw UnsupportedOperationException("WASI preview 1 creates no sockets")
}

/**
 * The WASI ring: an emulated ring whose SQEs this process services through WASI preview 1 host calls, one CQE per SQE
 * before submitBatch returns. WASI has no kernel ring, so NATIVE is refused. A READ that fills less than its block zeroes
 * only the block's unread tail; a full block is handed back untouched.
 */
internal class WasiUserspaceChannelBackend(val entries: Int) : UserspaceChannelBackend {
    override val capabilities: Long = UringOp.caps(UringOp.NOP, UringOp.OPENAT, UringOp.READ, UringOp.WRITE,
        UringOp.FSYNC, UringOp.CLOSE)
    override val availability: String get() = "emulated: WASI preview 1 file effects; no kernel ring on WASI"
    private var closed = false

    private fun execute(sub: UringSubmission): Int {
        if (closed) return -9
        if (sub.flags != 0) return -95
        return when (sub.opcode) {
            UringOp.NOP -> 0
            UringOp.OPENAT -> {
                if (sub.fd != UringOp.AT_FDCWD) return -95
                val bytes = sub.buffer ?: return -14
                val start = bytes.arrayOffset() + bytes.position()
                Wasi.open(bytes.array().copyOfRange(start, start + sub.len), sub.offset.toInt())
            }
            UringOp.READ -> {
                val buffer = sub.buffer ?: return -14
                val start = buffer.arrayOffset() + buffer.position()
                val n = Wasi.pread(sub.fd, buffer.array(), start, sub.len, sub.offset)
                buffer.array().zeroTail(start, n, start + sub.len)
                if (n > 0) buffer.position(buffer.position() + n)
                n
            }
            UringOp.WRITE -> {
                val buffer = sub.buffer ?: return -14
                val n = Wasi.pwrite(sub.fd, buffer.array(), buffer.arrayOffset() + buffer.position(), sub.len, sub.offset)
                if (n > 0) buffer.position(buffer.position() + n)
                n
            }
            UringOp.FSYNC -> Wasi.datasync(sub.fd)
            UringOp.CLOSE -> Wasi.close(sub.fd)
            else -> -95
        }
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

actual fun openUserspaceChannelBackend(entries: Int, mode: UringBackendMode?): UserspaceChannelBackend {
    require(entries > 0)
    check(mode != UringBackendMode.NATIVE) { "Native io_uring required: WASI has no kernel ring" }
    return WasiUserspaceChannelBackend(entries)
}
