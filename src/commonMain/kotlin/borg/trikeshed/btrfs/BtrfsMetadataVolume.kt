package borg.trikeshed.btrfs

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.btrfs.BtrfsCodecLE
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Native filesystem writers explicitly identify tree blocks; ordinary data and superblocks stay raw. */
interface BtrfsMetadataSink {
    suspend fun writeMetadata(lba: Long, data: ByteBuffer)
}

/** The owning volume-generation manifest must publish this descriptor together with its raw members. */
data class BtrfsMetadataDescriptor(
    val path: String,
    val contentId: ContentId,
    val storedBytes: Int,
    val decodedBytes: Int,
    val extentCount: Int,
)

/**
 * Stages native Btrfs tree blocks outside a fresh raw generation. Tree bytes
 * never reach the raw backing volume; [finish] stores them in one compressed,
 * checked sidecar. Reads always expose the original canonical Btrfs bytes.
 * Consequently a native tool consumes an exported logical image, not a raw
 * member or the sidecar by itself.
 *
 * The caller owns the backing volume and its bounded I/O actor. A staging
 * instance is used only for an unpublished generation. After [finish], and on
 * [open], it is read-only: mutations create a new generation. Publication of
 * the returned descriptor belongs to the same transaction as the raw members.
 */
class BtrfsMetadataVolume private constructor(
    private val backing: Volume,
    private val pages: MutableMap<Long, ByteArray>,
    private var staging: Boolean,
) : Volume, BtrfsMetadataSink {
    override val blockSize: Int get() = backing.blockSize
    override val capacity: Long get() = backing.capacity
    private val admission = Mutex()
    private var metadataBytes = pages.values.sumOf { it.size.toLong() }
    private var snapshot: Series<Join<Long, Int>> = snapshot()

    /** Immutable range snapshot, in logical blocks; used while migrating a generation. */
    val ranges: Series<Join<Long, Int>> get() = snapshot

    override suspend fun read(lba: Long, count: Int): ByteBuffer = admission.withLock {
        bounds(lba, count)
        require(count <= Int.MAX_VALUE / blockSize) { "Metadata volume read exceeds buffer capacity" }
        if (count == 0) return@withLock ByteBuffer.wrap(ByteArray(0))
        val raw = backing.read(lba, count)
        val bytes = ByteArray(count * blockSize)
        require(raw.remaining() == bytes.size) { "Short raw metadata backing read" }
        raw.get(bytes)
        val end = lba + count
        for ((at, page) in pages) {
            val first = maxOf(lba, at)
            val last = minOf(end, at + page.size / blockSize)
            if (first < last) page.copyInto(bytes, ((first - lba) * blockSize).toInt(),
                ((first - at) * blockSize).toInt(), ((last - at) * blockSize).toInt())
        }
        ByteBuffer.wrap(bytes)
    }

    override suspend fun write(lba: Long, data: ByteBuffer) = admission.withLock {
        check(staging) { "A published metadata generation is immutable" }
        val count = ((data.remaining().toLong() + blockSize - 1) / blockSize).toInt()
        bounds(lba, count)
        require(pages.none { (at, bytes) -> overlaps(lba, count, at, bytes.size / blockSize) }) {
            "Ordinary write overlaps a captured metadata extent"
        }
        if (count > 0) backing.write(lba, data)
    }

    override suspend fun writeMetadata(lba: Long, data: ByteBuffer) = admission.withLock {
        check(staging) { "A published metadata generation is immutable" }
        val size = data.remaining()
        require(size > 0 && size % blockSize == 0) { "Unaligned or empty metadata extent" }
        bounds(lba, size / blockSize)
        val previous = pages[lba]
        require(previous == null || previous.size == size) { "Replacement metadata extent changed its range" }
        require(pages.none { (at, bytes) -> at != lba && overlaps(lba, size / blockSize, at, bytes.size / blockSize) }) {
            "Metadata extents overlap"
        }
        val total = metadataBytes - (previous?.size ?: 0) + size
        val count = pages.size + if (previous == null) 1 else 0
        require(HEADER_BYTES + count.toLong() * RECORD_BYTES + total <= BtrfsMetadataCompression.MAX_BYTES) {
            "Native tree metadata exceeds sidecar capacity"
        }
        val captured = ByteArray(size)
        data.get(captured)
        pages[lba] = captured
        metadataBytes = total
        snapshot = snapshot()
    }

    override suspend fun sync() = admission.withLock { backing.sync() }

    /**
     * Flush raw data before publishing the sidecar. [FileOperations.durability]
     * describes the provider's atomic-write guarantee; persistent containers
     * must require a durable provider. Existing paths are never overwritten.
     */
    suspend fun finish(
        files: FileOperations,
        path: String,
        compression: BtrfsMetadataCompression,
    ): BtrfsMetadataDescriptor = admission.withLock {
        check(staging) { "Metadata generation has already been sealed" }
        require(pages.isNotEmpty()) { "No native tree metadata was captured" }
        require(path.isNotBlank() && path.encodeToByteArray().size <= 4096 && '\u0000' !in path)
        val decoded = encodePages()
        val stored = compression.encode(decoded)
        val descriptor = BtrfsMetadataDescriptor(path, ContentId.of(stored), stored.size, decoded.size, pages.size)
        backing.sync()
        withContext(fileIoContext) {
            require(!files.exists(path)) { "Metadata sidecar already exists" }
            files.writeAtomically(path, stored)
            check(files.readAllBytes(path).contentEquals(stored)) { "Metadata sidecar publication verification failed" }
        }
        staging = false
        descriptor
    }

    private fun snapshot(): Series<Join<Long, Int>> {
        val values = pages.entries.sortedBy { it.key }.map { it.key j (it.value.size / blockSize) }.toTypedArray()
        return values.size j { values[it] }
    }

    private fun encodePages(): ByteArray {
        val size = HEADER_BYTES + pages.size.toLong() * RECORD_BYTES + metadataBytes
        require(size <= BtrfsMetadataCompression.MAX_BYTES)
        val bytes = ByteArray(size.toInt())
        MAGIC.copyInto(bytes)
        BtrfsCodecLE.writeInt(VERSION, bytes, 8)
        BtrfsCodecLE.writeInt(blockSize, bytes, 12)
        BtrfsCodecLE.writeLong(capacity, bytes, 16)
        BtrfsCodecLE.writeInt(pages.size, bytes, 24)
        var cursor = HEADER_BYTES
        for ((lba, page) in pages.entries.sortedBy { it.key }) {
            BtrfsCodecLE.writeLong(lba, bytes, cursor)
            BtrfsCodecLE.writeInt(page.size, bytes, cursor + 8)
            cursor += RECORD_BYTES
            page.copyInto(bytes, cursor)
            cursor += page.size
        }
        return bytes
    }

    private fun bounds(lba: Long, count: Int) {
        require(count >= 0 && lba >= 0 && lba <= capacity - count) { "Metadata volume request is out of bounds" }
    }

    companion object {
        private const val HEADER_BYTES = 32
        private const val RECORD_BYTES = 16
        private const val VERSION = 1
        private val MAGIC = "TSBTPG01".encodeToByteArray()

        fun stage(backing: Volume): BtrfsMetadataVolume {
            geometry(backing)
            return BtrfsMetadataVolume(backing, mutableMapOf(), true)
        }

        /** The caller supplies its filesystem I/O context and still owns [backing]. */
        fun open(backing: Volume, files: FileOperations, descriptor: BtrfsMetadataDescriptor): BtrfsMetadataVolume {
            geometry(backing)
            require(descriptor.path.isNotBlank() && descriptor.path.encodeToByteArray().size <= 4096 && '\u0000' !in descriptor.path)
            require(descriptor.decodedBytes in HEADER_BYTES..BtrfsMetadataCompression.MAX_BYTES &&
                descriptor.storedBytes in 64..BtrfsMetadataCompression.MAX_BYTES + 64 &&
                descriptor.extentCount in 1..BtrfsMetadataCompression.MAX_BYTES / (RECORD_BYTES + backing.blockSize)) {
                "Invalid metadata sidecar descriptor"
            }
            val fd = files.open(descriptor.path, readOnly = true)
            try { require(files.size(fd) == descriptor.storedBytes.toLong()) { "Metadata sidecar size mismatch" } }
            finally { files.close(fd) }
            val stored = files.readAllBytes(descriptor.path)
            require(stored.size == descriptor.storedBytes && ContentId.of(stored) == descriptor.contentId) {
                "Metadata sidecar content identity mismatch"
            }
            require(BtrfsMetadataCompression.isEncoded(stored)) { "Metadata sidecar has no checked envelope" }
            val bytes = BtrfsMetadataCompression.decode(stored, descriptor.decodedBytes)
            require(bytes.size == descriptor.decodedBytes && bytes.size >= HEADER_BYTES &&
                MAGIC.indices.all { bytes[it] == MAGIC[it] } && BtrfsCodecLE.readInt(bytes, 8) == VERSION) {
                "Invalid native metadata sidecar"
            }
            require(BtrfsCodecLE.readInt(bytes, 12) == backing.blockSize && BtrfsCodecLE.readLong(bytes, 16) == backing.capacity) {
                "Metadata sidecar geometry differs from its raw generation"
            }
            val count = BtrfsCodecLE.readInt(bytes, 24)
            require(count == descriptor.extentCount && BtrfsCodecLE.readInt(bytes, 28) == 0)
            val pages = mutableMapOf<Long, ByteArray>()
            var cursor = HEADER_BYTES
            var previousEnd = 0L
            repeat(count) {
                require(cursor <= bytes.size - RECORD_BYTES) { "Truncated metadata extent record" }
                val lba = BtrfsCodecLE.readLong(bytes, cursor)
                val size = BtrfsCodecLE.readInt(bytes, cursor + 8)
                require(BtrfsCodecLE.readInt(bytes, cursor + 12) == 0)
                cursor += RECORD_BYTES
                require(size > 0 && size % backing.blockSize == 0 && size <= bytes.size - cursor &&
                    lba >= previousEnd && lba <= backing.capacity - size / backing.blockSize) {
                    "Invalid or overlapping metadata extent"
                }
                pages[lba] = bytes.copyOfRange(cursor, cursor + size)
                previousEnd = lba + size / backing.blockSize
                cursor += size
            }
            require(cursor == bytes.size) { "Trailing native metadata sidecar bytes" }
            return BtrfsMetadataVolume(backing, pages, false)
        }

        private fun geometry(volume: Volume) {
            require(volume.blockSize in 1..1024 * 1024 && volume.blockSize.countOneBits() == 1 &&
                volume.capacity in 0..Long.MAX_VALUE / volume.blockSize) { "Invalid metadata backing geometry" }
        }

        private fun overlaps(a: Long, aCount: Int, b: Long, bCount: Int): Boolean =
            aCount > 0 && bCount > 0 && a < b + bCount && b < a + aCount
    }
}
