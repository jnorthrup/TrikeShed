@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.couch.bucket.ipfs

import borg.trikeshed.couch.bucket.BucketObjectStorage
import borg.trikeshed.couch.ipfs.IpfsMeshStore
import borg.trikeshed.memory.ConfixBlock
import borg.trikeshed.memory.ConfixBlockStore
import borg.trikeshed.mutable.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.lib.*

/**
 * IPFS-backed BucketObjectStorage — content-addressed key-value store.
 *
 * Architecture:
 *   RingSeries → MergedSeries → SortedSeries
 *                     │
 *                     ▼
 *              IPFS Mesh Store (CID-addressed)
 *              ConfixBlockStore (metadata index)
 *              JournalSeries (mutation log)
 *
 * Facets:
 *   - cidIndex: CID → block (content-addressed lookup)
 *   - keyIndex: user key → CID (mapping)
 *   - journal: mutation deltas for replay
 *   - sorted: flushed entries in key order
 *   - ring: unflushed buffer
 */
class IpfsBucketObjectStorage<K, V>(
    private val keyOrder: (K, K) -> Int,
    private val valueCodec: (V) -> ConfixDoc,
    private val valueDecoder: (ConfixDoc) -> V,
    private val bucketName: String,
    private val ipfsStore: IpfsMeshStore = IpfsMeshStore(),
    private val ringCapacity: Int = 1024,
    private val flushThreshold: Int = 256,
) {

    // ── MutableSeries Drain Chain ──────────────────────────────────

    private val ringBuffer = RingSeries<BucketEntry<K, V>>(ringCapacity)
    private val sortedSeries = SortedSeries<BucketEntry<K, V>> { a, b -> keyOrder(a.key, b.key) }
    private val merged = MergedSeries(ringBuffer, sortedSeries, flushThreshold)
    private val journal = JournalSeries<BucketOp<K, V>>()

    // ── Local ConfixDocStore for metadata/index ───────────────────
    private val metaStore = borg.trikeshed.couch.ConfixDocStore()

    // ── Entry model ────────────────────────────────────────────────

    data class BucketEntry<K, V>(
        val key: K,
        val value: V,
        val cid: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    sealed interface BucketOp<out K, out V> {
        val timestamp: Long
        val cid: String?
    }

    data class PutOp<K, V>(
        val key: K,
        val value: V,
        val cid: String?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : BucketOp<K, V>

    data class DeleteOp<K, V>(
        val key: K,
        val cid: String?,
        override val timestamp: Long = System.currentTimeMillis()
    ) : BucketOp<K, V>

    // ── Public API ──────────────────────────────────────────────────

    fun put(key: K, value: V): IpfsBucketObjectStorage<K, V> {
        val doc = valueCodec(value)
        val cid = ipfsStore.computeCid(doc)
        val entry = BucketEntry(key, value, cid)
        merged.add(entry)
        journal.add(PutOp(key, value, cid))
        return this
    }

    fun get(key: K): V? {
        // Check ring buffer
        for (i in 0 until ringBuffer.size) {
            val e = ringBuffer[i]
            if (keyOrder(e.key, key) == 0) return e.value
        }
        // Check sorted
        for (i in 0 until sortedSeries.size) {
            val e = sortedSeries[i]
            val cmp = keyOrder(e.key, key)
            if (cmp == 0) return e.value
            if (cmp > 0) break
        }
        // Check meta store
        val id = "${bucketName}_$key"
        val meta = metaStore[id]
        if (meta != null) {
            val cid = meta.doc.value("cid") as? String
            cid?.let { ipfsStore.getByCid(it) }
                ?.let { valueDecoder(it.doc) }
                ?.also { return it }
        }
        return null
    }

    fun remove(key: K): Boolean {
        var found = false
        var cid: String? = null
        for (i in 0 until ringBuffer.size) {
            if (keyOrder(ringBuffer[i].key, key) == 0) {
                cid = ringBuffer[i].cid
                found = true
                break
            }
        }
        if (!found) {
            for (i in 0 until sortedSeries.size) {
                if (keyOrder(sortedSeries[i].key, key) == 0) {
                    cid = sortedSeries[i].cid
                    found = true
                    break
                }
            }
        }
        if (!found) {
            val id = "${bucketName}_$key"
            val meta = metaStore[id]
            cid = meta?.doc.value("cid") as? String
            found = meta != null
        }
        if (!found) return false

        journal.add(DeleteOp(key, cid))
        for (i in 0 until sortedSeries.size) {
            if (keyOrder(sortedSeries[i].key, key) == 0) {
                sortedSeries.removeAt(i)
                break
            }
        }
        return true
    }

    fun flush(): FlushResult {
        val beforeRing = ringBuffer.size
        merged.flush()

        var persisted = 0
        for (i in 0 until sortedSeries.size) {
            val entry = sortedSeries[i]
            val doc = valueCodec(entry.value)
            val cid = entry.cid ?: ipfsStore.computeCid(doc)
            
            // Store in IPFS mesh
            ipfsStore.putContent(doc)
            
            // Store metadata in ConfixDocStore
            val id = "${bucketName}_${entry.key}"
            val metaDoc = confixDoc {
                """
                {
                    "key": "${entry.key}",
                    "cid": "$cid",
                    "timestamp": ${entry.timestamp},
                    "bucket": "$bucketName"
                }
                """.trimIndent()
            }
            metaStore.put(id, metaDoc)
            persisted++
        }

        journal.commit()

        return FlushResult(
            flushedFromRing = beforeRing,
            totalSorted = sortedSeries.size,
            persistedToStore = persisted,
            cidCount = ipfsStore.backingStore.size,
        )
    }

    fun rollback() {
        journal.rollback()
    }

    fun entries(): Series<BucketEntry<K, V>> {
        val map = mutableMapOf<K, BucketEntry<K, V>>()
        for (i in 0 until ringBuffer.size) map[ringBuffer[i].key] = ringBuffer[i]
        for (i in 0 until sortedSeries.size) {
            val e = sortedSeries[i]
            if (!map.containsKey(e.key)) map[e.key] = e
        }
        return map.values.size j { i -> map.values.toList()[i] }
    }

    fun sortedEntries(): Series<BucketEntry<K, V>> = merged

    val pendingCount: Int get() = journal.pendingCount
    val mutationJournal: JournalSeries<BucketOp<K, V>> = journal
    val ipfsMeshStore: IpfsMeshStore = ipfsStore
    val confixDocStore: borg.trikeshed.couch.ConfixDocStore = metaStore
    val confixFacets: IpfsBucketConfixFacets<K, V> = IpfsBucketConfixFacets(this)
    val size: Int get() = merged.totalSize

    fun clear() {
        merged.clear()
        journal.clear()
    }

    override fun toString(): String {
        return "IpfsBucketObjectStorage(bucket=$bucketName, size=$size, ring=${ringBuffer.size}, sorted=${sortedSeries.size}, pending=$pendingCount, cids=${ipfsStore.backingStore.size})"
    }
}

data class FlushResult(
    val flushedFromRing: Int,
    val totalSorted: Int,
    val persistedToStore: Int,
    val cidCount: Int,
)

/**
 * Confix facets for IPFS BucketObjectStorage.
 */
class IpfsBucketConfixFacets<K, V>(private val storage: IpfsBucketObjectStorage<K, V>) {

    /** Key → CID mapping. */
    val keyToCid: Series<String> get() = storage.sortedEntries()
        .filter { it.cid != null }
        .map { "${it.key} -> ${it.cid}" }

    /** All CIDs in the bucket. */
    val cidSeries: Series<String> get() = storage.ipfsMeshStore.backingStore.entries.map { it.id }

    /** Journal for mutation replay. */
    val journalSeries: JournalSeries<IpfsBucketObjectStorage.BucketOp<K, V>> = storage.mutationJournal

    /** Ring buffer (unflushed). */
    val ringBufferSeries: RingSeries<IpfsBucketObjectStorage.BucketEntry<K, V>> = storage.ringBuffer

    /** Sorted series (flushed). */
    val sortedSeries: SortedSeries<IpfsBucketObjectStorage.BucketEntry<K, V>> = storage.sortedSeries

    /** Metadata as ConfixDoc. */
    fun metadata(): ConfixDoc = confixDoc {
        """
        {
            "bucket": "${storage.bucketName}",
            "size": ${storage.size},
            "ringBuffered": ${storage.ringBuffer.size},
            "sortedCount": ${storage.sortedEntries().size},
            "pendingMutations": ${storage.pendingCount},
            "flushThreshold": ${storage.flushThreshold},
            "ringCapacity": ${storage.ringCapacity},
            "ipfsObjects": ${storage.ipfsMeshStore.backingStore.size}
        }
        """.trimIndent()
    }

    /** Expose ConfixIndexK facets for query planning. */
    fun confixIndexFacets(): Series<ConfixIndexK<*>> = listOf(
        ConfixIndexK.Spans,
        ConfixIndexK.Tags,
        ConfixIndexK.Depths,
        ConfixIndexK.DirectChildren,
        ConfixIndexK.TreeCursor,
        ConfixIndexK.KeyToChild,
    ).toSeries()
}

private val IpfsBucketObjectStorage<*, *>.ringBuffer: RingSeries<*> get() = this.ringBuffer
private val IpfsBucketObjectStorage<*, *>.sortedSeries: SortedSeries<*> get() = this.sortedSeries
private val IpfsBucketObjectStorage<*, *>.flushThreshold: Int get() = this.flushThreshold
private val IpfsBucketObjectStorage<*, *>.ringCapacity: Int get() = this.ringCapacity