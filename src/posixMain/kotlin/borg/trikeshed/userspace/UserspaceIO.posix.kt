@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.PosixUringIO
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.seriesOf
import borg.trikeshed.lib.toList
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.open

private class PosixUserspaceChannelBackend(
    private val entries: Int,
) : UserspaceChannelBackend {

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        if (submissions.isEmpty()) return emptyList()
        val results = mutableListOf<SelectionResult>()
        submissions.forEach { sub ->
            when (sub.opcode) {
                UringOp.READ, UringOp.READV -> {
                    val bytes = sub.buffer?.array() ?: return@forEach
                    val start = (sub.buffer?.arrayOffset() ?: 0) + (sub.buffer?.position() ?: 0)
                    val len = sub.buffer?.remaining() ?: 0
                    val n = PosixUringIO.readAt(sub.fd, bytes, start, len, sub.offset, entries)
                    results.add(SelectionResult(n, sub.userData))
                }
                UringOp.WRITE, UringOp.WRITEV -> {
                    val bytes = sub.buffer?.array() ?: return@forEach
                    val start = (sub.buffer?.arrayOffset() ?: 0) + (sub.buffer?.position() ?: 0)
                    val len = sub.buffer?.remaining() ?: 0
                    val n = PosixUringIO.writeAt(sub.fd, bytes, start, len, sub.offset, entries)
                    results.add(SelectionResult(n, sub.userData))
                }
                UringOp.FSYNC -> {
                    val n = PosixUringIO.fsync(sub.fd, entries)
                    results.add(SelectionResult(n, sub.userData))
                }
                UringOp.FTRUNCATE -> {
                    val n = PosixUringIO.ftruncate(sub.fd, sub.offset, entries)
                    results.add(SelectionResult(n, sub.userData))
                }
                UringOp.CLOSE -> {
                    val n = PosixUringIO.closeFd(sub.fd, entries)
                    results.add(SelectionResult(n, sub.userData))
                }
                else -> results.add(SelectionResult(-1, sub.userData))
            }
        }
        return results
    }

    override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> {
        val subs = submissions.toList()
        val res = withContext(Dispatchers.Default) { submitBatch(subs) }
        return seriesOf(res.map { UringCompletion(it.userData, it.res, 0) })
    }
}

actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = PosixUserspaceChannelBackend(entries)

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {
        if (id >= 0) PosixUringIO.closeFd(id)
    }
    actual fun size(): Long = PosixUringIO.fileSize(id)
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl {
        val flags = if (readOnly) O_RDONLY else (O_RDWR or O_CREAT)
        return FileImpl(open(path, flags, 438u))
    }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(platform.posix.socket(domain, type, protocol))
}
