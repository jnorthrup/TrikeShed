package borg.trikeshed.userspace.database

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Log-Structured Merge-Tree (LSMR) database ported from literbike.
 */

data class LsmrConfig(
    val path: String,
    val memtableThreshold: Int,
    val maxSegments: Int? = null
)

class LsmrDatabase(val config: LsmrConfig) {
    private val memtable = mutableMapOf<String, ByteArray>()
    private var memtableSize = 0
    private val segments = mutableListOf<MutableMap<String, ByteArray>>()
    private val mutex = Mutex()

    suspend fun put(id: String, value: ByteArray) {
        mutex.withLock {
            val prev = memtable.put(id, value)
            if (prev != null) memtableSize -= prev.size
            memtableSize += value.size

            if (memtableSize >= config.memtableThreshold) {
                flushMemtable()
            }
        }
    }

    suspend fun get(id: String): ByteArray? {
        mutex.withLock {
            memtable[id]?.let { return it }
            // Search segments newest-first
            for (seg in segments.asReversed()) {
                seg[id]?.let { return it }
            }
            return null
        }
    }

    private fun compactIfNeeded() {
        config.maxSegments?.let { max ->
            while (segments.size > max) {
                // drop oldest segments first
                segments.removeAt(0)
            }
        }
    }

    private suspend fun flushMemtable() {
        if (memtable.isEmpty()) return
        // Move current memtable to a new in-memory segment
        val newSegment = memtable.toMutableMap()
        segments.add(newSegment)
        compactIfNeeded()
        memtable.clear()
        memtableSize = 0
    }
}
