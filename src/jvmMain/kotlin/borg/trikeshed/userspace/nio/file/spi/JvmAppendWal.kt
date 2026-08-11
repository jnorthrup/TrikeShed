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
        /** A corrupt frame must not allocate attacker-sized arrays during replay. */
        private const val MAX_RECORD_BYTES = 64 * 1024 * 1024
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

    override suspend fun append(key: String, payload: ByteArray): Long {
        val keyBytes = key.encodeToByteArray()
        return withContext(Dispatchers.IO) {
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

            val fileLength = readRaf.length()
            while (readRaf.filePointer < fileLength) {
                val recordOffset = readRaf.filePointer
                if (fileLength - recordOffset < Int.SIZE_BYTES) {
                    reportTornTail(recordOffset, fileLength, "missing key length")
                    break
                }
                val keyLen = readRaf.readInt()
                if (keyLen < 0 || keyLen > MAX_RECORD_BYTES) {
                    error("Invalid WAL key length $keyLen at offset $recordOffset")
                }
                if (fileLength - readRaf.filePointer < keyLen.toLong() + Int.SIZE_BYTES) {
                    reportTornTail(recordOffset, fileLength, "incomplete key or missing payload length")
                    break
                }
                val keyBytes = ByteArray(keyLen)
                readRaf.readFully(keyBytes)

                val payloadLen = readRaf.readInt()
                if (payloadLen < 0 || payloadLen > MAX_RECORD_BYTES) {
                    error("Invalid WAL payload length $payloadLen at offset $recordOffset")
                }
                if (fileLength - readRaf.filePointer < payloadLen.toLong()) {
                    reportTornTail(recordOffset, fileLength, "incomplete payload")
                    break
                }
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

    /**
     * An append can be torn after a process crash even when every completed
     * frame was synced. Replay keeps the proven prefix available; the caller
     * can start and surface repair work instead of dying before its lifecycle
     * reaches ACTIVE. The original bytes remain untouched for forensics.
     */
    private fun reportTornTail(recordOffset: Long, fileLength: Long, detail: String) {
        System.err.println(
            "[APPEND-WAL] torn trailing frame path=${path.absolutePath} " +
                "offset=$recordOffset retainedPrefix=$recordOffset tailBytes=${fileLength - recordOffset}: $detail"
        )
    }
}
