package borg.trikeshed.btrfs

import borg.trikeshed.lib.Closeable
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

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
 * Admission occurs under the volume lock. Drain stops admission before waiting;
 * admitted operations retain their buffers until every partial I/O settles.
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
    val readOnly: Boolean = false,
) : Volume, Closeable {
    private constructor(
        imagePath: String,
        blockSize: Int,
        capacity: Long,
        backendReport: NioCapabilityReport,
        resources: Join<File, UringChannel>,
    ) : this(imagePath, blockSize, capacity, resources.b, resources.a, -1, backendReport, null)

    constructor(
        imagePath: String,
        blockSize: Int = DEFAULT_BLOCK_SIZE,
        capacity: Long,
        entries: Int = 8,
        ebpfPrograms: List<UringEbpfProgram> = emptyList(),
    ) : this(
        imagePath = imagePath,
        blockSize = blockSize,
        capacity = capacity,
        backendReport = currentNioCapabilityReport(),
        resources = openCompatibility(imagePath, blockSize, capacity, entries, ebpfPrograms),
    )

    companion object {
        const val DEFAULT_BLOCK_SIZE: Int = 4096
        private const val O_RDWR: Int = 2
        private const val O_CREAT: Int = 64
        private const val O_EXCL: Int = 128

        /**
         * Reopen with create=false; resizing an existing image requires resize=true.
         * Create-and-size is exclusive by default, so failure cannot truncate an
         * existing image. readOnly requires create=false and resize=false.
         */
        suspend fun open(
            channel: UringChannel,
            imagePath: String,
            blockSize: Int = DEFAULT_BLOCK_SIZE,
            capacity: Long,
            create: Boolean = true,
            resize: Boolean = create,
            backendReport: NioCapabilityReport,
            channelReport: BtrfsUringChannelReport? = null,
            exclusive: Boolean = create && resize,
            readOnly: Boolean = false,
        ): BtrfsUringFileVolume {
            require(!exclusive || create) { "exclusive open requires create" }
            require(!readOnly || (!create && !resize)) { "read-only open cannot create or resize an image" }
            val volume = BtrfsUringFileVolume(
                imagePath = imagePath,
                blockSize = blockSize,
                capacity = capacity,
                channel = channel,
                file = null,
                fd = -1,
                backendReport = backendReport,
                channelReport = channelReport,
                readOnly = readOnly,
            )
            try {
                val flags = (if (readOnly) 0 else O_RDWR) or
                    (if (create) O_CREAT else 0) or (if (exclusive) O_EXCL else 0)
                currentCoroutineContext().ensureActive()
                // Retain the descriptor before cancellation can discard its OPENAT completion.
                withContext(NonCancellable) {
                    val opened = volume.submitOneAwait(
                        opcode = UringOp.OPENAT,
                        byteOffset = 0L,
                        byteLength = imagePath.encodeToByteArray().size,
                    ) { token -> Submissions.openat(imagePath, flags, token) }
                    if (opened < 0) {
                        throw BtrfsImageIoException("OPENAT failed for $imagePath: res=$opened", volume.ioReceipts())
                    }
                    volume.fd = opened
                }
                currentCoroutineContext().ensureActive()
                if (resize) {
                    withContext(NonCancellable) {
                        val truncated = volume.submitOneAwait(UringOp.FTRUNCATE, 0L, 0) { token ->
                            UringSubmission(UringOp.FTRUNCATE, volume.fd, 0, 0, volume.capacityBytes, userData = token)
                        }
                        if (truncated != 0) {
                            throw BtrfsImageIoException("FTRUNCATE failed for $imagePath: res=$truncated", volume.ioReceipts())
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                return volume
            } catch (failure: Throwable) {
                runCatching { volume.drain() }.exceptionOrNull()?.let {
                    if (it !== failure) failure.addSuppressed(it)
                }
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
                runCatching { opened.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                throw failure
            }
        }

        private fun openCompatibility(
            imagePath: String,
            blockSize: Int,
            capacity: Long,
            entries: Int,
            ebpfPrograms: List<UringEbpfProgram>,
        ): Join<File, UringChannel> {
            require(entries > 0) { "entries must be positive" }
            val file = openPreSizedFile(imagePath, blockSize, capacity)
            try {
                return file j UringChannels.open(entries, ebpfPrograms)
            } catch (failure: Throwable) {
                runCatching { file.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                throw failure
            }
        }
    }

    private val lock = Mutex()
    private val completions = ArrayList<BtrfsVolumeIoCompletion>()
    private val capacityBytes: Long = checkedCapacityBytes(capacity, blockSize)
    @Volatile private var draining = false
    private var closed = false
    private var closeFailure: Throwable? = null
    private var nextUserData = 1L

    fun ioReceipts(): List<BtrfsVolumeIoCompletion> = completions.toList()

    override suspend fun read(lba: Long, count: Int): ByteBuffer = withIo {
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
            if (res > byteLength - copied) {
                throw BtrfsImageIoException(
                    "oversized READ completion at $offset: res=$res remaining=${byteLength - copied}",
                    ioReceipts(),
                )
            }
            copied += res
        }
        ByteBuffer.wrap(target)
    }

    override suspend fun write(lba: Long, data: ByteBuffer): Unit = withIo {
        check(!readOnly) { "BtrfsUringFileVolume is read-only" }
        val startPosition = data.position()
        val total = data.remaining()
        val byteOffset = checkedByteOffset(lba, total)
        var written = 0
        while (written < total) {
            val source = data.slice()
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

    override suspend fun sync(): Unit = withIo {
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

    suspend fun drain(): Unit = withContext(NonCancellable) {
        draining = true
        lock.withLock {
            closeResource {
                when {
                    fd >= 0 -> submitOneAwait(UringOp.CLOSE, 0L, 0) { token -> Submissions.close(fd, token) }
                    file != null -> submitOne(UringOp.CLOSE, 0L, 0) { token -> channel.close(file, token) }
                    else -> 0
                }
            }
        }
    }

    override fun close() {
        check(lock.tryLock()) { "suspend drain() is required while volume I/O is active" }
        try {
            check(closed || fd < 0) { "suspend drain() is required for fd-backed BtrfsUringFileVolume" }
            draining = true
            closeResource {
                submitOne(UringOp.CLOSE, 0L, 0) { token -> channel.close(requireNotNull(file), token) }
            }
        } finally {
            lock.unlock()
        }
    }

    private inline fun closeResource(closeDescriptor: () -> Int) {
        if (!closed) {
            try {
                val res = closeDescriptor()
                if (res != 0) {
                    val failure = BtrfsImageIoException("CLOSE failed for $imagePath: res=$res", ioReceipts())
                    // A rejected SQE never reached the backend; the owned File still needs closing.
                    if (file != null && completions.lastOrNull()?.submitted == 0) {
                        runCatching { requireNotNull(file).close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                    }
                    throw failure
                }
            } catch (failure: Throwable) {
                closeFailure = failure
            } finally {
                // A failing CLOSE may already have released the fd. Never retry that identity.
                fd = -1
                closed = true
            }
        }
        if (file != null) {
            try {
                channel.closeNow()
            } catch (failure: Throwable) {
                val first = closeFailure
                if (first == null) closeFailure = failure else if (first !== failure) first.addSuppressed(failure)
            }
        }
        closeFailure?.let { throw it }
    }

    private suspend fun <T> withIo(operation: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        check(!draining) { "BtrfsUringFileVolume is draining" }
        return lock.withLock {
            requireOpen()
            currentCoroutineContext().ensureActive()
            val result = withContext(NonCancellable) { operation() }
            currentCoroutineContext().ensureActive()
            result
        }
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
        check(!draining && !closed && (fd >= 0 || file?.isOpen() == true)) { "BtrfsUringFileVolume is closed or draining" }
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
