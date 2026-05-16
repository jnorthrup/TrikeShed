package borg.trikeshed.miniduck.userspace.database

import borg.trikeshed.lib.*
import borg.trikeshed.lib.mutable.SeriesBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LsmrDatabase(val config: LsmrConfig) {
    private val memtable: SeriesBuffer<Pair<CharSequence, ByteArray>> = SeriesBuffer()
    var memtableSize = 0
    private val segments: SeriesBuffer<SeriesBuffer<Pair<CharSequence, ByteArray>>> = SeriesBuffer()
    private val segmentFiles: SeriesBuffer<CharSequence> = SeriesBuffer()
    private val mutex = Mutex()

    suspend fun put(id: CharSequence, value: ByteArray) {
        mutex.withLock {
            val prev = memtable.view.find { it.first == id }
            if (prev != null) memtableSize -= prev.second.size
            memtable.view.filter { it.first != id }
            memtable.add(id to value)
            memtableSize += value.size

            if (memtableSize >= config.memtableThreshold) {
                flushMemtable()
            }
        }
    }

    suspend fun get(id: CharSequence): ByteArray? {
        mutex.withLock {
            return memtable.view.find { it.first == id }?.second
        }
    }

    private suspend fun flushMemtable() {
        if (memtable.view.isEmpty()) return
        val snapshot: SeriesBuffer<Pair<CharSequence, ByteArray>> = SeriesBuffer()
        memtable.view.forEach { snapshot.add(it) }
        segments.add(snapshot)
        segmentFiles.add("segment_${segments.view.size}.dat")
        memtable.clear()
        memtableSize = 0
    }
}

data class LsmrConfig(
    val memtableThreshold: Int = 1024 * 1024,
    val maxSegments: Int = 10,
)
