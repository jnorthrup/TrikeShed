package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.AppendWal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.coroutines.CoroutineContext

/**
 * JVM actual for [AppendWal] — plain RandomAccessFile + synchronized RAF.
 *
 * File layout:
 *   header: MAGIC (4 bytes BE) + VERSION (4 bytes BE)
 *   records: keyLen(4) + keyBytes + payloadLen(4) + payloadBytes  (repeated)
 *
 * CoroutineContext.Element so it registers into the CCEK scope.
 * Uses Dispatchers.IO for the suspend RAF write — single-threaded so
 * no concurrent write coordination needed beyond synchronized.
 */
class JvmAppendWal(private val path: File) : AppendWal {
    companion object {
        private const val MAGIC = 0xCA05A101.toInt()
        private const val VERSION = 1
    }

    override val key: CoroutineContext.Key<*> get() = AppendWal

    private val raf: RandomAccessFile = RandomAccessFile(path, "rw").also { initHeader(it) }

    private fun initHeader(raf: RandomAccessFile) {
        if (path.length() < 8L) {
            raf.writeInt(MAGIC)
            raf.writeInt(VERSION)
            raf.fd.sync()
        }
    }

    override suspend fun append(key: String, payload: ByteArray): Long =
        withContext(Dispatchers.IO) {
            val keyBytes = key.encodeToByteArray()
            synchronized(raf) {
                val offset = raf.length()
                raf.seek(offset)
                raf.writeInt(keyBytes.size)
                raf.write(keyBytes)
                raf.writeInt(payload.size)
                raf.write(payload)
                raf.fd.sync()
                offset
            }
        }

    override fun replay(): Sequence<Pair<String, ByteArray>> = sequence {
        val readRaf = RandomAccessFile(path, "r")
        try {
            if (readRaf.length() < 8L) return@sequence

            val magic = readRaf.readInt()
            val version = readRaf.readInt()
            if (magic != MAGIC || version != VERSION) {
                error("Invalid WAL header: magic=$magic version=$version")
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

    override fun close() {
        raf.close()
    }
}
