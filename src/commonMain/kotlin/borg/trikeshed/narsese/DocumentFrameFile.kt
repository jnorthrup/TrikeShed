package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.WalFrame
import borg.trikeshed.lib.Closeable
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption

internal enum class DocumentFileKind(val header: String) { CAS("DOC-CAS1"), LOG("DOC-LOG1") }

internal data class DocumentFrame(val offset: Long, val sequence: Long, val length: Int) {
    val end: Long get() = offset + WalFrame.HEADER_SIZE + length + 4L
}

/**
 * Positional SQ/CQ adapter; its CAS/log owner serializes access to this channel.
 * No worker is blocked on a scoped coroutine bridge and no multi-process lock is claimed.
 * A complete corrupt frame is an error; only a short final frame is recoverable.
 */
internal class DocumentFrameFile private constructor(
    private val channel: FileChannel,
    private val maxPayload: Int,
) : Closeable {
    var end: Long = HEADER_SIZE.toLong()
        private set
    var sequence: Long = 0
        private set
    private var failed: Throwable? = null
    private var closed = false

    companion object {
        const val HEADER_SIZE = 8
        const val MAX_PAYLOAD = 64 * 1024 * 1024

        fun open(path: String, parentPath: String, kind: DocumentFileKind): DocumentFrameFile {
            val channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            return initialize(channel, kind) {
                // Also retry directory sync on reopen after a previous uncertain creation.
                FileChannel.open(parentPath, StandardOpenOption.READ).use { it.force(true) }
            }
        }

        internal fun initialize(
            channel: FileChannel,
            kind: DocumentFileKind,
            maxPayload: Int = MAX_PAYLOAD,
            syncParent: () -> Unit,
        ): DocumentFrameFile {
            require(maxPayload in 0..MAX_PAYLOAD)
            val file = DocumentFrameFile(channel, maxPayload)
            try {
                val header = kind.header.encodeToByteArray()
                val size = channel.size()
                if (size < HEADER_SIZE) {
                    val prefix = file.readBytes(0, size.toInt())
                    check(prefix.contentEquals(header.copyOf(prefix.size))) { "document file type/version mismatch" }
                    file.mutate {
                        channel.truncate(0)
                        file.writeBytes(0, header)
                        channel.force(true)
                    }
                } else {
                    check(file.readBytes(0, HEADER_SIZE).contentEquals(header)) { "document file type/version mismatch" }
                }
                val limit = channel.size()
                while (file.end < limit) {
                    val frame = file.readFrame(file.end, limit) ?: break
                    check(frame.a.sequence > file.sequence) { "non-monotonic document frame sequence" }
                    file.sequence = frame.a.sequence
                    file.end = frame.a.end
                }
                if (file.end < limit) file.mutate { channel.truncate(file.end); channel.force(true) }
                file.mutate(syncParent)
                return file
            } catch (failure: Throwable) {
                try { channel.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }
    }

    fun append(sequence: Long, payload: ByteArray): DocumentFrame {
        checkUsable()
        require(sequence > this.sequence) { "document frame sequence must increase" }
        require(payload.size <= maxPayload) { "document frame payload exceeds $maxPayload bytes" }
        val frame = DocumentFrame(end, sequence, payload.size)
        check(frame.end > end) { "document frame offset overflow" }
        mutate { writeBytes(end, WalFrame.encode(sequence, payload)) }
        end = frame.end
        this.sequence = sequence
        return frame
    }

    fun read(frame: DocumentFrame): ByteArray {
        checkUsable()
        return mutate {
            val found = readFrame(frame.offset, end) ?: error("document frame disappeared")
            check(found.a == frame) { "document frame index mismatch" }
            found.b
        }
    }

    fun frameAt(offset: Long): Join<DocumentFrame, ByteArray> {
        checkUsable()
        require(offset >= HEADER_SIZE && offset < end)
        return mutate { readFrame(offset, end) ?: error("document frame disappeared") }
    }

    fun flush() { checkUsable(); mutate { channel.force(true) } }

    /** Test hook required by DurableAppendLog; corrupt a complete frame, then fail closed. */
    fun corrupt(frame: DocumentFrame) {
        checkUsable()
        mutate {
            val offset = frame.end - 1
            val byte = readBytes(offset, 1)
            byte[0] = (byte[0].toInt() xor 1).toByte()
            writeBytes(offset, byte)
            channel.force(true)
        }
        failed = IllegalStateException("document frame deliberately corrupted")
    }

    override fun close() {
        if (closed) return
        closed = true
        channel.close()
    }

    private fun readFrame(offset: Long, limit: Long): Join<DocumentFrame, ByteArray>? {
        if (limit - offset < WalFrame.HEADER_SIZE) return null
        val header = readBytes(offset, WalFrame.HEADER_SIZE)
        check(header.copyOfRange(0, 4).contentEquals(WalFrame.MAGIC)) { "document WAL magic mismatch at $offset" }
        val buffer = ByteBuffer.wrap(header)
        check(buffer.getShort(4).toInt() == WalFrame.VERSION) { "document WAL version mismatch at $offset" }
        val sequence = buffer.getLong(6)
        val length = buffer.getInt(14)
        check(length in 0..maxPayload) { "document WAL payload length invalid at $offset: $length" }
        val frame = DocumentFrame(offset, sequence, length)
        if (frame.end > limit) return null
        val bytes = readBytes(offset, WalFrame.HEADER_SIZE + length + 4)
        check(WalFrame.validate(bytes)) { "document WAL checksum mismatch at $offset" }
        return frame j bytes.copyOfRange(WalFrame.HEADER_SIZE, WalFrame.HEADER_SIZE + length)
    }

    private fun readBytes(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val position = buffer.position()
            val count = channel.read(buffer, offset + position)
            check(count > 0 && buffer.position() == position + count) { "document read made no progress at ${offset + position}" }
        }
        return bytes
    }

    private fun writeBytes(offset: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val position = buffer.position()
            val count = channel.write(buffer, offset + position)
            check(count > 0 && buffer.position() == position + count) { "document write made no progress at ${offset + position}" }
        }
    }

    fun checkUsable() {
        check(!closed) { "document file is closed" }
        check(failed == null) { "document writer failed; close and reopen before use: ${failed?.message}" }
    }

    private inline fun <T> mutate(block: () -> T): T = try { block() } catch (failure: Throwable) {
        failed = failure
        throw failure
    }
}
