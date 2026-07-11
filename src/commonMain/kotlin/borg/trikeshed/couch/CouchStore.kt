package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.mutable.mutableSeriesOf
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.isam.meta.IOMemento
import kotlinx.serialization.*
import kotlinx.serialization.json.Json

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

/**
 * CouchStore — minimal K-V document store with:
 * - put/get/delete by docId
 * - query(ConfixCursor) — Cursor algebra over documents
 * - subscribe to mutations via MutableSeries observer
 * - flush/drain for durability hooks
 */
class CouchStore(
    private val persistence: CouchPersistence? = null
) {
    
    // DocId -> Document (using MutableSeries as index)
    private val docs = mutableSeriesOf<Document>()
    private val docIndex = mutableMapOf<String, Int>() // docId -> index in docs
    
    // Mutation event stream for subscribers
    private val mutations = mutableSeriesOf<MutationEvent>()
    
    sealed interface MutationEvent {
        data class Inserted(val doc: Document) : MutationEvent
        data class Updated(val doc: Document) : MutationEvent
        data class Deleted(val docId: String) : MutationEvent
    }
    
    /**
     * Put a document — insert or replace.
     */
    fun put(document: Document): Boolean {
        val existingIndex = docIndex[document.id]
        if (existingIndex != null) {
            docs.set(existingIndex, document)
            mutations.append(MutationEvent.Updated(document))
            persistence?.persist(document)
            return false // updated
        } else {
            docs.append(document)
            docIndex[document.id] = docs.a - 1
            mutations.append(MutationEvent.Inserted(document))
            persistence?.persist(document)
            return true // inserted
        }
    }
    
    /**
     * Get a document by id.
     */
    fun get(docId: String): Document? {
        val idx = docIndex[docId] ?: return null
        return docs[idx]
    }
    
    /**
     * Delete a document by id.
     */
    fun delete(docId: String): Boolean {
        val idx = docIndex.remove(docId) ?: return false
        docs.removeAt(idx)
        // Rebuild index (simplest correct approach for in-memory)
        docIndex.clear()
        for (i in 0 until docs.a) {
            docIndex[docs[i].id] = i
        }
        mutations.append(MutationEvent.Deleted(docId))
        persistence?.delete(docId)
        return true
    }
    
    /**
     * Check if document exists.
     */
    fun contains(docId: String): Boolean = docId in docIndex
    
    /**
     * Total document count.
     */
    val size: Int get() = docs.a
    
    /**
     * All document ids.
     */
    fun ids(): Join<Int, (Int) -> String> = docs.α { it.id }
    
    /**
     * Query — projects all documents to a Cursor (rows = docs, cols = _id + fields).
     */
    fun query(): QueryResult {
        val cursor = buildCursorFromDocs(docs)
        return QueryResult(cursor, docs.a.toLong())
    }

    /** Query by field value equality. */
    fun query(fieldName: String, value: Any): QueryResult {
        val matched = ArrayList<Document>()
        for (i in 0 until docs.a) {
            val doc = docs[i]
            if (doc.fields.any { it.name == fieldName && it.value == value }) matched.add(doc)
        }
        val series: Series<Document> = matched.size j { matched[it] }
        return QueryResult(buildCursorFromSeries(series), matched.size.toLong())
    }

    private fun buildCursorFromDocs(documents: MutableSeries<Document>): Cursor =
        buildCursorFromSeries(documents.a j { documents[it] })

    private fun buildCursorFromSeries(documents: Series<Document>): Cursor {
        if (documents.size == 0) return 0 j { error("empty cursor") }
        return documents.size j { rowIdx -> documentToRowVec(documents[rowIdx]) }
    }

    private fun documentToRowVec(doc: Document): RowVec {
        val n = 1 + doc.fields.size
        val keys = Array(n) { i -> if (i == 0) "_id" else doc.fields[i - 1].name }
        val cells = Array<Any?>(n) { i -> if (i == 0) doc.id else doc.fields[i - 1].value }
        return borg.trikeshed.cursor.cellsToRowVec(
            n j { cells[it] },
            n j { keys[it] },
        )
    }
    /**
     * Subscribe to mutation events.
     * Returns cancel function to unsubscribe.
     */
    fun subscribeMutations(observer: (MutationEvent) -> Unit): () -> Unit {
        return mutations.subscribe { twin: Twin<Series<MutationEvent>> ->
            // Twin<Series<MutationEvent>> is Join<Series<MutationEvent>, Series<MutationEvent>>
            // So twin.a is Series<MutationEvent> and twin.b is Series<MutationEvent>
            // Get the last element from the series
            val series: Series<MutationEvent> = twin.a
            if (series.size > 0) {
                observer(series[series.size - 1])
            }
        }
    }

    /** Subscribe to mutation events as Twin<Series<MutationEvent>> (for MutableSeries DSL). */
    fun subscribeMutationsSeries(observer: (Twin<Series<MutationEvent>>) -> Unit): () -> Unit {
        return mutations.subscribe(observer)
    }

    /**
     * Flush pending mutations to persistence.
     * Idempotent — safe to call repeatedly.
     */
    fun flush(): Unit {
        persistence?.flush()
    }
    
    /**
     * Drain — stop accepting new mutations, process remaining.
     * For in-memory store, this is a no-op; persistence implementations may override.
     */
    fun drain(): Unit {
        persistence?.drain()
    }
    
    /**
     * Close the store — drain and release resources.
     */
    fun close(): Unit {
        drain()
        persistence?.close()
    }
    
    /**
     * Get all documents as a list.
     */
    fun all(): List<Document> = docs.sequence().toList()
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
    fun inMemory(): CouchStore = CouchStore(NoOpPersistence)

    fun withPersistence(persistence: CouchPersistence): CouchStore = CouchStore(persistence)

    fun withJsonFile(path: String, files: borg.trikeshed.userspace.nio.file.spi.FileOperations): CouchStore =
        CouchStore(JsonFilePersistence(path, files))
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
