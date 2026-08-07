@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.PosixUringIO
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.open

private class PosixUserspaceChannelBackend(
    private val entries: Int,
) : UserspaceChannelBackend {
        return PosixUringIO.mmap(0, size.toInt(), prot, flags, file.id, position).toInt()
    }

    override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
        if (submissions.isEmpty()) return emptyList()
        val results = mutableListOf<SelectionResult>()
        submissions.forEach { sub ->
            when (sub.opcode) {
                UringOp.READV -> {
                    val bytes = sub.buffer?.array() ?: return@forEach
                    val start = (sub.buffer?.arrayOffset() ?: 0) + (sub.buffer?.position() ?: 0)
                    val len = sub.buffer?.remaining() ?: 0
                    val n = PosixUringIO.readAt(sub.fd, bytes, start, len, sub.offset, entries)
                    results.add(SelectionResult(n, sub.userData))
                }
                UringOp.WRITEV -> {
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
