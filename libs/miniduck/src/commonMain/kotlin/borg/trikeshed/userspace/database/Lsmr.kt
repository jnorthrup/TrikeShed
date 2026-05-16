package borg.trikeshed.userspace.database

import borg.trikeshed.lib.mutable.SeriesBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Log-Structured Merge-Tree (LSMR) database.
 *
 * Architecture:
 *   - memtable: mutable in-memory write buffer (SeriesBuffer of key/value pairs)
 *   - segments: immutable in-memory segments (SeriesBuffer of SeriesBuffer of key/value pairs)
 *   - segmentFiles: filenames for persisted segments
 *
 * Writes go to memtable. On threshold, memtable is flushed to a new segment
 * and optionally persisted to disk. Reads check memtable then segments newest-first.
 */
data class LsmrConfig(
    val path: CharSequence,
    val memtableThreshold: Int,
    val maxSegments: Int? = null,
)

class LsmrDatabase(val config: LsmrConfig) {
    private val memtable: SeriesBuffer<Pair<CharSequence, ByteArray>> = SeriesBuffer(8)
    private var memtableSize = 0
    private val segments: SeriesBuffer<SeriesBuffer<Pair<CharSequence, ByteArray>>> = SeriesBuffer(4)
    private val segmentFiles: SeriesBuffer<CharSequence> = SeriesBuffer(4)
    private val mutex = Mutex()

    suspend fun put(id: CharSequence, value: ByteArray) {
        mutex.withLock {
            // Remove existing key if present (LSM update semantics)
            for (i in 0 until memtable.a) {
                if (memtable.b(i).first == id) {
                    memtableSize -= memtable.b(i).second.size
                    memtable.set(i, id to value)
                    memtableSize += value.size
                    if (memtableSize >= config.memtableThreshold) flushMemtable()
                    return
                }
            }
            // New key
            memtable.add(id to value)
            memtableSize += value.size
            if (memtableSize >= config.memtableThreshold) flushMemtable()
        }
    }

    suspend fun get(id: CharSequence): ByteArray? {
        mutex.withLock {
            // Check memtable
            for (i in 0 until memtable.a) {
                val (k, v) = memtable.b(i)
                if (k == id) return v
            }
            // Search segments newest-first
            for (segIndex in segments.a - 1 downTo 0) {
                val seg = segments.b(segIndex)
                for (i in 0 until seg.a) {
                    val (k, v) = seg.b(i)
                    if (k == id) return v
                }
            }
            // Search disk segments newest-first
            if (config.path.isNotBlank()) {
                for (fIndex in segmentFiles.a - 1 downTo 0) {
                    val v = loadFromDisk(config.path, segmentFiles.b(fIndex), id)
                    if (v != null) return v
                }
            }
            return null
        }
    }

    private suspend fun flushMemtable() {
        if (memtable.a == 0) return
        // Snapshot memtable into a new immutable segment
        val newSegment: SeriesBuffer<Pair<CharSequence, ByteArray>> = SeriesBuffer(memtable.a)
        for (i in 0 until memtable.a) newSegment.add(memtable.b(i))
        segments.add(newSegment)
        memtableSize = 0
        // Clear memtable by rebuilding as empty buffer
        val fresh: SeriesBuffer<Pair<CharSequence, ByteArray>> = SeriesBuffer(8)
        // Transfer reference (swap internally)
        memtable.clear()
        // Persist if path provided
        if (config.path.isNotBlank()) {
            val fname = "segment-${kotlin.random.Random.Default.nextLong()}-${segments.a}.seg"
            segmentFiles.add(fname)
            persistToDisk(config.path, fname, newSegment)
        }
        compactIfNeeded()
    }

    private fun compactIfNeeded() {
        val max = config.maxSegments ?: return
        while (segments.a > max) {
            segments.set(0, segments.b(1))
            // rebuild without first element
            val remaining: SeriesBuffer<SeriesBuffer<Pair<CharSequence, ByteArray>>> = SeriesBuffer(segments.a - 1)
            for (i in 1 until segments.a) remaining.add(segments.b(i))
            segments.clear()
            // re-add remaining
            for (i in 0 until remaining.a) segments.add(remaining.b(i))
        }
        while (segmentFiles.a > max) {
            val removed = segmentFiles.b(0)
            segmentFiles.set(0, segmentFiles.b(1))
            // rebuild without first element
            val remaining: SeriesBuffer<CharSequence> = SeriesBuffer(segmentFiles.a - 1)
            for (i in 1 until segmentFiles.a) remaining.add(segmentFiles.b(i))
            segmentFiles.clear()
            for (i in 0 until remaining.a) segmentFiles.add(remaining.b(i))
            try { deleteSegmentFile(config.path, removed) } catch (_: Throwable) {}
        }
    }

    private suspend fun persistToDisk(path: CharSequence, fname: CharSequence, seg: SeriesBuffer<Pair<CharSequence, ByteArray>>) {
        // Stub — platform code provides the actual implementation
    }

    private suspend fun loadFromDisk(path: CharSequence, fname: CharSequence, id: CharSequence): ByteArray? {
        // Stub — platform code provides the actual implementation
        return null
    }

    private fun deleteSegmentFile(path: CharSequence, fname: CharSequence) {
        // Stub — platform code provides the actual implementation
    }
}
