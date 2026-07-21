package borg.trikeshed.userspace.volume

import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import platform.posix.*
import kotlinx.cinterop.*
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
    val path: String,
    override val blockSize: Int = 4096
) : Volume {

    private val fileOps = PosixFileOperations()
    private val fd: Int

    companion object {
        const val MAX_INFLIGHT = 64
    }

    init {
        fd = fileOps.open(path, readOnly = false)
        if (fd < 0) {
            val file = fopen(path, "wb")
            if (file != null) {
                fclose(file)
            }
            val secondFd = fileOps.open(path, readOnly = false)
            if (secondFd < 0) {
                val err = errno
                throw RuntimeException("Failed to open or create file: $path (errno: $err)")
            }
            this.fd = secondFd
        } else {
            this.fd = fd
        }
    }

    override val capacity: Long
        get() = fileOps.size(fd) / blockSize

    override suspend fun read(lba: Long, count: Int): ByteArray {
        val size = count * blockSize
        if (size == 0) return ByteArray(0)
        
        val reqs = listOf(IoRequest(lba, ByteArray(size), IoKind.READ))
        val res = submitBatch(reqs).first()
        return when (res) {
            is IoResult.Ok -> res.bytes
            is IoResult.Failure -> throw res.cause
            is IoResult.Cancelled -> throw CancellationException("Read cancelled")
        }
    }

    override suspend fun write(lba: Long, data: ByteArray) {
        if (data.isEmpty()) return
        
        val reqs = listOf(IoRequest(lba, data, IoKind.WRITE))
        val res = submitBatch(reqs).first()
        when (res) {
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
                    req.payload.usePinned { pinned ->
                        val pwriteRes = pwrite(fd, pinned.addressOf(0), req.payload.size.convert(), offset.convert())
                        if (pwriteRes < 0L) {
                            results[idx] = IoResult.Failure(RuntimeException("pwrite failed (errno: $errno)"))
                        } else {
                            results[idx] = IoResult.Ok(ByteArray(0))
                        }
                    }
                } else if (req.kind == IoKind.READ) {
                    val size = req.payload.size
                    val buf = ByteArray(size)
                    buf.usePinned { pinned ->
                        val preadRes = pread(fd, pinned.addressOf(0), size.convert(), offset.convert())
                        if (preadRes < 0L) {
                            results[idx] = IoResult.Failure(RuntimeException("pread failed (errno: $errno)"))
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
    
    fun close() {
        if (fd >= 0) {
            fsync(fd)
            fileOps.close(fd)
        }
    }
}
