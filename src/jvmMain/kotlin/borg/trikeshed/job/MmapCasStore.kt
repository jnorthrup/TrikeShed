package borg.trikeshed.job

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import java.io.RandomAccessFile
import borg.trikeshed.collections.associative.LinearHashMap

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

class MmapCasStore(val file: Path) {
    private val randomAccessFile = RandomAccessFile(file.toFile(), "rw")
    private val channel = randomAccessFile.channel

    // We parse the file contents on initialization to rebuild the index.
    // Format per blob: [4 bytes length][N bytes content]

    // Use LinearHashMap for non-mmap fallback/index as requested
    private val offsetMap = LinearHashMap<ContentId, Pair<Long, Int>>()
    private var writeOffset: Long = 0L

    // Keep a single mapped region to avoid memory leaks. We remap when file grows.
    private var currentArena: Arena? = null
    private var currentMapping: MemorySegment? = null
    private var mappedSize: Long = 0L
    private val mapLock = Any()

    init {
        rebuildIndex()
    }

    @Synchronized
    private fun rebuildIndex() {
        writeOffset = 0L
        val size = channel.size()
        if (size == 0L) return
        val header = ByteBuffer.allocate(4)

        while (writeOffset + 4 <= size) {
            header.clear()
            val read = channel.read(header, writeOffset)
            if (read < 4) break

            header.flip()
            val len = header.getInt()

            if (len <= 0 || writeOffset + 4 + len > size) {
                // corrupted or partial write, truncate
                channel.truncate(writeOffset)
                break
            }

            // read content to compute CID
            val buf = ByteBuffer.allocate(len)
            channel.read(buf, writeOffset + 4)
            val bytes = buf.array()
            val cid = ContentId.of(bytes)

            offsetMap[cid] = Pair(writeOffset + 4, len)
            writeOffset += 4 + len
        }

        remap()
    }

    private fun remap() {
        synchronized(mapLock) {
            val size = channel.size()
            if (size == 0L) return
            if (size > 0 && size > mappedSize) {
                currentArena?.close()
                val newArena = Arena.ofShared()
                currentMapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, newArena)
                currentArena = newArena
                mappedSize = size
            }
        }
    }

    fun put(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        synchronized(this) {
            if (cid in offsetMap) return cid

            val len = bytes.size
            val buffer = ByteBuffer.allocate(4 + len)
            buffer.putInt(len)
            buffer.put(bytes)
            buffer.flip()

            channel.write(buffer, writeOffset)

            offsetMap[cid] = Pair(writeOffset + 4, len)
            writeOffset += 4 + len
        }
        remap() // Remap after write to include new data
        return cid
    }


    fun get(cid: ContentId): Series<Byte>? {
        val loc = synchronized(this) { offsetMap[cid] } ?: return null
        val offset = loc.first
        val length = loc.second
        val map = synchronized(mapLock) { currentMapping } ?: return null

        // Return a series that reads from the mapped buffer without copy
        return length j { i: Int -> map.get(ValueLayout.JAVA_BYTE, offset + i.toLong()) }
    }

    fun close() {
        synchronized(mapLock) {
            currentArena?.close()
            currentArena = null
            currentMapping = null
        }
        channel.close()
        randomAccessFile.close()
    }
}
