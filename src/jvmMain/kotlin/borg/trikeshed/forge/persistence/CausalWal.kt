package borg.trikeshed.forge.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Append-only write-ahead log keyed by causalKey, replayable on daemon restart.
 */
class CausalWal(private val path: File) {

    companion object {
        private const val MAGIC: Int = 0xCA05A101.toInt()
        private const val VERSION: Int = 1
    }

    private val raf: RandomAccessFile

    init {
        val isNew = !path.exists() || path.length() == 0L
        raf = RandomAccessFile(path, "rw")
        if (isNew) {
            raf.writeInt(MAGIC)
            raf.writeInt(VERSION)
            raf.fd.sync()
        }
    }

    suspend fun append(causalKey: String, payload: ByteArray): Long = withContext(Dispatchers.IO) {
        val keyBytes = causalKey.encodeToByteArray()

        // ⚡ Bolt Optimization: Pre-allocate a single ByteBuffer outside the synchronized block.
        // By copying the header lengths and payload into this single buffer, we reduce
        // 4 separate JNI / disk `write` calls into 1, vastly reducing disk I/O overhead
        // and minimizing the lock duration on the RandomAccessFile.
        val buffer = java.nio.ByteBuffer.allocate(8 + keyBytes.size + payload.size)
        buffer.putInt(keyBytes.size)
        buffer.put(keyBytes)
        buffer.putInt(payload.size)
        buffer.put(payload)

        synchronized(raf) {
            val offset = raf.length()
            raf.seek(offset)
            raf.write(buffer.array())
            raf.fd.sync()
            offset
        }
    }

    fun replay(): Sequence<Pair<String, ByteArray>> = sequence {
        // Since we read iteratively, we should open a new file stream specifically for reading.
        val readRaf = RandomAccessFile(path, "r")
        try {
            if (readRaf.length() < 8L) return@sequence

            val magic = readRaf.readInt()
            val version = readRaf.readInt()
            if (magic != MAGIC || version != VERSION) {
                error("Invalid CausalWal header: magic=$magic version=$version")
            }

            while (readRaf.filePointer < readRaf.length()) {
                val keyLen = readRaf.readInt()
                val keyBytes = ByteArray(keyLen)
                readRaf.readFully(keyBytes)

                val payloadLen = readRaf.readInt()
                val payloadBytes = ByteArray(payloadLen)
                readRaf.readFully(payloadBytes)

                yield(keyBytes.decodeToString() to payloadBytes)
            }
        } finally {
            readRaf.close()
        }
    }

    // Test utility
    internal fun close() {
        raf.close()
    }
}
