package borg.literbike.betanet

/**
 * ISAM Index - Direct offset-based indexing.
 * Ported from literbike/src/betanet/isam_index.rs.
 *
 * No B-trees, no hash tables, just direct array indexing.
 */

/**
 * Direct offset index entry - file position and key.
 */
data class IndexEntry(
    var offset: Long,
    var key: Long
)

/**
 * ISAM index over data files.
 * Simple array of file positions.
 */
class ISAMIndex {
    private val entries = mutableListOf<IndexEntry>()
    private var count: Int = 0
    private var capacity: Int = 0

    fun initEmpty(maxCapacity: Int = 65536): Result<Unit> {
        capacity = maxCapacity
        count = 0
        entries.clear()
        return Result.success(Unit)
    }

    /** Direct key lookup - O(1) if keys are dense */
    fun get(key: Long): Long? {
        if (key >= count) return null
        val entry = entries[key.toInt()]
        if (entry.key != key) return null // Key mismatch - sparse or deleted entry
        return entry.offset
    }

    /** Insert or update index entry */
    fun put(key: Long, dataOffset: Long): Result<Unit> {
        if (key >= capacity) {
            return Result.failure(Exception("Key exceeds index capacity"))
        }

        // Extend entries list if needed
        while (entries.size <= key.toInt()) {
            entries.add(IndexEntry(0, 0))
        }

        entries[key.toInt()] = IndexEntry(dataOffset, key)

        if (key >= count) {
            count = (key + 1).toInt()
        }

        return Result.success(Unit)
    }

    /** Linear scan for non-dense keys (fallback) */
    fun scanForKey(targetKey: Long): Long? {
        for (i in 0 until count) {
            val entry = entries[i]
            if (entry.key == targetKey) {
                return entry.offset
            }
        }
        return null
    }

    /** Get all entries as iterator */
    fun entries(): Sequence<Pair<Long, Long>> = sequence {
        for (i in 0 until count) {
            val entry = entries[i]
            yield(entry.key to entry.offset)
        }
    }

    /** Compact index by removing gaps */
    fun compact(): Result<Unit> {
        var writeIdx = 0

        val compacted = mutableListOf<IndexEntry>()
        for (readIdx in 0 until count) {
            val entry = entries[readIdx]
            if (entry.offset != 0L) { // Non-null entry
                if (writeIdx < compacted.size) {
                    compacted[writeIdx] = entry
                } else {
                    compacted.add(entry)
                }
                writeIdx++
            }
        }

        entries.clear()
        entries.addAll(compacted)
        count = writeIdx
        return Result.success(Unit)
    }

    /** Sort index by key for better cache locality */
    fun sort(): Result<Unit> {
        if (count <= 1) return Result.success(Unit)
        entries.sortBy { it.key }
        return Result.success(Unit)
    }

    /** Binary search for sorted index */
    fun binarySearch(key: Long): Long? {
        if (count == 0) return null
        val index = entries.binarySearchBy(key) { it.key }
        return if (index >= 0) entries[index].offset else null
    }

    /** Get current entry count */
    fun len(): Int = count

    /** Check if index is empty */
    fun isEmpty(): Boolean = count == 0

    /** Get index capacity */
    fun capacity(): Int = capacity
}

/**
 * High-level interface combining data cursor and index.
 */
class ISAMTable(
    private val dataCursor: MmapCursor,
    private val indexCursor: MmapCursor,
    val index: ISAMIndex = ISAMIndex()
) {
    companion object {
        /** Create new ISAM table with data and index files */
        fun create(dataPath: String, indexPath: String, recordSize: Int): Result<ISAMTable> {
            return runCatching {
                val dataCursor = MmapCursor(dataPath, indexPath)
                dataCursor.initHeader(recordSize)
                val index = ISAMIndex()
                index.initEmpty()
                val indexCursor = MmapCursor(indexPath, "$indexPath.tmp")
                ISAMTable(dataCursor, indexCursor, index)
            }
        }
    }

    /** Insert record with automatic indexing */
    fun insert(key: Long, record: ByteArray): Result<Unit> {
        // Check if key already exists (idempotent update)
        val existingOffset = index.get(key)
        if (existingOffset != null) {
            // Update existing record
            val header = dataCursor.header()
            val dataOffset = header.dataOffset + (key * header.recordSize)
            dataCursor.seek(dataOffset)?.put(record)
        } else {
            // Append new record
            val recordIndex = dataCursor.append(record).getOrThrow()
            val header = dataCursor.header()
            val dataOffset = header.dataOffset + (recordIndex * header.recordSize)
            index.put(key, dataOffset).getOrThrow()
        }
        return Result.success(Unit)
    }

    /** Get record by key */
    fun get(key: Long): ByteArray? {
        val offset = index.get(key) ?: return null
        return dataCursor.seek(offset)?.let { buf ->
            val arr = ByteArray(buf.remaining())
            buf.get(arr)
            arr
        }
    }

    /** Sync both data and index to disk */
    fun sync(): Result<Unit> {
        dataCursor.sync()
        indexCursor.sync()
        return Result.success(Unit)
    }

    fun close() {
        dataCursor.close()
        indexCursor.close()
    }
}
