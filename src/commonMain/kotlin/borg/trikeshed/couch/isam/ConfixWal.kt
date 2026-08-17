package borg.trikeshed.couch.isam

import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.src
import borg.trikeshed.lib.toArray
import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.file.Path
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * Append-only Write-Ahead Log for Confix Documents.
 * Serves as the durability layer before compaction into the ISAM K-V Stringpool layout.
 */
class ConfixWal(
    val walFileLocation: String,
    val fileOps: FileOperations
) {
    private var sequenceNumber: Long = 0L

    /**
     * Appends a document mutation to the WAL.
     * @param id The document ID (e.g. CID or user key)
     * @param rev The document revision
     * @param doc The actual ConfixDoc to append
     * @return The monotonic sequence number of this mutation
     */
    fun append(id: String, rev: String, doc: ConfixDoc): Long {
        val idBytes = id.encodeToByteArray()
        val revBytes = rev.encodeToByteArray()
        val docBytes = doc.src.toArray()

        val out = ByteBuffer.allocate(8 + 4 + idBytes.size + 4 + revBytes.size + 4 + docBytes.size)
        out.putLong(sequenceNumber)
        out.putInt(idBytes.size)
        out.put(idBytes)
        out.putInt(revBytes.size)
        out.put(revBytes)
        out.putInt(docBytes.size)
        out.put(docBytes)
        out.flip()

        // Use NIO Files API for true append instead of O(N^2) FileOperations mock
        try {
            val path = Path.of(walFileLocation)
            val channel = Files.newByteChannel(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
            channel.use { ch ->
                while (out.hasRemaining()) {
                    ch.write(out)
                }
            }
        } catch (e: Exception) {
            // Fallback for non-NIO environments (e.g. JS/Browser) using FileOperations
            val bytes = out.array().sliceArray(0 until out.limit())
            val existing = if (fileOps.exists(walFileLocation)) fileOps.readAllBytes(walFileLocation) else ByteArray(0)
            fileOps.write(walFileLocation, existing + bytes)
        }

        sequenceNumber++
        return sequenceNumber
    }

    /**
     * Replays the WAL to rebuild the in-memory or on-disk MemTable/ISAM state.
     */
    fun replay(bridge: ConfixIsamCursorBridge) {
        if (!fileOps.exists(walFileLocation)) return
        val bytes = fileOps.readAllBytes(walFileLocation)
        val buf = ByteBuffer.wrap(bytes)
        while (buf.remaining() >= 8 + 4 + 4 + 4) {
            val seq = buf.getLong()
            val idLen = buf.getInt()
            if (buf.remaining() < idLen) break
            val idBytes = ByteArray(idLen)
            buf.get(idBytes)

            if (buf.remaining() < 4) break
            val revLen = buf.getInt()
            if (buf.remaining() < revLen) break
            val revBytes = ByteArray(revLen)
            buf.get(revBytes)

            if (buf.remaining() < 4) break
            val docLen = buf.getInt()
            if (buf.remaining() < docLen) break
            val docBytes = ByteArray(docLen)
            buf.get(docBytes)

            val offset = bridge.stringpool.put(docBytes.decodeToString())
            bridge.index.put(idBytes.decodeToString(), offset)
            sequenceNumber = seq + 1
        }
    }

    /**
     * Truncates the WAL after a successful compaction to ISAM disk.
     */
    fun checkpoint() {
        sequenceNumber = 0L
        if (fileOps.exists(walFileLocation)) {
            fileOps.deleteRecursively(walFileLocation)
        }
    }
}
