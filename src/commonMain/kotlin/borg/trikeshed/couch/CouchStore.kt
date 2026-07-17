package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.mutable.mutableSeriesOf
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.isam.meta.IOMemento
import kotlinx.serialization.*

/**
 * Couch K-V Document Store — minimal in-memory implementation backed by MutableSeries.
 * 
 * Document = Join<DocId, Series<Field>>
 * Field = Join<FieldName, FieldValue>
 * 
 * Uses COWArrayBackend for O(1) reads, copy-on-write mutations.
 * Pluggable persistence interface for future durability.
 */
@Serializable
data class Field(
    val name: String,
    @Contextual
    val value: Any // Serialized as JSON string for generality
)

@Serializable
data class Document(
    val id: String,
    val fields: List<Field>
)

/**
 * Query result — wraps a Cursor for composability with CursorOps.
 */
data class QueryResult(
    val cursor: Cursor,
    val totalCount: Long
)

interface CouchIngress {
    fun putIntent(doc: Document, expectedRev: String?): Boolean
    fun deleteIntent(docId: String, expectedRev: String?): Boolean
}

/**
 * CouchStore — minimal K-V document store with:
 * - put/get/delete by docId
 * - query(ConfixCursor) — Cursor algebra over documents
 * - subscribe to mutations via MutableSeries observer
 * - flush/drain for durability hooks
 */
class CouchStore(
    private val ingress: CouchIngress,
    val head: CouchHeadProjection,
    val changes: CouchChangesProjection
) {

    sealed interface MutationEvent {
        data class Inserted(val doc: Document) : MutationEvent
        data class Updated(val doc: Document) : MutationEvent
        data class Deleted(val docId: String) : MutationEvent
    }
    
    /**
     * Put a document — insert or replace.
     */
    fun put(document: Document): Boolean {
        return ingress.putIntent(document, null)
    }
    
    /**
     * Get a document by id.
     */
    fun get(docId: String): Document? {
        return head.get(docId)
    }
    
    /**
     * Delete a document by id.
     */
    fun delete(docId: String): Boolean {
        return ingress.deleteIntent(docId, null)
    }

    /**
     * Check if document exists.
     */
    fun contains(docId: String): Boolean = head.contains(docId)
    
    /**
     * Total document count.
     */
    val size: Int get() = head.size
    
    /**
     * All document ids.
     */
    fun ids(): Join<Int, (Int) -> String> = head.ids()

    /**
     * Get all documents as a list.
     */
    fun all(): List<Document> = head.all()
    
    /**
     * Query — projects all documents to a Cursor (rows = docs, cols = _id + fields).
     */
    fun query(): QueryResult {
        return head.query()
    }

    /** Query by field value equality. */
    fun query(fieldName: String, value: Any): QueryResult {
        return head.query(fieldName, value)
    }

    /**
     * Subscribe to mutation events.
     * Returns cancel function to unsubscribe.
     */
    fun subscribeMutations(observer: (MutationEvent) -> Unit): () -> Unit {
        var lastSeen = 0
        return changes.subscribe { twin: Twin<Series<CouchCommittedFrame>> ->
            val series: Series<CouchCommittedFrame> = twin.a
            while (lastSeen < series.size) {
                val frame = series[lastSeen]
                lastSeen++
                if (frame.deleted) {
                    observer(MutationEvent.Deleted(frame.docId))
                } else if (frame.doc != null) {
                    if (frame.rev.startsWith("uuid-0-")) {
                         observer(MutationEvent.Inserted(frame.doc))
                    } else {
                         observer(MutationEvent.Updated(frame.doc))
                    }
                }
            }
        }
    }

    /** Subscribe to mutation events as Twin<Series<MutationEvent>> (for MutableSeries DSL). */
    fun subscribeMutationsSeries(observer: (Twin<Series<MutationEvent>>) -> Unit): () -> Unit {
        return changes.subscribe { twin: Twin<Series<CouchCommittedFrame>> ->
            val src: Series<CouchCommittedFrame> = twin.a
            // create an ephemeral projection to matching types
            val mapped: Series<MutationEvent> = src.size j { i: Int ->
                val f = src[i]
                if (f.deleted) MutationEvent.Deleted(f.docId)
                else if (f.rev.startsWith("uuid-0-")) MutationEvent.Inserted(f.doc!!)
                else MutationEvent.Updated(f.doc!!)
            }
            // For now, this is a leaky abstraction that wraps Series inside a Twin dummy view.
            observer(mapped j mapped)
        }
    }

    /**
     * Flush pending mutations to persistence.
     * Idempotent — safe to call repeatedly.
     */
    fun flush(): Unit {
        // persistence is now handled by the ingress / pipeline
    }
    
    /**
     * Drain — stop accepting new mutations, process remaining.
     * For in-memory store, this is a no-op; persistence implementations may override.
     */
    fun drain(): Unit {
    }
    
    /**
     * Close the store — drain and release resources.
     */
    fun close(): Unit {
        drain()
    }

    companion object {
        fun create(parentScope: kotlinx.coroutines.CoroutineScope, capacity: Int = 64): CouchStore {
            return CouchStoreFactory.inMemory()
        }
    }
}

