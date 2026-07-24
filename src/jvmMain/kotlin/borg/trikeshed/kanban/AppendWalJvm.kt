package borg.trikeshed.kanban

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.Paths
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

actual class AppendWal actual constructor(path: String) {
    private val file: Path = Paths.get(path)
    private val randomAccessFile = RandomAccessFile(file.toFile(), "rw")
    private val channel = randomAccessFile.channel

    companion object {
        private const val MAGIC: Int = 0xCA05A101.toInt()
        private const val VERSION: Int = 1
    }

    private var writeOffset: Long = 0L

    // Keep a single mapped region to avoid memory leaks. We remap when file grows.
    private var currentArena: Arena? = null
    private var currentMapping: MemorySegment? = null
    private var mappedSize: Long = 0L
    private val mapLock = Any()

    init {
        val isNew = !file.toFile().exists() || file.toFile().length() == 0L
        if (isNew) {
            val buf = ByteBuffer.allocate(8)
            buf.putInt(MAGIC)
            buf.putInt(VERSION)
            buf.flip()
            channel.write(buf, 0)
            writeOffset = 8L
        } else {
            writeOffset = channel.size()
            val buf = ByteBuffer.allocate(8)
            channel.read(buf, 0)
            buf.flip()
            if (buf.remaining() >= 8) {
                val magic = buf.getInt()
                val version = buf.getInt()
                if (magic != MAGIC || version != VERSION) {
                    error("Invalid AppendWal header: magic=$magic version=$version")
                }
            }
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

    @Synchronized
    actual fun append(key: String, payload: ByteArray): Long {
        val keyBytes = key.encodeToByteArray()
        val keyLen = keyBytes.size
        val payloadLen = payload.size

        val buffer = ByteBuffer.allocate(4 + keyLen + 4 + payloadLen)
        buffer.putInt(keyLen)
        buffer.put(keyBytes)
        buffer.putInt(payloadLen)
        buffer.put(payload)
        buffer.flip()

        val offset = writeOffset
        channel.write(buffer, offset)
        channel.force(false)

        writeOffset += buffer.capacity()
        remap() // Remap after write to include new data
        return offset
    }

    actual fun replay(): Sequence<Pair<String, ByteArray>> = sequence {
        val map = synchronized(mapLock) { currentMapping }
        val mapSize = synchronized(mapLock) { mappedSize }
        if (map == null || mapSize < 8L) return@sequence

        // Use map.asByteBuffer() which preserves the big-endian writing from ByteBuffer putInt
        val buffer = map.asByteBuffer()
        buffer.position(8) // Skip magic and version

        while (buffer.remaining() >= 4) {
            val keyLen = buffer.getInt()
            if (buffer.remaining() < keyLen) break
            val keyBytes = ByteArray(keyLen)
            buffer.get(keyBytes)

            if (buffer.remaining() < 4) break
            val payloadLen = buffer.getInt()
            if (buffer.remaining() < payloadLen) break
            val payloadBytes = ByteArray(payloadLen)
            buffer.get(payloadBytes)

            yield(keyBytes.decodeToString() to payloadBytes)
        }
    }

    actual fun close() {
        synchronized(mapLock) {
            currentArena?.close()
            currentArena = null
            currentMapping = null
        }
        channel.close()
        randomAccessFile.close()
    }
}
