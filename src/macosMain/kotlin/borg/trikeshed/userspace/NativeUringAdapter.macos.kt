package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlinx.cinterop.*
import platform.posix.*

internal actual class NativeUringAdapter actual constructor(entries: Int) {
    actual val capabilities: Long = 0L
    actual val availability: String = "emulated: Darwin has no Linux io_uring kernel"
    actual fun execute(submission: UringSubmission): UringCompletion =
        UringCompletion(submission.userData, -95, 0)
    actual fun fadvise(fd: Int, offset: Long, length: Int, advice: Int): Int {
        if (offset < 0 || length < 0 || advice !in 0..5) return -22
        return when (advice) {
            0 -> {
                val readAhead = posixCompletion(fcntl(fd, F_RDAHEAD, 1))
                if (readAhead < 0) readAhead else posixCompletion(fcntl(fd, F_NOCACHE, 0))
            }
            1 -> posixCompletion(fcntl(fd, F_RDAHEAD, 0))
            2 -> posixCompletion(fcntl(fd, F_RDAHEAD, 1))
            3 -> memScoped {
                val request = alloc<radvisory>()
                request.ra_offset = offset
                request.ra_count = if (length != 0) length else {
                    val metadata = alloc<stat>()
                    val status = posixCompletion(fstat(fd, metadata.ptr))
                    if (status < 0) return@memScoped status
                    (metadata.st_size - offset).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                }
                posixCompletion(fcntl(fd, F_RDADVISE, request.ptr))
            }
            else -> posixCompletion(fcntl(fd, F_NOCACHE, 1))
        }
    }
    actual fun registerBuffers(buffers: borg.trikeshed.lib.Series<MemoryMapping>): Result<Unit> = unsupported()
    actual fun unregisterBuffers(): Result<Unit> = unsupported()
    actual fun close() {}
}