/**
 * Persistence interface — pluggable backend for durability.
 */
interface CouchPersistence {
    fun persist(document: Document)
    fun delete(docId: String)
    fun flush(): Unit
    fun drain(): Unit
    fun close(): Unit
}

/**
 * No-op persistence for pure in-memory operation.
 */
object NoOpPersistence : CouchPersistence {
    override fun persist(document: Document) {}
    override fun delete(docId: String) {}
    override fun flush() {}
    override fun drain() {}
    override fun close() {}
}

/**
 * Factory functions for CouchStore creation.
 * File-backed store uses [FileOperations] (userspace.nio) — JVM is only the nio adapter.
 */
object CouchStoreFactory {
    class SyncTestIngress(val head: CouchHeadProjection, val changes: CouchChangesProjection, val persistence: CouchPersistence) : CouchIngress {
        var seq: Long = 0
        override fun putIntent(doc: Document, expectedRev: String?): Boolean {
            val existingRev = head.getRev(doc.id)
            val isDeleted = head.isDeleted(doc.id)
            if (expectedRev != null && existingRev != expectedRev) {
                return false // reject stale
            }

            val newRev = if (existingRev == null || isDeleted) "uuid-0-${doc.id.hashCode()}" else "uuid-${seq}-${doc.id.hashCode()}" // simple rev generator
            val frame = CouchCommittedFrame(seq, doc.id, newRev, false, doc)
            seq++

            head.applyCommit(frame)
            changes.applyCommit(frame)
            persistence.persist(doc)
            return existingRev == null || isDeleted
        }

        override fun deleteIntent(docId: String, expectedRev: String?): Boolean {
            val existingRev = head.getRev(docId) ?: return false
            val isDeleted = head.isDeleted(docId)
            if (isDeleted) return false // already deleted

            if (expectedRev != null && existingRev != expectedRev) {
                return false // reject stale
            }

            val newRev = "uuid-${seq}-deleted"
            val frame = CouchCommittedFrame(seq, docId, newRev, true, null)
            seq++

            head.applyCommit(frame)
            changes.applyCommit(frame)
            persistence.delete(docId)
            return true
        }
    }

    fun inMemory(): CouchStore {
        val head = CouchHeadProjection()
        val changes = CouchChangesProjection()
        val ingress = ProductionCouchIngress(head, { frame -> head.applyCommit(frame); changes.applyCommit(frame); }, { doc -> borg.trikeshed.job.ContentId.of(doc.fields.joinToString { it.value.toString() }.encodeToByteArray()) })
        return CouchStore(ingress, head, changes)
    }

    fun withPersistence(persistence: CouchPersistence): CouchStore {
        val head = CouchHeadProjection()
        val changes = CouchChangesProjection()
        val ingress = ProductionCouchIngress(head, { frame -> head.applyCommit(frame); changes.applyCommit(frame); persistence.persist(frame.doc ?: Document(frame.docId, emptyList())) }, { doc -> borg.trikeshed.job.ContentId.of(doc.fields.joinToString { it.value.toString() }.encodeToByteArray()) })
        return CouchStore(ingress, head, changes)
    }
}

/**
 * Extension for Cursor → Document reconstruction.
 * Known row size — Field list freeze per row (Document takes List).
 */
fun Cursor.toDocuments(): MutableSeries<Document> {
    val result = mutableSeriesOf<Document>()
    for (i in 0 until size) {
        val row = this[i]
        val fields = ArrayList<Field>(row.size)
        for (colIdx in 0 until row.size) {
            val cell = row.b(colIdx)
            fields.add(Field(cell.b().name.toString(), cell.a ?: ""))
        }
        result.append(Document("reconstructed-$i", fields))
    }
    return result
}
