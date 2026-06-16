package borg.trikeshed.couch.bucket

import borg.trikeshed.couch.ConfixDocStore
import borg.trikeshed.couch.ConfixDocStoreEntry
import borg.trikeshed.couch.ipfs.IpfsMeshStore
import borg.trikeshed.mutable.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.lib.*

/**
 * BucketObjectStorage — compositional key-value store built on the MutableSeries DSL.
 *
 * Architecture (drain chain):
 *   RingSeries (buffer) → MergedSeries → SortedSeries (sorted flush)
 *         │                   │               │
 *         ▼                   ▼               ▼
 *    JournalSeries        flush()          ConfixDocStore
 *    (mutations)                               │
 *         └───────────────────────────────────►│
 *                          IpfsMeshStore (CID)┘
 *
 * Facets exposed via ConfixIndexK:
 *   - CID index (content-addressed lookup)
 *   - Key index (user key → CID)
 *   - Revision timeline (JournalSeries deltas)
 *   - Sorted flush checkpoints
 */
class BucketObjectStorage<K, V>(
    private val keyOrder: (K, K) -> Int,
    private val valueCodec: (V) -> ConfixDoc,
    private val valueDecoder: (ConfixDoc) -> V,
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

    /** Content-addressed IPFS mesh store */
    private val ipfsStore = IpfsMeshStore()

    /** Confix-backed document store (revisioned, queryable) */
    private val confixStore = ConfixDocStore()

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
        return null
    }

    /** Remove a key (buffered as delete op). */
    fun remove(key: K): Boolean {
        // Check if exists in either buffer
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
        // For sorted series, we need to find and remove
        for (i in 0 until sortedSeries.size) {
            if (keyOrder(sortedSeries[i].key, key) == 0) {
                sortedSeries.removeAt(i)
                break
            }
        }
        return true
    }

    /** Force flush the ring buffer into sorted series and persist to stores. */
    fun flush(): FlushResult {
        val beforeRing = ringBuffer.size
        val beforeSorted = sortedSeries.size

        // Drain ring → sorted
        merged.flush()

        // Persist flushed entries to ConfixDocStore and IPFS
        var persisted = 0
        for (i in 0 until sortedSeries.size) {
            val entry = sortedSeries[i]
            val doc = valueCodec(entry.value)
            val id = "${bucketName}_${entry.key}"
            
            confixStore.put(id, doc)
            ipfsStore.putContent(doc)
            persisted++
        }

        // Commit journal (clear pending mutations)
        journal.commit()

        return FlushResult(
            flushedFromRing = beforeRing,
            totalSorted = sortedSeries.size,
            persistedToStore = persisted,
            cidCount = ipfsStore.backingStore.size,
        )
    }

    /** Rollback uncommitted mutations in the journal. */
    fun rollback() {
        journal.rollback()
    }

    /** Get all entries as a series (ring + sorted, deduped by key keeping latest). */
    fun entries(): Series<BucketEntry<K, V>> {
        val map = mutableMapOf<K, BucketEntry<K, V>>()
        
        // Ring buffer entries win (newer)
        for (i in 0 until ringBuffer.size) {
            val e = ringBuffer[i]
            map[e.key] = e
        }
        // Sorted entries fill gaps
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

    /** Get ConfixDocStore for view queries. */
    val confixDocStore: ConfixDocStore = confixStore

    /** Get IPFS mesh store for CID lookups. */
    val ipfsMeshStore: IpfsMeshStore = ipfsStore

    /** Expose ConfixIndex facets for query planning. */
    val confixFacets: BucketConfixFacets<K, V> = BucketConfixFacets(this)

    /** Total element count (approximate, includes ring buffer). */
    val size: Int get() = merged.totalSize

    fun clear() {
        merged.clear()
        journal.clear()
        confixStore = ConfixDocStore() // Note: would need to be var for this to work
    }

    override fun toString(): String {
        return "BucketObjectStorage(bucket=$bucketName, size=$size, ring=${ringBuffer.size}, sorted=${sortedSeries.size}, pending=$pendingCount)"
    }
}

/** Result of a flush operation. */
data class FlushResult(
    val flushedFromRing: Int,
    val totalSorted: Int,
    val persistedToStore: Int,
    val cidCount: Int,
)

/**
 * Confix facets for BucketObjectStorage — enables typed projection in query plans.
 */
class BucketConfixFacets<K, V>(private val storage: BucketObjectStorage<K, V>) {

    /** Key → bucket entry (sorted by key). */
    val keyIndex: Series<String> get() = storage.sortedEntries().map { "${storage.bucketName}_${it.key}" }

    /** CID series from IPFS store. */
    val cidSeries: Series<String> get() = storage.ipfsMeshStore.backingStore.entries.map { it.id }

    /** Journal series for mutation replay. */
    val journalSeries: JournalSeries<BucketObjectStorage.BucketOp<K, V>> = storage.mutationJournal

    /** Ring buffer series (unflushed writes). */
    val ringBufferSeries: RingSeries<BucketObjectStorage.BucketEntry<K, V>> = storage.ringBuffer

    /** Sorted series (flushed, ordered). */
    val sortedSeries: SortedSeries<BucketObjectStorage.BucketEntry<K, V>> = storage.sortedSeries

    /** Bucket metadata facet. */
    fun metadata(): ConfixDoc = confixDoc {
        """
        {
            "bucket": "${storage.bucketName}",
            "size": ${storage.size},
            "ringBuffered": ${storage.ringBuffer.size},
            "sortedCount": ${storage.sortedSeriesSize},
            "pendingMutations": ${storage.pendingCount},
            "flushThreshold": ${storage.flushThreshold},
            "ringCapacity": ${storage.ringCapacity}
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