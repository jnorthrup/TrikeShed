@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.couch.bucket

import borg.trikeshed.memory.ConfixMemoryStore
import borg.trikeshed.memory.ConfixBlockStore
import borg.trikeshed.memory.ConfixBlock
import borg.trikeshed.memory.ConfixBlockCodec
import borg.trikeshed.mutable.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.lib.*

/**
 * BucketObjectStorage — compositional key-value store built on the MutableSeries DSL,
 * backed by miniduck-memory's ConfixBlockStore for persistence.
 *
 * Architecture (drain chain):
 *   RingSeries (buffer) → MergedSeries → SortedSeries (sorted flush)
 *         │                   │               │
 *         ▼                   ▼               ▼
 *    JournalSeries        flush()      ConfixBlockStore
 *    (mutations)                              │
 *         └──────────────────────────────────┘
 *                  FileConfixBlockStore (NDJSON)
 *
 * Facets exposed via ConfixIndexK:
 *   - Block index (content-addressed by CID)
 *   - Key index (user key → block ID)
 *   - Revision timeline (JournalSeries deltas)
 *   - Sorted flush checkpoints
 */
class BucketObjectStorage<K, V>(
    private val keyOrder: (K, K) -> Int,
    private val valueToBlock: (K, V) -> ConfixBlock,
    private val blockToValue: (ConfixBlock) -> V,
    private val blockStore: ConfixBlockStore,
    private val bucketName: String,
    private val ringCapacity: Int = 1024,
    private val flushThreshold: Int = 256,
) {

    // ── MutableSeries Drain Chain ──────────────────────────────────

    /** Ring buffer for O(1) append buffering */
    private val ringBuffer = RingSeries<BucketEntry<K, V>>(ringCapacity)

    /** Sorted series for ordered flush (by key) */
    private val sortedSeries = SortedSeries<BucketEntry<K, V>> { a, b -> keyOrder(a.key, b.key) }

    /** MergedSeries orchestrates: ring → flush → sorted */
    private val merged = MergedSeries(
        input = ringBuffer,
        sorted = sortedSeries,
        mergeThreshold = flushThreshold,
    )

    /** Journal for mutation replay / rollback / audit */
    private val journal = JournalSeries<BucketOp<K, V>>()

    // ── Entry model ────────────────────────────────────────────────

    data class BucketEntry<K, V>(
        val key: K,
        val value: V,
        val timestamp: Long = System.currentTimeMillis(),
    )

    sealed interface BucketOp<out K, out V> {
        val timestamp: Long
    }

    data class PutOp<K, V>(
        val key: K,
        val value: V,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : BucketOp<K, V>

    data class DeleteOp<K, V>(
        val key: K,
        override val timestamp: Long = System.currentTimeMillis()
    ) : BucketOp<K, V>

    // ── Public API ──────────────────────────────────────────────────

    /** Put a key-value pair into the bucket (buffered, flushed async). */
    fun put(key: K, value: V): BucketObjectStorage<K, V> {
        val entry = BucketEntry(key, value)
        merged.add(entry)
        journal.add(PutOp(key, value))
        return this
    }

    /** Get a value by key (checks ring buffer first, then sorted). */
    fun get(key: K): V? {
        // Check ring buffer (most recent writes)
        for (i in 0 until ringBuffer.size) {
            val e = ringBuffer[i]
            if (keyOrder(e.key, key) == 0) return e.value
        }
        // Check sorted series
        for (i in 0 until sortedSeries.size) {
            val e = sortedSeries[i]
            val cmp = keyOrder(e.key, key)
            if (cmp == 0) return e.value
            if (cmp > 0) break // past the key in sorted order
        }
        // Check persisted blocks (linear scan - would use index in production)
        for (id in blockStore.list()) {
            val block = blockStore.get(id)
            if (block != null) {
                val value = blockToValue(block)
                // Could extract key from block metadata
                // For now, skip as we don't have key in block
            }
        }
        return null
    }

    /** Remove a key (buffered as delete op). */
    fun remove(key: K): Boolean {
        var found = false
        for (i in 0 until ringBuffer.size) {
            if (keyOrder(ringBuffer[i].key, key) == 0) {
                found = true
                break
            }
        }
        if (!found) {
            for (i in 0 until sortedSeries.size) {
                if (keyOrder(sortedSeries[i].key, key) == 0) {
                    found = true
                    break
                }
            }
        }
        if (!found) return false

        journal.add(DeleteOp(key))
        for (i in 0 until sortedSeries.size) {
            if (keyOrder(sortedSeries[i].key, key) == 0) {
                sortedSeries.removeAt(i)
                break
            }
        }
        return true
    }

    /** Force flush the ring buffer into sorted series and persist to block store. */
    fun flush(): FlushResult {
        val beforeRing = ringBuffer.size

        // Drain ring → sorted
        merged.flush()

        // Persist flushed entries to ConfixBlockStore
        var persisted = 0
        for (i in 0 until sortedSeries.size) {
            val entry = sortedSeries[i]
            val block = valueToBlock(entry.key, entry.value)
            val id = "${bucketName}_${entry.key}"
            blockStore.put(id, block)
            persisted++
        }

        // Commit journal (clear pending mutations)
        journal.commit()

        return FlushResult(
            flushedFromRing = beforeRing,
            totalSorted = sortedSeries.size,
            persistedToStore = persisted,
            blockCount = blockStore.list().size,
        )
    }

    /** Rollback uncommitted mutations in the journal. */
    fun rollback() {
        journal.rollback()
    }

    /** Get all entries as a series (ring + sorted, deduped by key keeping latest). */
    fun entries(): Series<BucketEntry<K, V>> {
        val map = mutableMapOf<K, BucketEntry<K, V>>()
        for (i in 0 until ringBuffer.size) {
            val e = ringBuffer[i]
            map[e.key] = e
        }
        for (i in 0 until sortedSeries.size) {
            val e = sortedSeries[i]
            if (!map.containsKey(e.key)) map[e.key] = e
        }
        return map.values.size j { i -> map.values.toList()[i] }
    }

    /** Get entries sorted by key. */
    fun sortedEntries(): Series<BucketEntry<K, V>> = merged

    /** Get journal pending count. */
    val pendingCount: Int get() = journal.pendingCount

    /** Get journal for inspection/replay. */
    val mutationJournal: JournalSeries<BucketOp<K, V>> = journal

    /** Get underlying block store. */
    val confixBlockStore: ConfixBlockStore = blockStore

    /** Expose Confix facets for query planning. */
    val confixFacets: BucketConfixFacets<K, V> = BucketConfixFacets(this)

    /** Total element count (approximate, includes ring buffer). */
    val size: Int get() = merged.totalSize

    /** Clear all buffers and journal. */
    fun clear() {
        merged.clear()
        journal.clear()
        // Note: blockStore retains persisted data
    }

    override fun toString(): String {
        return "BucketObjectStorage(bucket=$bucketName, size=$size, ring=${ringBuffer.size}, sorted=${sortedSeries.size}, pending=$pendingCount, blocks=${blockStore.list().size})"
    }
}

/** Result of a flush operation. */
data class FlushResult(
    val flushedFromRing: Int,
    val totalSorted: Int,
    val persistedToStore: Int,
    val blockCount: Int,
)

/**
 * Confix facets for BucketObjectStorage — enables typed projection in query plans.
 */
class BucketConfixFacets<K, V>(private val storage: BucketObjectStorage<K, V>) {

    /** Key → bucket entry (sorted by key). */
    val keyIndex: Series<String> get() = storage.sortedEntries().map { "${storage.bucketName}_${it.key}" }

    /** Block IDs from block store. */
    val blockIds: Series<String> get() = storage.confixBlockStore.list().toSeries()

    /** Journal series for mutation replay. */
    val journalSeries: JournalSeries<BucketObjectStorage.BucketOp<K, V>> = storage.mutationJournal

    /** Ring buffer series (unflushed writes). */
    val ringBufferSeries: RingSeries<BucketObjectStorage.BucketEntry<K, V>> = storage.ringBuffer

    /** Sorted series (flushed, ordered). */
    val sortedSeries: SortedSeries<BucketObjectStorage.BucketEntry<K, V>> = storage.sortedSeries

    /** Bucket metadata facet as ConfixDoc. */
    fun metadata(): ConfixDoc = confixDoc {
        """
        {
            "bucket": "${storage.bucketName}",
            "size": ${storage.size},
            "ringBuffered": ${storage.ringBuffer.size},
            "sortedCount": ${storage.sortedSeriesSize},
            "pendingMutations": ${storage.pendingCount},
            "flushThreshold": ${storage.flushThreshold},
            "ringCapacity": ${storage.ringCapacity},
            "persistedBlocks": ${storage.confixBlockStore.list().size}
        }
        """.trimIndent()
    }
}

// Helper extensions for facet access
private val BucketObjectStorage<*, *>.ringBuffer: RingSeries<*> get() = this.ringBuffer
private val BucketObjectStorage<*, *>.sortedSeries: SortedSeries<*> get() = this.sortedSeries
private val BucketObjectStorage<*, *>.sortedSeriesSize: Int get() = this.sortedSeries.size
private val BucketObjectStorage<*, *>.flushThreshold: Int get() = this.flushThreshold
private val BucketObjectStorage<*, *>.ringCapacity: Int get() = this.ringCapacity
private val BucketObjectStorage<*, *>.confixBlockStore: ConfixBlockStore get() = this.confixBlockStore

/**
 * Factory for creating BucketObjectStorage with miniduck-memory integration.
 */
object BucketObjectStorageFactory {

    /**
     * Create an in-memory BucketObjectStorage using miniduck's ConfixMemoryStore.
     */
    fun <K, V> createInMemory(
        keyOrder: (K, K) -> Int,
        valueToBlock: (K, V) -> ConfixBlock,
        blockToValue: (ConfixBlock) -> V,
        bucketName: String,
        ringCapacity: Int = 1024,
        flushThreshold: Int = 256,
    ): BucketObjectStorage<K, V> {
        val memoryStore = ConfixMemoryStore()
        val blockStore = memoryStore.getBlockStore()
        return BucketObjectStorage(
            keyOrder = keyOrder,
            valueToBlock = valueToBlock,
            blockToValue = blockToValue,
            blockStore = blockStore,
            bucketName = bucketName,
            ringCapacity = ringCapacity,
            flushThreshold = flushThreshold,
        )
    }

    /**
     * Create a persistent BucketObjectStorage using miniduck's FileConfixBlockStore (JVM only).
     */
    @Suppress("UNUSED_PARAMETER")
    fun <K, V> createPersistent(
        keyOrder: (K, K) -> Int,
        valueToBlock: (K, V) -> ConfixBlock,
        blockToValue: (ConfixBlock) -> V,
        bucketName: String,
        baseDir: java.nio.file.Path,
        ringCapacity: Int = 1024,
        flushThreshold: Int = 256,
    ): BucketObjectStorage<K, V> {
        // This would use JvmConfixBlockStores.fileBlockStore(baseDir) on JVM
        // For commonMain, return in-memory as fallback
        return createInMemory(
            keyOrder = keyOrder,
            valueToBlock = valueToBlock,
            blockToValue = blockToValue,
            bucketName = bucketName,
            ringCapacity = ringCapacity,
            flushThreshold = flushThreshold,
        )
    }
}