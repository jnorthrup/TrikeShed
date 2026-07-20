package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.volume.Volume
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.MappedByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MmapCasStoreJvm(
    private val file: File,
    private val replicationHook: CasReplicationHook = CasReplicationHook.NoOp,
    blockSize: Int = 4096
) {
    private val volume = MmapVolume(file, blockSize)
    private val store = VolumeCasStore(volume, replicationHook, blockSize)

    fun put(bytes: ByteArray): ContentId = store.put(bytes)

    fun get(cid: ContentId): ByteArray? = store.get(cid)

    fun delete(cid: ContentId): Boolean = store.delete(cid)

    fun manifest(cids: List<ContentId>): CasManifest = store.manifest(cids)

    fun sync() {
        store.sync()
    }
}

class MmapVolume(private val file: File, override val blockSize: Int) : Volume {
    override val capacity: Long
        get() = file.length()

    private val randomAccessFile = RandomAccessFile(file, "rw")
    private val channel = randomAccessFile.channel

    // Simplistic approach: map 100MB at a time
    private val MAP_SIZE = 100 * 1024 * 1024L
    private val lock = Any()
    private var mappedBuffer: MappedByteBuffer? = null
    private var mappedOffset = 0L
    private var mappedSize = 0L

    private fun getBufferFor(position: Long, size: Long): MappedByteBuffer {
        synchronized(lock) {
            val endPosition = position + size

            // If file is not large enough, extend it
            if (endPosition > channel.size()) {
                randomAccessFile.setLength(endPosition)
                // Invalidate buffer to force remap
                mappedBuffer = null
            }

            val buf = mappedBuffer
            if (buf != null && position >= mappedOffset && endPosition <= mappedOffset + mappedSize) {
                return buf
            }

            // Need to map a new region
            val mapStart = position - (position % MAP_SIZE)
            // Ensure we map enough to cover the requested size, min MAP_SIZE
            val newMappedSize = maxOf(MAP_SIZE, size + (position % MAP_SIZE))

            // Don't map past end of file if we don't have to, but we just extended it if needed
            val actualSize = minOf(newMappedSize, channel.size() - mapStart)

            val newBuf = channel.map(FileChannel.MapMode.READ_WRITE, mapStart, actualSize)
            mappedBuffer = newBuf
            mappedOffset = mapStart
            mappedSize = actualSize
            return newBuf
        }
    }

    override suspend fun read(lba: Long, count: Int): ByteArray = withContext(Dispatchers.IO) {
        val position = lba * blockSize
        val size = count * blockSize

        // Ensure file is large enough to map
        if (position >= channel.size()) {
            return@withContext ByteArray(size)
        }

        // If the requested bytes overlap with end of file, we need to read what we can
        if (position + size > channel.size()) {
            val readableSize = (channel.size() - position).toInt()
            val result = ByteArray(size)

            val buffer = getBufferFor(position, readableSize.toLong())

            synchronized(buffer) {
                buffer.position((position - mappedOffset).toInt())
                buffer.get(result, 0, readableSize)
            }

            return@withContext result
        }

        val buffer = getBufferFor(position, size.toLong())
        val result = ByteArray(size)

        synchronized(buffer) {
            buffer.position((position - mappedOffset).toInt())
            buffer.get(result)
        }

        result
    }

    override suspend fun write(lba: Long, data: ByteArray) = withContext(Dispatchers.IO) {
        val position = lba * blockSize
        val buffer = getBufferFor(position, data.size.toLong())

        synchronized(buffer) {
            buffer.position((position - mappedOffset).toInt())
            buffer.put(data)
            Unit
        }
    }

    override suspend fun sync() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            mappedBuffer?.force()
            Unit
        }
    }
}
