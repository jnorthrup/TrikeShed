package borg.trikeshed.userspace.volume

import borg.trikeshed.lib.Closeable
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.openUserspaceChannelBackend
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

data class LiburingVolumeCompletion(
    val opcode: UringOp,
    val byteOffset: Long,
    val byteLength: Int,
    val userData: Long,
    val submitted: Int,
    val res: Int,
)

actual class LiburingVolume actual constructor(
    val path: String,
    actual override val blockSize: Int,
    capacityBytes: Long,
) : Volume, Closeable {
    private val lock = SynchronizedObject()
    private val totalBytes = checkedCapacityBytes(capacityBytes, blockSize)
    private val facade = FunctionalUringFacade(64, openUserspaceChannelBackend(64))
    private val completions = ArrayList<LiburingVolumeCompletion>()
    private var fd = -1
    private var isClosed = false
    private var nextUserData = 1L

    actual override val capacity: Long = totalBytes / blockSize

    init {
        try {
            val opened = submitOne(UringOp.OPENAT, 0L, path.encodeToByteArray().size) { token ->
                facade.enqueue(Submissions.openat(path, O_RDWR or O_CREAT, token))
            }
            if (opened < 0) throw VolumeException("open failed: $opened")
            fd = opened
            val res = submitOne(UringOp.FTRUNCATE, 0L, 0) { token ->
                facade.enqueue(UringSubmission(UringOp.FTRUNCATE, fd, 0, 0, totalBytes, userData = token))
            }
            if (res != 0) throw VolumeException("ftruncate failed: $res")
        } catch (failure: Throwable) {
            runCatching {
                if (fd >= 0) submitOne(UringOp.CLOSE, 0L, 0) { token ->
                    facade.enqueue(Submissions.close(fd, token))
                }
            }
            throw failure
        }
    }

    fun ioReceipts(): List<LiburingVolumeCompletion> = synchronized(lock) { completions.toList() }

    actual override suspend fun read(lba: Long, count: Int): ByteArray = synchronized(lock) {
        checkOpen()
        require(count >= 0) { "count must be non-negative" }
        val byteLength = byteCount(count)
        checkBounds(lba, byteLength)
        val target = ByteArray(byteLength)
        var copied = 0
        while (copied < byteLength) {
            val offset = lba * blockSize + copied
            val buffer = ByteBuffer.wrap(target, copied, byteLength - copied)
            val res = submitOne(UringOp.READ, offset, byteLength - copied) { token ->
                facade.enqueue(Submissions.read(fd, 0L, buffer.remaining(), offset, token).copy(buffer = buffer))
            }
            if (res <= 0) {
                throw VolumeException("short read at $offset: res=$res remaining=${byteLength - copied}")
            }
            copied += res
        }
        target
    }

    actual override suspend fun write(lba: Long, data: ByteArray): Unit = synchronized(lock) {
        checkOpen()
        require(data.size % blockSize == 0) {
            "write size ${data.size} is not a multiple of blockSize $blockSize"
        }
        checkBounds(lba, data.size)
        var written = 0
        while (written < data.size) {
            val offset = lba * blockSize + written
            val buffer = ByteBuffer.wrap(data, written, data.size - written)
            val res = submitOne(UringOp.WRITE, offset, data.size - written) { token ->
                facade.enqueue(Submissions.write(fd, 0L, buffer.remaining(), offset, token).copy(buffer = buffer))
            }
            if (res <= 0) {
                throw VolumeException("short write at $offset: res=$res remaining=${data.size - written}")
            }
            if (res > data.size - written) {
                throw VolumeException("oversized write at $offset: res=$res remaining=${data.size - written}")
            }
            written += res
        }
    }

    actual override suspend fun sync(): Unit = synchronized(lock) {
        checkOpen()
        val res = submitOne(UringOp.FSYNC, 0L, 0) { token ->
            facade.enqueue(Submissions.fsync(fd, token))
        }
        if (res != 0) throw VolumeException("fsync failed: $res")
    }

    actual override fun close() {
        synchronized(lock) {
            if (!isClosed) {
                val res = submitOne(UringOp.CLOSE, 0L, 0) { token ->
                    facade.enqueue(Submissions.close(fd, token))
                }
                if (res != 0) throw VolumeException("close failed: $res")
                fd = -1
                isClosed = true
            }
        }
    }

    private fun byteCount(count: Int): Int {
        val bytes = count.toLong() * blockSize
        require(bytes <= Int.MAX_VALUE) { "read byte count exceeds ByteArray capacity: $bytes" }
        return bytes.toInt()
    }

    private fun checkBounds(lba: Long, byteLength: Int) {
        require(lba >= 0) { "lba must be non-negative" }
        require(lba <= Long.MAX_VALUE / blockSize) { "lba byte offset overflows Long" }
        val byteOffset = lba * blockSize
        require(byteOffset <= totalBytes && byteLength <= totalBytes - byteOffset) {
            "volume access out of bounds: offset=$byteOffset length=$byteLength capacityBytes=$totalBytes"
        }
    }

    private fun checkOpen() {
        check(!isClosed && fd >= 0) { "Volume is closed" }
    }

    private inline fun submitOne(
        opcode: UringOp,
        byteOffset: Long,
        byteLength: Int,
        enqueue: (Long) -> Unit,
    ): Int {
        val token = nextUserData++
        enqueue(token)
        val submitted = facade.submit()
        val result = facade.wait(minComplete = 1).singleCompletion(token)
        completions += LiburingVolumeCompletion(opcode, byteOffset, byteLength, token, submitted, result.res)
        return result.res
    }

    private fun List<SelectionResult>.singleCompletion(userData: Long): SelectionResult {
        val result = firstOrNull { it.userData == userData }
            ?: throw VolumeException("missing completion for userData=$userData")
        check(size == 1) { "unexpected extra completions while awaiting userData=$userData: $this" }
        return result
    }

    private companion object {
        const val O_RDWR: Int = 2
        const val O_CREAT: Int = 64

        fun checkedCapacityBytes(capacityBytes: Long, blockSize: Int): Long {
            require(blockSize > 0) { "blockSize must be positive" }
            require(capacityBytes >= 0L) { "capacityBytes must be non-negative" }
            require(capacityBytes % blockSize == 0L) {
                "capacity $capacityBytes not aligned to blockSize $blockSize"
            }
            return capacityBytes
        }
    }
}
