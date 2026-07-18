package borg.trikeshed.couch.isam

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.collections.FunnelHashMap
import borg.trikeshed.couch.isam.WalFrame

/**
 * A basic stringpool for storing and retrieving variable-length strings via integer offsets.
 * Conceptually identical to a blob store for varchars and JSON blobs.
 */
interface Stringpool {
    /** Appends a string to the pool and returns its byte offset/identifier. */
    fun put(value: String): Int

    /** Retrieves a string from the pool given its byte offset/identifier. */
    fun get(offset: Int): String?
}

/**
 * An in-memory/file-backed stringpool implementation.
 */
class FileBackedStringpool(
    val location: String,
    val fileOps: FileOperations
) : Stringpool {
    // In a full implementation, this uses functional uring or NIO byte channels to append
    // to a memory-mapped file or WAL block.

    private val memoizedStrings = FunnelHashMap<String, Int>()
    private var fd: Int = -1
    private var currentOffset: Int = 0
    private var isCorrupted: Boolean = false

    init {
        // Recover from location if it exists
        if (fileOps.exists(location)) {
            val bytes = fileOps.readAllBytes(location)
            var offset = 0
            while (offset < bytes.size) {
                // Read frame
                if (offset + WalFrame.HEADER_SIZE + 4 > bytes.size) {
                    isCorrupted = true
                    break
                }

                // Read payload length
                var len = 0
                for (i in 0 until 4) {
                    len = (len shl 8) or (bytes[offset + 14 + i].toInt() and 0xFF)
                }

                if (offset + WalFrame.HEADER_SIZE + len + 4 > bytes.size) {
                    isCorrupted = true
                    break
                }

                val frame = bytes.sliceArray(offset until offset + WalFrame.HEADER_SIZE + len + 4)
                if (WalFrame.validate(frame)) {
                    val payload = frame.sliceArray(WalFrame.HEADER_SIZE until WalFrame.HEADER_SIZE + len)
                    val str = payload.decodeToString()
                    memoizedStrings.put(str, offset)
                    offset += frame.size
                    currentOffset = offset
                } else {
                    isCorrupted = true
                    break // Stop recovery on corruption
                }
            }

            // Truncate if there was corruption
            if (isCorrupted && offset < bytes.size) {
                fileOps.write(location, bytes.sliceArray(0 until offset))
            }
        }

        // Open for appending later
    }

    private fun ensureOpen() {
        if (fd == -1) {
            if (!fileOps.exists(location)) {
                fileOps.write(location, ByteArray(0))
            }
            fd = fileOps.open(location, readOnly = false)
        }
    }

    override fun put(value: String): Int {
        val existingOffset = memoizedStrings.get(value)
        if (existingOffset != null) {
            return existingOffset
        }

        // Bounded record size - let's say max 10MB as in JvmDurableAppendLog?
        val payload = value.encodeToByteArray()
        require(payload.size <= 10 * 1024 * 1024) { "String payload exceeds maximum size" }

        val offset = currentOffset
        val frame = WalFrame.encode(offset.toLong(), payload)

        // Append to file
        val bytes = if (fileOps.exists(location)) fileOps.readAllBytes(location) else ByteArray(0)
        val newBytes = ByteArray(bytes.size + frame.size)
        bytes.copyInto(newBytes)
        frame.copyInto(newBytes, bytes.size)
        fileOps.write(location, newBytes)

        memoizedStrings.put(value, offset)
        currentOffset += frame.size
        return offset
    }

    override fun get(offset: Int): String? {
        if (!fileOps.exists(location)) return null
        val bytes = fileOps.readAllBytes(location)
        if (offset >= bytes.size) return null

        // Check if there's enough space for header + crc
        if (offset + WalFrame.HEADER_SIZE + 4 > bytes.size) return null

        // Read length
        var len = 0
        for (i in 0 until 4) {
            len = (len shl 8) or (bytes[offset + 14 + i].toInt() and 0xFF)
        }

        if (offset + WalFrame.HEADER_SIZE + len + 4 > bytes.size) return null

        val frame = bytes.sliceArray(offset until offset + WalFrame.HEADER_SIZE + len + 4)
        if (WalFrame.validate(frame)) {
            val payload = frame.sliceArray(WalFrame.HEADER_SIZE until WalFrame.HEADER_SIZE + len)
            return payload.decodeToString()
        }
        return null
    }
}
