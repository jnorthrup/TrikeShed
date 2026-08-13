package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.AppendWal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import kotlin.coroutines.CoroutineContext

/**
 * JVM actual for [AppendWal] — RandomAccessFile guarded by a JVM path lock and
 * an operating-system file lock.
 *
 * File layout:
 *   header: MAGIC (4 bytes BE) + VERSION (4 bytes BE)
 *   legacy record: keyLen(4 >= 0) + keyBytes + payloadLen(4) + payloadBytes
 *   committed record: marker(4 < 0) + keyLen(4) + payloadLen(4) + keyBytes
 *                     + payloadBytes + crc32(4) + commitSentinel(8)
 *
 * CoroutineContext.Element so it registers into the CCEK scope.
 * Append positioning, frame write, and force happen under one exclusive
 * FileChannel lock. The JVM path lock prevents OverlappingFileLockException
 * between multiple instances in one process; the channel lock serializes
 * independent processes.
 */
class JvmAppendWal(private val path: File) : AppendWal {
    companion object {
        private const val MAGIC = 0xCA05A101.toInt()
        private const val VERSION = 1
        private const val HEADER_BYTES = 8L
        private const val COMMITTED_FRAME_MARKER = -0x4A57414C
        private const val COMMIT_SENTINEL = 0x54534A57414C434DL
        /** A corrupt frame must not allocate attacker-sized arrays during replay. */
        private const val MAX_RECORD_BYTES = 64 * 1024 * 1024

        private val processPathLocks = ConcurrentHashMap<String, ReentrantLock>()
    }

    override val key: CoroutineContext.Key<*> get() = AppendWal

    private val lockPath = path.canonicalFile.path
    private val processPathLock = processPathLocks.computeIfAbsent(lockPath) { ReentrantLock() }
    /** Last frame boundary validated by this handle; other handles may extend it. */
    private var verifiedThrough = HEADER_BYTES
    private val raf: RandomAccessFile = RandomAccessFile(path, "rw").also { initHeader(it) }

    private fun initHeader(raf: RandomAccessFile) {
        processPathLock.lock()
        try {
            raf.channel.lock().use {
                val length = raf.channel.size()
                when {
                    length == 0L -> {
                        raf.seek(0L)
                        raf.writeInt(MAGIC)
                        raf.writeInt(VERSION)
                        raf.channel.force(true)
                        syncParentDirectory()
                    }
                    length < HEADER_BYTES -> error("Incomplete WAL header at $lockPath: $length bytes")
                    else -> {
                        raf.seek(0L)
                        validateHeader(raf)
                    }
                }
            }
        } finally {
            processPathLock.unlock()
        }
    }

    override suspend fun append(key: String, payload: ByteArray): Long {
        val keyBytes = key.encodeToByteArray()
        require(keyBytes.size <= MAX_RECORD_BYTES) { "WAL key exceeds $MAX_RECORD_BYTES bytes" }
        require(payload.size <= MAX_RECORD_BYTES) { "WAL payload exceeds $MAX_RECORD_BYTES bytes" }
        return withContext(Dispatchers.IO) {
            processPathLock.lock()
            try {
                raf.channel.lock().use {
                    repairTornTailLocked()
                    val offset = raf.channel.size()
                    raf.seek(offset)
                    raf.writeInt(COMMITTED_FRAME_MARKER)
                    raf.writeInt(keyBytes.size)
                    raf.writeInt(payload.size)
                    raf.write(keyBytes)
                    raf.write(payload)
                    raf.writeInt(frameCrc(keyBytes, payload))
                    // The body must be durable before its final commit marker.
                    // A crash between these forces leaves an uncommitted tail,
                    // which the next append truncates back to [offset].
                    raf.channel.force(false)
                    raf.writeLong(COMMIT_SENTINEL)
                    raf.channel.force(true)
                    verifiedThrough = raf.channel.size()
                    offset
                }
            } finally {
                processPathLock.unlock()
            }
        }
    }

