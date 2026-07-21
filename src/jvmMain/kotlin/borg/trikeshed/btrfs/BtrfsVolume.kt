package borg.trikeshed.btrfs

import borg.trikeshed.userspace.volume.Volume
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class BtrfsVolume(private val devicePath: String) : Volume {
    override val blockSize: Int = 4096
    override val capacity: Long

    private val chunkMap = sortedMapOf<ULong, BtrfsChunkItem>()

    init {
        RandomAccessFile(devicePath, "r").use { raf ->
            val buf = ByteArray(4096)
            raf.seek(0)
            raf.readFully(buf)
            val sb = BtrfsSuperblock.parse(buf)
            capacity = sb.totalBytes.toLong()

            // read chunk tree
            val chunkTreeBuf = ByteArray(4096)
            raf.seek(sb.chunkRoot.toLong())
            raf.readFully(chunkTreeBuf)
            val chunkItems = BtrfsChunkTree.parse(chunkTreeBuf, 0)
            
            for ((key, item) in chunkItems) {
                chunkMap[key.offset] = item
            }
        }
    }

    override suspend fun read(lba: Long, count: Int): ByteArray {
        val logicalOffset = (lba * blockSize).toULong()
        val readBytesCount = count * blockSize
        
        // Find chunk that contains this logical offset
        // In sorted map, find the last entry whose key <= logicalOffset
        var targetChunkOffset: ULong? = null
        var targetChunk: BtrfsChunkItem? = null
        
        for ((offset, chunk) in chunkMap) {
            if (offset <= logicalOffset) {
                targetChunkOffset = offset
                targetChunk = chunk
            } else {
                break
            }
        }
        
        requireNotNull(targetChunkOffset) { "No chunk found for logical offset $logicalOffset" }
        requireNotNull(targetChunk) { "No chunk found for logical offset $logicalOffset" }
        
        // Only SINGLE or valid RAID0 logic provided per requirements (asserting numStripes == 1u || type == SINGLE (2))
        require(targetChunk.numStripes == 1u.toUShort() || targetChunk.type == 2.toUByte()) {
            "Only SINGLE or single-stripe chunks are supported for read"
        }
        
        val stripe = targetChunk.stripes.first()
        val offsetWithinChunk = logicalOffset - targetChunkOffset
        val physicalOffset = stripe.offset + offsetWithinChunk
        
        val buf = ByteArray(readBytesCount)
        RandomAccessFile(devicePath, "r").use { raf ->
            raf.channel.position(physicalOffset.toLong())
            val byteBuf = ByteBuffer.wrap(buf)
            var bytesRead = 0
            while (bytesRead < readBytesCount) {
                val r = raf.channel.read(byteBuf)
                if (r == -1) break
                bytesRead += r
            }
        }
        return buf
    }

    override suspend fun write(lba: Long, data: ByteArray): Nothing =
        throw UnsupportedOperationException("BtrfsVolume is read-only; COW not in scope for this task")

    override suspend fun sync(): Unit = Unit
}