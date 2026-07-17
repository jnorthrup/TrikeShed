package borg.trikeshed.couch.isam

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.collections.FunnelHashMap

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
    // For now, we simulate the offset generation and storage.
    private var currentOffset = 0
    private val memoryCache = mutableMapOf<Int, String>()
    private val memoizedStrings = FunnelHashMap<String, Int>()

    override fun put(value: String): Int {
        val existingOffset = memoizedStrings.get(value)
        if (existingOffset != null) {
            return existingOffset
        }

        val offset = currentOffset
        val bytes = value.encodeToByteArray()
        // Simulate writing bytes to `location`
        memoryCache[offset] = value
        memoizedStrings.put(value, offset)
        currentOffset += bytes.size
        return offset
    }

    override fun get(offset: Int): String? {
        // Simulate reading from `location` at `offset`
        return memoryCache[offset]
    }
}
