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
            return memtable[id]
            // In a real implementation, we would also check segments on disk
        }
    }

    private suspend fun flushMemtable() {
        // Implementation for flushing to disk
        memtable.clear()
        memtableSize = 0
    }
}
