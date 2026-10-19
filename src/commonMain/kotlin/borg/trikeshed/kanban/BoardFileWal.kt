package borg.trikeshed.kanban

import borg.trikeshed.couch.isam.WalFrame
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability

/** Legacy WAL1 frames over positional userspace I/O; the board element owns serialization and batches. */
class BoardFileWal internal constructor(
    private val channel: FileChannel,
    override val durability: StorageDurability,
    private val syncParent: () -> Unit,
) : BoardWalPort {
    constructor(files: FileOperations, directory: String) : this(open(files, directory), files.durability)

    private constructor(opened: Join<FileChannel, () -> Unit>, durability: StorageDurability) :
        this(opened.a, durability, opened.b)

    private var end = 0L
    private var sequence = 0L
    private var closed = false
    private var failure: Throwable? = null

    companion object {
        private const val MAX_PAYLOAD = 10 * 1024 * 1024

        private fun open(files: FileOperations, directory: String): Join<FileChannel, () -> Unit> {
            val path = files.canonicalPath(files.resolvePath(directory))
            val created = mutableListOf<String>()
            if (files.durability == StorageDurability.DURABLE) {
                var ancestor = path
                while (!files.exists(ancestor)) {
                    created.add(ancestor)
                    val parent = files.resolvePath(ancestor, "..")
                    check(parent != ancestor) { "Board WAL directory has no existing ancestor: $path" }
                    ancestor = parent
                }
                check(files.isDir(ancestor)) { "Board WAL ancestor is not a directory: $ancestor" }
            }
            files.mkdirs(path)
            for (entry in created.asReversed()) syncDirectory(files.resolvePath(entry, ".."))
            val channel = FileChannel.open(files.resolvePath(path, "board.wal"),
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            val syncParent: () -> Unit = { syncDirectory(path) }
            return channel j syncParent
        }

        private fun syncDirectory(path: String) = FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    init {
        try {
            val limit = channel.size()
            while (end < limit) {
                val frame = readFrame(end, limit) ?: break
                check(frame.a > sequence) { "Non-monotonic board WAL sequence at $end" }
                sequence = frame.a
                end += WalFrame.HEADER_SIZE + frame.b.size + 4L
            }
            if (end < limit) channel.truncate(end)
            // Recovery and a newly created directory entry must settle before this owner is usable.
            channel.force(true)
            syncParent()
        } catch (caught: Throwable) {
            try { channel.close() } catch (cleanup: Throwable) { if (cleanup !== caught) caught.addSuppressed(cleanup) }
            throw caught
        }
    }

    override fun append(record: ByteArray): Long {
        checkUsable()
        require(record.size <= MAX_PAYLOAD) { "Board WAL payload exceeds $MAX_PAYLOAD bytes" }
        check(sequence < Long.MAX_VALUE) { "Board WAL sequence exhausted" }
        val next = sequence + 1
        val frame = WalFrame.encode(next, record)
        check(end <= Long.MAX_VALUE - frame.size) { "Board WAL offset overflow" }
        mutate { writeBytes(end, frame) }
        end += frame.size
        sequence = next
        return next
    }

    /** One explicit group flush, including the directory entry; no sync failure is discarded. */
    override fun flush() {
        checkUsable()
        mutate { channel.force(true); syncParent() }
    }

    override suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit) {
        checkUsable()
        val limit = end
        var offset = 0L
        var previous = 0L
        while (offset < limit) {
            val frame = mutate {
                checkNotNull(readFrame(offset, limit)) { "Board WAL frame disappeared at $offset" }.also {
                    check(it.a > previous) { "Non-monotonic board WAL sequence at $offset" }
                }
            }
            onRecord(frame.a, frame.b)
            previous = frame.a
            offset += WalFrame.HEADER_SIZE + frame.b.size + 4L
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        channel.close()
    }

    /** Only a short final header/payload is recoverable; a complete corrupt frame is an error. */
    private fun readFrame(offset: Long, limit: Long): Join<Long, ByteArray>? {
        if (limit - offset < WalFrame.HEADER_SIZE) return null
        val header = ByteArray(WalFrame.HEADER_SIZE)
        readBytes(offset, ByteBuffer.wrap(header))
        check(header.copyOfRange(0, 4).contentEquals(WalFrame.MAGIC)) { "Board WAL magic mismatch at $offset" }
        val fields = ByteBuffer.wrap(header)
        check(fields.getShort(4).toInt() == WalFrame.VERSION) { "Board WAL version mismatch at $offset" }
        val length = fields.getInt(14)
        check(length in 0..MAX_PAYLOAD) { "Invalid board WAL payload length at $offset: $length" }
        val size = WalFrame.HEADER_SIZE + length + 4
        if (size.toLong() > limit - offset) return null
        val bytes = ByteArray(size)
        header.copyInto(bytes)
        readBytes(offset, ByteBuffer.wrap(bytes).also { it.position(WalFrame.HEADER_SIZE) })
        check(WalFrame.validate(bytes)) { "Board WAL checksum mismatch at $offset" }
        return fields.getLong(6) j bytes.copyOfRange(WalFrame.HEADER_SIZE, WalFrame.HEADER_SIZE + length)
    }

    private fun readBytes(offset: Long, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val position = buffer.position()
            val count = channel.read(buffer, offset + position)
            check(count > 0 && buffer.position() == position + count) { "Board WAL read made no progress at ${offset + position}" }
        }
    }

    private fun writeBytes(offset: Long, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val position = buffer.position()
            val count = channel.write(buffer, offset + position)
            check(count > 0 && buffer.position() == position + count) { "Board WAL write made no progress at ${offset + position}" }
        }
    }

    private fun checkUsable() {
        check(!closed) { "Board WAL is closed" }
        check(failure == null) { "Board WAL failed; close and reopen before use: ${failure?.message}" }
    }

    private inline fun <T> mutate(block: () -> T): T = try { block() } catch (caught: Throwable) {
        failure = caught
        throw caught
    }
}