    override fun replay(): Sequence<Pair<String, ByteArray>> = sequence {
        val readRaf = RandomAccessFile(path, "r")
        try {
            // Snapshot the extent and header while excluding appenders. Each
            // record is then read under a short shared lock: this keeps replay
            // lazy without racing a different process that repairs a torn tail.
            val snapshotLength = processPathLock.run {
                lock()
                try {
                    readRaf.channel.lock(0L, Long.MAX_VALUE, true).use {
                        val length = readRaf.channel.size()
                        if (length >= HEADER_BYTES) {
                            readRaf.seek(0L)
                            validateHeader(readRaf)
                        }
                        length
                    }
                } finally {
                    unlock()
                }
            }
            check(snapshotLength >= HEADER_BYTES) {
                "Incomplete WAL header at $lockPath: $snapshotLength bytes"
            }

            while (true) {
                val frame = processPathLock.run {
                    lock()
                    try {
                        readRaf.channel.lock(0L, Long.MAX_VALUE, true).use {
                            val currentLength = minOf(snapshotLength, readRaf.channel.size())
                            check(readRaf.filePointer <= currentLength) {
                                "WAL truncated before proven prefix at ${readRaf.filePointer} (length=$currentLength)"
                            }
                            readNextFrame(readRaf, currentLength)
                        }
                    } finally {
                        unlock()
                    }
                }
                when (frame) {
                    FrameRead.End -> break
                    is FrameRead.Torn -> {
                        reportTornTail(frame.recordOffset, frame.fileLength, frame.detail)
                        break
                    }
                    is FrameRead.Complete -> yield(frame.key to frame.payload)
                }
            }
        } finally {
            readRaf.close()
        }
    }

    /**
     * Scan only the extent this handle has not yet validated. The exclusive
     * channel lock is already held. Structural tail damage is repairable;
     * committed-frame checksum failures are corruption and stop the append.
     */
    private fun repairTornTailLocked() {
        val fileLength = raf.channel.size()
        if (verifiedThrough !in HEADER_BYTES..fileLength) verifiedThrough = HEADER_BYTES
        raf.seek(verifiedThrough)

        while (true) {
            when (val frame = readNextFrame(raf, fileLength)) {
                FrameRead.End -> return
                is FrameRead.Complete -> verifiedThrough = raf.filePointer
                is FrameRead.Torn -> {
                    reportTornTail(frame.recordOffset, frame.fileLength, frame.detail)
                    raf.channel.truncate(frame.recordOffset)
                    raf.channel.force(true)
                    verifiedThrough = frame.recordOffset
                    return
                }
            }
        }
    }

    private fun readNextFrame(input: RandomAccessFile, fileLength: Long): FrameRead {
        val recordOffset = input.filePointer
        if (recordOffset == fileLength) return FrameRead.End
        if (fileLength - recordOffset < Int.SIZE_BYTES) {
            return FrameRead.Torn(recordOffset, fileLength, "missing frame marker/key length")
        }

        val markerOrKeyLength = input.readInt()
        return when {
            markerOrKeyLength == COMMITTED_FRAME_MARKER ->
                readCommittedFrame(input, recordOffset, fileLength)
            markerOrKeyLength >= 0 ->
                readLegacyFrame(input, markerOrKeyLength, recordOffset, fileLength)
            else -> corrupt(recordOffset, "unknown frame marker $markerOrKeyLength")
        }
    }

    private fun readLegacyFrame(
        input: RandomAccessFile,
        keyLength: Int,
        recordOffset: Long,
        fileLength: Long,
    ): FrameRead {
        validateLength("key", keyLength, recordOffset)
        if (fileLength - input.filePointer < keyLength.toLong() + Int.SIZE_BYTES) {
            return FrameRead.Torn(recordOffset, fileLength, "incomplete legacy key or payload length")
        }
        val keyBytes = ByteArray(keyLength)
        input.readFully(keyBytes)

        val payloadLength = input.readInt()
        validateLength("payload", payloadLength, recordOffset)
        if (fileLength - input.filePointer < payloadLength.toLong()) {
            return FrameRead.Torn(recordOffset, fileLength, "incomplete legacy payload")
        }
        val payload = ByteArray(payloadLength)
        input.readFully(payload)
        return FrameRead.Complete(keyBytes.decodeToString(), payload)
    }

