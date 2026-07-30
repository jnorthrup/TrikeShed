package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.volume.Volume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CAS store backed by a [Volume] LBA surface.
 *
 * Each CID is written block-aligned to its own LBA range. A block index lives
 * at LBA 0 and is rewritten atomically on [sync].
 *
 * Use [create] to construct one (the index load is a suspend call).
 */
class VolumeCasStore internal constructor(
    initialIndex: BlockIndex,
    initialNextLba: Long,
    private val volume: Volume,
    private val replicationHook: CasReplicationHook = CasReplicationHook.NoOp,
    private val blockSize: Int = volume.blockSize,
) {
    private var index: BlockIndex = initialIndex
    private val lock = Mutex()
    private var nextFreeLba: Long = initialNextLba

    init {
        require(blockSize == volume.blockSize) { "blockSize mismatch" }
    }

    /** Construct a store by reading the on-volume index at LBA 0. */
    suspend fun create(
        volume: Volume,
        replicationHook: CasReplicationHook = CasReplicationHook.NoOp,
        blockSize: Int = volume.blockSize,
    ): VolumeCasStore {
        val headerBytes = volume.read(0, 1)
        val magic = if (headerBytes.size >= 4) headerBytes.readIntAt(0) else 0
        if (magic != 0xCA5B1001.toInt()) {
            return VolumeCasStore(BlockIndex(), 1L, volume, replicationHook, blockSize)
        }
        val entryCount = headerBytes.readIntAt(8)
        val totalBytes = 12 + entryCount * 80
        val blocksNeeded = (totalBytes + blockSize - 1) / blockSize
        val fullIndexBytes = if (blocksNeeded > 1) {
            val full = ByteArray(blocksNeeded * blockSize)
            headerBytes.copyInto(full, 0, 0, blockSize)
            for (i in 1 until blocksNeeded) {
                val chunk = volume.read(i.toLong(), 1)
                chunk.copyInto(full, i * blockSize, 0, blockSize)
            }
            full
        } else {
            headerBytes
        }
        val decoded = BlockIndex.decode(fullIndexBytes)
        var maxLba = 0L
        for ((_, entry) in decoded.getEntries()) {
            val entryBlocks = (entry.sizeBytes + blockSize - 1) / blockSize
            val entryEndLba = entry.lba + entryBlocks - 1
            if (entryEndLba > maxLba) maxLba = entryEndLba
        }
        val indexBlocks = (12 + decoded.getEntries().size * 80 + blockSize - 1) / blockSize
        val maxReserved = maxOf(maxLba, indexBlocks.toLong() - 1)
        return VolumeCasStore(decoded, maxReserved + 1L, volume, replicationHook, blockSize)
    }

    suspend fun put(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        val lba: Long
        val blocksNeeded: Int
        lock.withLock {
            val existing = index.get(cid)
            if (existing != null) {
                index.put(cid, existing.copy(refCount = existing.refCount + 1))
                return cid
            }
            blocksNeeded = (bytes.size + blockSize - 1) / blockSize
            lba = nextFreeLba
            nextFreeLba += blocksNeeded
            index.put(cid, LbaEntry(lba, bytes.size, 1))
        }
        val padded = padForVolume(bytes, blocksNeeded)
        volume.write(lba, padded)
        replicationHook.onPut(cid, bytes)
        return cid
    }

    suspend fun get(cid: ContentId): ByteArray? {
        val entry = lock.withLock { index.get(cid) } ?: return null
        val blocksNeeded = (entry.sizeBytes + blockSize - 1) / blockSize
        val padded = volume.read(entry.lba, blocksNeeded)
        val result = ByteArray(entry.sizeBytes)
        padded.copyInto(result, 0, 0, entry.sizeBytes)
        val actualCid = ContentId.of(result)
        if (actualCid != cid) {
            throw IllegalStateException("digest mismatch")
        }
        return result
    }

    suspend fun delete(cid: ContentId): Boolean = lock.withLock {
        val entry = index.get(cid) ?: return@withLock false
        if (entry.refCount > 1) {
            index.put(cid, entry.copy(refCount = entry.refCount - 1))
        } else {
            index.remove(cid)
        }
        true
    }

    fun manifest(cids: List<ContentId>): CasManifest {
        val sortedCids = cids.sortedBy { it.value }
        return CasManifest(sortedCids)
    }

    suspend fun sync() {
        val encoded = lock.withLock { index.encode() }
        val blocksNeeded = (encoded.size + blockSize - 1) / blockSize
        val padded = padForVolume(encoded, blocksNeeded)
        volume.write(0L, padded)
        volume.sync()
    }

    private fun padForVolume(bytes: ByteArray, blocksNeeded: Int): ByteArray {
        if (bytes.size == blocksNeeded * blockSize) return bytes
        val padded = ByteArray(blocksNeeded * blockSize)
        bytes.copyInto(padded)
        return padded
    }
}
