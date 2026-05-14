package borg.trikeshed.userspace.database

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedList

/**
 * Log-Structured Merge-Tree (LSMR) database ported from literbike.
 */

data class LsmrConfig(
    val path: CharSequence,
    val memtableThreshold: Int,
    val maxSegments: Int? = null
)

class LsmrDatabase(val config: LsmrConfig) {
   val memtable = LinkedHashMap<CharSequence, ByteArray>()
   var memtableSize = 0
   val segments = LinkedList<LinkedHashMap<CharSequence, ByteArray>>()
   val segmentFiles = LinkedList<CharSequence>()
   val mutex = Mutex()

    suspend fun put(id: CharSequence, value: ByteArray) {
        mutex.withLock {
            val prev = memtable.put(id, value)
            if (prev != null) memtableSize -= prev.size
            memtableSize += value.size

            if (memtableSize >= config.memtableThreshold) {
                flushMemtable()
            }
        }
    }

    suspend fun get(id: CharSequence): ByteArray? {
        mutex.withLock {
            memtable[id]?.let { return it }
            // Search in-memory segments newest-first
            for (seg in segments.asReversed()) {
                seg[id]?.let { return it }
            }
            // Search on-disk segment files newest-first (if path provided)
            if (config.path.isNotBlank()) {
                for (fname in segmentFiles.asReversed()) {
                    val v = loadKeyFromSegment(config.path, fname, id)
                    if (v != null) return v
                }
            }
            return null
        }
    }

   fun compactIfNeeded() {
        config.maxSegments?.let { max ->
            while (segments.size > max) {
                // drop oldest segments first
                segments.removeAt(0)
            }
            // if we also limit segmentFiles, drop oldest files
            if (config.path.isNotBlank()) {
                while (segmentFiles.size > max) {
                    // best-effort: delete file if exists (platform impl may ignore)
                    val removed = segmentFiles.removeAt(0)
                    try { deleteSegmentFile(config.path, removed) } catch (_: Throwable) {}
                }
            }
        }
    }

   suspend fun flushMemtable() {
        if (memtable.isEmpty()) return
        // Move current memtable to a new in-memory segment
        val newSegment = memtable.toMutableMap()
        segments.add(newSegment)

        // Persist to disk if path provided
        if (config.path.isNotBlank()) {
            // commonMain cannot reference java.lang.System; use a multiplatform-safe
            // pseudo-unique id instead of currentTimeMillis. Random long is
            // sufficient for generating a unique filename in this small scope.
            val fname = "segment-${kotlin.random.Random.Default.nextLong()}-${segments.size}.seg"
            segmentFiles.add(fname)
            persistSegmentToDisk(config.path, fname, newSegment)
        }

        compactIfNeeded()
        memtable.clear()
        memtableSize = 0
    }
}