    private fun readCommittedFrame(
        input: RandomAccessFile,
        recordOffset: Long,
        fileLength: Long,
    ): FrameRead {
        if (fileLength - input.filePointer < 2L * Int.SIZE_BYTES) {
            return FrameRead.Torn(recordOffset, fileLength, "missing committed-frame lengths")
        }
        val keyLength = input.readInt()
        val payloadLength = input.readInt()
        validateLength("key", keyLength, recordOffset)
        validateLength("payload", payloadLength, recordOffset)

        val remainingFrameBytes = keyLength.toLong() + payloadLength + Int.SIZE_BYTES + Long.SIZE_BYTES
        if (fileLength - input.filePointer < remainingFrameBytes) {
            return FrameRead.Torn(recordOffset, fileLength, "incomplete committed frame")
        }

        val keyBytes = ByteArray(keyLength)
        val payload = ByteArray(payloadLength)
        input.readFully(keyBytes)
        input.readFully(payload)
        val storedCrc = input.readInt()
        val sentinel = input.readLong()
        if (sentinel != COMMIT_SENTINEL) {
            if (input.filePointer == fileLength) {
                return FrameRead.Torn(recordOffset, fileLength, "missing commit sentinel")
            }
            corrupt(recordOffset, "invalid commit sentinel before later frames")
        }
        val actualCrc = frameCrc(keyBytes, payload)
        if (storedCrc != actualCrc) {
            corrupt(
                recordOffset,
                "CRC mismatch: stored=${storedCrc.toUInt()} actual=${actualCrc.toUInt()}",
            )
        }
        return FrameRead.Complete(keyBytes.decodeToString(), payload)
    }

    private fun frameCrc(keyBytes: ByteArray, payload: ByteArray): Int {
        val metadata = ByteBuffer.allocate(3 * Int.SIZE_BYTES)
            .putInt(COMMITTED_FRAME_MARKER)
            .putInt(keyBytes.size)
            .putInt(payload.size)
            .array()
        return CRC32().run {
            update(metadata)
            update(keyBytes)
            update(payload)
            value.toInt()
        }
    }

    private fun validateHeader(input: RandomAccessFile) {
        val magic = input.readInt()
        val version = input.readInt()
        check(magic == MAGIC && version == VERSION) {
            "Invalid WAL header: magic=$magic version=$version"
        }
    }

    /** Persist the WAL directory entry after its initial header is forced. */
    private fun syncParentDirectory() {
        val parent = requireNotNull(path.absoluteFile.parentFile) {
            "WAL path has no parent directory: $lockPath"
        }
        FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { directory ->
            directory.force(true)
        }
    }

    private fun validateLength(field: String, length: Int, recordOffset: Long) {
        if (length < 0 || length > MAX_RECORD_BYTES) {
            corrupt(recordOffset, "invalid $field length $length")
        }
    }

    private fun corrupt(recordOffset: Long, detail: String): Nothing =
        error("Corrupt WAL frame at $lockPath offset=$recordOffset: $detail")

    private sealed interface FrameRead {
        data object End : FrameRead
        data class Complete(val key: String, val payload: ByteArray) : FrameRead
        data class Torn(val recordOffset: Long, val fileLength: Long, val detail: String) : FrameRead
    }

    override fun close() {
        processPathLock.lock()
        try {
            raf.close()
        } finally {
            processPathLock.unlock()
        }
    }

    /**
     * An append can be torn after a process crash even when every completed
     * frame was synced. Replay keeps the proven prefix available without
     * mutation. Before a later append, the same diagnosis is made under the
     * exclusive writer lock and the uncommitted tail is truncated durably.
     */
    private fun reportTornTail(recordOffset: Long, fileLength: Long, detail: String) {
        System.err.println(
            "[APPEND-WAL] torn trailing frame path=${path.absolutePath} " +
                "offset=$recordOffset retainedPrefix=$recordOffset tailBytes=${fileLength - recordOffset}: $detail"
        )
    }
}
