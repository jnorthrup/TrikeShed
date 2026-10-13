package borg.trikeshed.couch.isam

import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.src
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.lib.toArray
import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.file.Path
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * Append-only Write-Ahead Log for Confix Documents.
 * Legacy framing has no syntax marker. Replay retains source bytes and revisions in the dense Confix store.
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
    fun replay(bridge: ConfixIsamCursorBridge, syntax: Syntax = Syntax.JSON) {
        if (!fileOps.exists(walFileLocation)) return
        val bytes = fileOps.readAllBytes(walFileLocation)
        val buf = ByteBuffer.wrap(bytes)
        // The legacy format has no syntax marker. Its caller must supply non-JSON syntax explicitly.
        // Validate the complete snapshot before publishing any conversion; never truncate a partial tail.
        val frames = mutableListOf<Triple<Long, Pair<String, String>, ByteArray>>()
        fun field(): ByteArray {
            require(buf.remaining() >= 4) { "Incomplete Confix WAL field length" }
            val size = buf.getInt()
            require(size >= 0 && size <= buf.remaining()) { "Invalid Confix WAL field length" }
            return ByteArray(size).also { buf.get(it) }
        }
        var previous = -1L
        while (buf.hasRemaining()) {
            require(buf.remaining() >= 20) { "Incomplete Confix WAL frame" }
            val seq = buf.getLong()
            require(seq >= 0 && seq > previous && seq < Long.MAX_VALUE) { "Invalid Confix WAL sequence" }
            val id = field().decodeToString(throwOnInvalidSequence = true)
            val rev = field().decodeToString(throwOnInvalidSequence = true)
            require(id.isNotEmpty() && rev.isNotEmpty()) { "Invalid Confix WAL identity" }
            frames.add(Triple(seq, id to rev, field()))
            previous = seq
        }
        for ((seq, identity, source) in frames) bridge.put(seq, identity.first, identity.second, source, syntax)
        sequenceNumber = previous + 1
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
