package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.volume.Volume
import kotlinx.coroutines.runBlocking

class VolumeCasStore(
    private val volume: Volume,
    private val replicationHook: CasReplicationHook = CasReplicationHook.NoOp,
    private val blockSize: Int = volume.blockSize,
) {
    private var index: BlockIndex
    private val lock = Any()

    // Simple bump allocator for LBAs starting at 1 (since 0 is the index)
    private var nextFreeLba = 1L

    init {
        require(blockSize == volume.blockSize) { "blockSize mismatch" }
        // Attempt to load index from LBA 0
        index = runBlocking {
            val headerBytes = volume.read(0, 1) // read first block to get header
            val magic = if (headerBytes.size >= 4) headerBytes.readIntAt(0) else 0
            if (magic == 0xCA5B1001.toInt()) {
                // Determine how many blocks we need for the full index
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

                // Update nextFreeLba based on existing entries
                var maxLba = 0L
                for ((_, entry) in decoded.getEntries()) {
                    val entryBlocks = (entry.sizeBytes + blockSize - 1) / blockSize
                    val entryEndLba = entry.lba + entryBlocks - 1
                    if (entryEndLba > maxLba) {
                        maxLba = entryEndLba
                    }
                }
                // Determine index size in blocks to start free LBAs
                val indexBlocks = (12 + decoded.getEntries().size * 80 + blockSize - 1) / blockSize
                val maxReserved = maxOf(maxLba, indexBlocks.toLong() - 1)
                nextFreeLba = maxReserved + 1L

                decoded
            } else {
                BlockIndex()
            }
        }
    }

    fun put(bytes: ByteArray): ContentId = runBlocking {
        val cid = ContentId.of(bytes)

        synchronized(lock) {
            val existing = index.get(cid)
            if (existing != null) {
                index.put(cid, existing.copy(refCount = existing.refCount + 1))
                return@runBlocking cid
            }

            val blocksNeeded = (bytes.size + blockSize - 1) / blockSize
            val lba = nextFreeLba
            nextFreeLba += blocksNeeded

            index.put(cid, LbaEntry(lba, bytes.size, 1))
        }

        // Write to volume outside the lock
        val blocksNeeded = (bytes.size + blockSize - 1) / blockSize
        val lba = synchronized(lock) { index.get(cid)?.lba } ?: return@runBlocking cid

        val paddedBytes = if (bytes.size % blockSize == 0 && bytes.isNotEmpty()) {
            bytes
        } else {
            val padded = ByteArray(blocksNeeded * blockSize)
            bytes.copyInto(padded)
            padded
        }

        // Write block by block to avoid large arrays if volume requires it,
        // but here we can just write the whole array if volume supports it.
        // Assuming volume.write handles paddedBytes.size bytes starting at lba.
        // The tests use FakeVolume which expects to write `paddedBytes.size / blockSize` blocks.
        volume.write(lba, paddedBytes)
        replicationHook.onPut(cid, bytes)

        cid
    }

    fun get(cid: ContentId): ByteArray? = runBlocking {
        val entry = synchronized(lock) { index.get(cid) } ?: return@runBlocking null

        val blocksNeeded = (entry.sizeBytes + blockSize - 1) / blockSize
        val paddedBytes = volume.read(entry.lba, blocksNeeded)

        val result = ByteArray(entry.sizeBytes)
        paddedBytes.copyInto(result, 0, 0, entry.sizeBytes)

        val actualCid = ContentId.of(result)
        if (actualCid != cid) {
            throw IllegalStateException("digest mismatch")
        }

        result
    }

    fun delete(cid: ContentId): Boolean {
        synchronized(lock) {
            val entry = index.get(cid) ?: return false
            if (entry.refCount > 1) {
                index.put(cid, entry.copy(refCount = entry.refCount - 1))
            } else {
                index.remove(cid)
            }
            return true
        }
    }

    fun manifest(cids: List<ContentId>): CasManifest {
        val sortedCids = cids.sortedBy { it.value }
        return CasManifest(sortedCids)
    }

    fun sync() = runBlocking {
        val encoded = synchronized(lock) { index.encode() }
        val blocksNeeded = (encoded.size + blockSize - 1) / blockSize
        val paddedBytes = ByteArray(blocksNeeded * blockSize)
        encoded.copyInto(paddedBytes)

        volume.write(0L, paddedBytes)
        volume.sync()
    }
}
