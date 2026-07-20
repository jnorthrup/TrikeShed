package borg.trikeshed.volume

import borg.trikeshed.userspace.volume.Volume
import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import borg.trikeshed.lib.Closeable
import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class IoKind { READ, WRITE, CANCEL }

data class IoRequest(val lba: Long, val payload: ByteArray, val kind: IoKind)

sealed class IoResult {
    data class Ok(val bytes: ByteArray) : IoResult()
    data class Failure(val cause: Throwable) : IoResult()
    data object Cancelled : IoResult()
}

class LiburingVolume(
    path: String,
    override val blockSize: Int = 4096,
    capacityBytes: Long = blockSize.toLong() * 1024L
) : Volume, Closeable {

    override val capacity: Long = capacityBytes
    private val fd: Int
    private val fileOps = PosixFileOperations()

    companion object {
        const val MAX_INFLIGHT = 64
    }

    init {
        require(capacityBytes % blockSize == 0L) {
            "capacity $capacityBytes not aligned to blockSize $blockSize"
        }

        fd = fileOps.open(path, readOnly = false)
        if (fd < 0) {
            throw RuntimeException("Failed to open file: $path (errno: $errno)")
        }

        if (ftruncate(fd, capacityBytes) != 0) {
            val err = errno
            fileOps.close(fd)
            throw RuntimeException("ftruncate failed (errno: $err)")
        }
    }

    override suspend fun read(lba: Long, count: Int): ByteArray {
        val reqs = listOf(IoRequest(lba, ByteArray(count * blockSize), IoKind.READ))
        val res = submitBatch(reqs).first()
        return when(res) {
            is IoResult.Ok -> res.bytes
            is IoResult.Failure -> throw res.cause
            is IoResult.Cancelled -> throw CancellationException("Read cancelled")
        }
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        val reqs = listOf(IoRequest(lba, data, IoKind.WRITE))
        val res = submitBatch(reqs).first()
        when(res) {
            is IoResult.Ok -> return
            is IoResult.Failure -> throw res.cause
            is IoResult.Cancelled -> throw CancellationException("Write cancelled")
        }
    }

    override suspend fun sync() {
        if (fsync(fd) != 0) {
            throw RuntimeException("fsync failed (errno: $errno)")
        }
    }

    suspend fun submitBatch(requests: List<IoRequest>): List<IoResult> {
        val results = MutableList<IoResult?>(requests.size) { null }

        for ((idx, req) in requests.withIndex()) {
            if (idx >= MAX_INFLIGHT) {
                if (results[idx] == null) {
                    results[idx] = IoResult.Failure(IllegalStateException("submit backpressure"))
                }
                continue
            }

            try {
                // Check for cancellation
                currentCoroutineContext().ensureActive()

                val offset = req.lba * blockSize

                if (req.kind == IoKind.WRITE) {
                    if (req.payload.size > blockSize) {
                        results[idx] = IoResult.Failure(IllegalArgumentException("write ${req.payload.size} > blockSize $blockSize"))
                    } else {
                        req.payload.usePinned { pinned ->
                            val pwriteRes = pwrite(fd, pinned.addressOf(0), req.payload.size.convert(), offset.convert())
                            if (pwriteRes < 0L) {
                                results[idx] = IoResult.Failure(RuntimeException("pwrite failed"))
                            } else {
                                results[idx] = IoResult.Ok(ByteArray(0))
                            }
                        }
                    }
                } else {
                    val size = req.payload.size.coerceAtLeast(blockSize)
                    val buf = ByteArray(size)
                    buf.usePinned { pinned ->
                        val preadRes = pread(fd, pinned.addressOf(0), size.convert(), offset.convert())
                        if (preadRes < 0L) {
                            results[idx] = IoResult.Failure(RuntimeException("pread failed"))
                        } else {
                            results[idx] = IoResult.Ok(buf)
                        }
                    }
                }

            } catch (e: CancellationException) {
                results[idx] = IoResult.Cancelled
            }
        }

        return results.requireNoNulls()
    }

    override fun close() {
        if (fd >= 0) {
            fsync(fd)
            fileOps.close(fd)
        }
    }
}
