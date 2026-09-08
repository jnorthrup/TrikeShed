package borg.trikeshed.btrfs

import borg.trikeshed.lib.Closeable
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.ebpf.UringEbpfProgram
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.spi.NioCapabilityReport
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BtrfsVolumeIoCompletion(
    val opcode: UringOp,
    val byteOffset: Long,
    val byteLength: Int,
    val userData: Long,
    val submitted: Int,
    val res: Int,
)

data class BtrfsUringChannelReport(
    val availability: String,
    val capabilities: Long,
    val nativeCapabilities: Long,
)

class BtrfsImageIoException(
    message: String,
    val completions: List<BtrfsVolumeIoCompletion>,
) : IllegalStateException(message)

/**
 * File-backed userspace NIO [Volume] for Btrfs image work.
 *
 * [open] is the connected path: the caller supplies the already-selected
 * channel, so OPENAT, FTRUNCATE, READ, WRITE, FSYNC, and CLOSE cross the same
 * SQE/CQE boundary. The public constructor is retained as a compatibility
 * path for older call sites that still hand over a pre-sized file.
 * The supplied channel is dedicated to this volume's request identities;
 * its caller drains the channel after the volume. Compatibility instances
 * own and close their internally created channel.
 */
class BtrfsUringFileVolume private constructor(
    val imagePath: String,
    override val blockSize: Int,
    override val capacity: Long,
    private val channel: UringChannel,
    private val file: File?,
    private var fd: Int,
    val backendReport: NioCapabilityReport,
    val channelReport: BtrfsUringChannelReport?,
) : Volume, Closeable {
    constructor(
        imagePath: String,
        blockSize: Int = NioBtrfsGraalBlobStore.SECTOR_MIN.toInt(),
        capacity: Long,
        entries: Int = 8,
        ebpfPrograms: List<UringEbpfProgram> = emptyList(),
    ) : this(
        imagePath = imagePath,
        blockSize = blockSize,
        capacity = capacity,
        file = openPreSizedFile(imagePath, blockSize, capacity),
        channel = UringChannels.open(entries, ebpfPrograms),
        fd = -1,
        backendReport = currentNioCapabilityReport(),
        channelReport = null,
    )

    companion object {
        private const val O_RDWR: Int = 2
        private const val O_CREAT: Int = 64

        suspend fun open(
            channel: UringChannel,
            imagePath: String,
            blockSize: Int = NioBtrfsGraalBlobStore.SECTOR_MIN.toInt(),
            capacity: Long,
            create: Boolean = true,
            resize: Boolean = true,
            backendReport: NioCapabilityReport,
            channelReport: BtrfsUringChannelReport? = null,
        ): BtrfsUringFileVolume {
            val volume = BtrfsUringFileVolume(
                imagePath = imagePath,
                blockSize = blockSize,
                capacity = capacity,
                channel = channel,
                file = null,
                fd = -1,
                backendReport = backendReport,
                channelReport = channelReport,
            )
            try {
                val flags = O_RDWR or if (create) O_CREAT else 0
                val opened = volume.submitOneAwait(
                    opcode = UringOp.OPENAT,
                    byteOffset = 0L,
                    byteLength = imagePath.encodeToByteArray().size,
                ) { token -> Submissions.openat(imagePath, flags, token) }
                if (opened < 0) {
                    throw BtrfsImageIoException("OPENAT failed for $imagePath: res=$opened", volume.ioReceipts())
                }
                volume.fd = opened
                if (resize) {
                    val truncated = volume.submitOneAwait(UringOp.FTRUNCATE, 0L, 0) { token ->
                        UringSubmission(UringOp.FTRUNCATE, opened, 0, 0, volume.capacityBytes, userData = token)
                    }
                    if (truncated != 0) {
                        throw BtrfsImageIoException("FTRUNCATE failed for $imagePath: res=$truncated", volume.ioReceipts())
                    }
                }
                return volume
            } catch (failure: Throwable) {
                runCatching { volume.drain() }
                throw failure
            }
        }

        private fun checkedCapacityBytes(capacity: Long, blockSize: Int): Long {
            require(blockSize > 0) { "blockSize must be positive" }
            require(capacity >= 0) { "capacity must be non-negative" }
            require(capacity <= Long.MAX_VALUE / blockSize) { "volume byte capacity overflows Long" }
            return capacity * blockSize
        }

        private fun openPreSizedFile(imagePath: String, blockSize: Int, capacity: Long): File {
            val requiredBytes = checkedCapacityBytes(capacity, blockSize)
            val opened = Files.open(imagePath, readOnly = false)
            try {
                val size = opened.size()
                require(size >= requiredBytes) {
                    "image file must be pre-sized: $imagePath size=$size required=$requiredBytes"
                }
                return opened
            } catch (failure: Throwable) {
                runCatching { opened.close() }
                throw failure
            }
        }
    }

    private val lock = Mutex()
    private val completions = ArrayList<BtrfsVolumeIoCompletion>()
    private val capacityBytes: Long = checkedCapacityBytes(capacity, blockSize)
    private var closed = false
    private var nextUserData = 1L

    fun ioReceipts(): List<BtrfsVolumeIoCompletion> = completions.toList()

    override suspend fun read(lba: Long, count: Int): ByteBuffer = lock.withLock {
        requireOpen()
        require(count >= 0) { "count must be non-negative" }
        val byteLength = byteCount(count)
        val byteOffset = checkedByteOffset(lba, byteLength)
        val target = ByteArray(byteLength)
        var copied = 0
        while (copied < byteLength) {
            val offset = byteOffset + copied
            val buffer = ByteBuffer.wrap(target, copied, byteLength - copied)
            val res = if (fd >= 0) {
                submitOneAwait(UringOp.READ, offset, byteLength - copied) { token ->
                    Submissions.read(fd, 0L, buffer.remaining(), offset, token).copy(buffer = buffer)
                }
            } else {
                submitOne(UringOp.READ, offset, byteLength - copied) { token ->
                    channel.read(requireNotNull(file), buffer, offset, token)
                }
            }
            if (res <= 0) {
                throw BtrfsImageIoException(
                    "short READ at $offset: res=$res remaining=${byteLength - copied}",
                    ioReceipts(),
                )
            }
            copied += res
        }
        ByteBuffer.wrap(target)
    }

    override suspend fun write(lba: Long, data: ByteBuffer): Unit = lock.withLock {
        requireOpen()
        val startPosition = data.position()
        val total = data.remaining()
        val byteOffset = checkedByteOffset(lba, total)
        var written = 0
        while (written < total) {
            val source = ByteBuffer.wrap(data.array(), data.arrayOffset() + data.position(), data.remaining())
            val offset = byteOffset + written
            val res = if (fd >= 0) {
                submitOneAwait(UringOp.WRITE, offset, data.remaining()) { token ->
                    Submissions.write(fd, 0L, source.remaining(), offset, token).copy(buffer = source)
                }
            } else {
                submitOne(UringOp.WRITE, offset, data.remaining()) { token ->
                    channel.write(requireNotNull(file), source, offset, token)
                }
            }
            if (res <= 0) {
                data.position(startPosition + written)
                throw BtrfsImageIoException(
                    "short WRITE at $offset: res=$res remaining=${total - written}",
                    ioReceipts(),
                )
            }
            if (res > total - written) {
                data.position(startPosition + written)
                throw BtrfsImageIoException(
                    "oversized WRITE completion at $offset: res=$res remaining=${total - written}",
                    ioReceipts(),
                )
            }
            written += res
            data.position(startPosition + written)
        }
    }

    override suspend fun sync(): Unit = lock.withLock {
        requireOpen()
        val res = if (fd >= 0) {
            submitOneAwait(UringOp.FSYNC, 0L, 0) { token ->
                Submissions.fsync(fd, token)
            }
        } else {
            submitOne(UringOp.FSYNC, 0L, 0) { token ->
                channel.sync(requireNotNull(file), token, metaData = true)
            }
        }
        if (res != 0) {
            throw BtrfsImageIoException("FSYNC failed for $imagePath: res=$res", ioReceipts())
        }
    }

    suspend fun drain(): Unit = withContext(NonCancellable) { lock.withLock {
        if (!closed) {
            val res = when {
                fd >= 0 -> submitOneAwait(UringOp.CLOSE, 0L, 0) { token -> Submissions.close(fd, token) }
                file != null -> submitOne(UringOp.CLOSE, 0L, 0) { token ->
                    channel.close(file, token)
                }
                else -> 0 // OPENAT failed before a descriptor was acquired.
            }
            if (res != 0) {
                throw BtrfsImageIoException("CLOSE failed for $imagePath: res=$res", ioReceipts())
            }
            fd = -1
            closed = true
        }
        if (file != null) channel.closeNow()
    } }

    override fun close() {
        if (closed) {
            if (file != null) channel.closeNow()
            return
        }
        if (fd >= 0) {
            throw IllegalStateException("suspend drain() is required for fd-backed BtrfsUringFileVolume")
        }
        val res = submitOne(UringOp.CLOSE, 0L, 0) { token ->
            channel.close(requireNotNull(file), token)
        }
        if (res != 0) {
            throw BtrfsImageIoException("CLOSE failed for $imagePath: res=$res", ioReceipts())
        }
        closed = true
        channel.closeNow()
    }

    private fun byteCount(count: Int): Int {
        val bytes = count.toLong() * blockSize
        require(bytes <= Int.MAX_VALUE) { "read byte count exceeds ByteBuffer capacity: $bytes" }
        return bytes.toInt()
    }

    private fun checkedByteOffset(lba: Long, byteLength: Int): Long {
        require(lba >= 0) { "lba must be non-negative" }
        require(lba <= Long.MAX_VALUE / blockSize) { "lba byte offset overflows Long" }
        val byteOffset = lba * blockSize
        require(byteOffset <= capacityBytes && byteLength <= capacityBytes - byteOffset) {
            "volume access out of bounds: offset=$byteOffset length=$byteLength capacityBytes=$capacityBytes"
        }
        return byteOffset
    }

    private fun requireOpen() {
        check(!closed && (fd >= 0 || file?.isOpen() == true)) { "BtrfsUringFileVolume is closed" }
    }

    private inline fun submitOne(
        opcode: UringOp,
        byteOffset: Long,
        byteLength: Int,
        enqueue: (Long) -> Unit,
    ): Int {
        val token = nextUserData++
        enqueue(token)
        val submitted = channel.submit()
        val result = channel.wait(minComplete = 1).singleCompletion(token)
        completions += BtrfsVolumeIoCompletion(opcode, byteOffset, byteLength, token, submitted, result.res)
        return result.res
    }

    private suspend inline fun submitOneAwait(
        opcode: UringOp,
        byteOffset: Long,
        byteLength: Int,
        submission: (Long) -> UringSubmission,
    ): Int {
        val token = nextUserData++
        val result = channel.batchEnqueue(listOf(submission(token)).toSeries()).singleCompletion(token)
        completions += BtrfsVolumeIoCompletion(
            opcode = opcode,
            byteOffset = byteOffset,
            byteLength = byteLength,
            userData = token,
            submitted = 1,
            res = result.res,
        )
        return result.res
    }

    private fun List<SelectionResult>.singleCompletion(userData: Long): SelectionResult {
        val result = firstOrNull { it.userData == userData }
            ?: throw BtrfsImageIoException("missing completion for userData=$userData", ioReceipts())
        check(size == 1) { "unexpected extra completions while awaiting userData=$userData: $this" }
        return result
    }

    private fun borg.trikeshed.lib.Series<UringCompletion>.singleCompletion(userData: Long): UringCompletion {
        check(size == 1) { "unexpected completion count while awaiting userData=$userData: $size" }
        val result = this[0]
        check(result.userData == userData) { "unexpected completion userData=${result.userData}, expected=$userData" }
        return result
    }
}
