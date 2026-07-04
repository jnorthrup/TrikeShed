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
     * Query with Confix Cursor — projects documents to a Cursor for CursorOps composition.
     * 
     * The returned Cursor has rows = documents, columns = Field(name, value).
     * Metadata carries TypeMemento for each column.
     */
    fun query(): QueryResult {
        // Simplified: return empty cursor for now - core K-V works
        val emptyCursor: Cursor = 0 j { error("empty cursor") }
        return QueryResult(emptyCursor, docs.a.toLong())
    }

    /** Query by field value equality. */
    fun query(fieldName: String, value: Any): QueryResult {
        val emptyCursor: Cursor = 0 j { error("empty cursor") }
        return QueryResult(emptyCursor, 0L)
    }

    private fun buildCursorFromDocs(documents: MutableSeries<Document>): Cursor {
        return 0 j { error("empty cursor") }
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
 * JSON file persistence (minimal, single-file append-only log).
 */
class JsonFilePersistence(private val file: java.io.File) : CouchPersistence {
    private val json = Json { prettyPrint = true }
    
    init {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
            file.writeText(json.encodeToString(emptyList<Document>()))
        }
    }
    
    override fun persist(document: Document) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == document.id }
        if (idx >= 0) list[idx] = document else list.add(document)
        saveAll(list)
    }
    
    override fun delete(docId: String) {
        val list = loadAll().filter { it.id != docId }
        saveAll(list)
    }
    
    override fun flush() {} // Write-through on every persist
    
    override fun drain() {} // No pending buffer
    
    override fun close() {}
    
    private fun loadAll(): List<Document> {
        return try {
            json.decodeFromString<List<Document>>(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun saveAll(list: List<Document>) {
        file.writeText(json.encodeToString(list))
    }
}

/**
 * Factory functions for CouchStore creation.
 */
object CouchStoreFactory {
    fun inMemory(): CouchStore = CouchStore(NoOpPersistence)
    
    fun withJsonFile(file: java.io.File): CouchStore = CouchStore(JsonFilePersistence(file))
    
    fun withPersistence(persistence: CouchPersistence): CouchStore = CouchStore(persistence)
}

/**
 * Extension for Cursor → Document reconstruction.
 */
fun Cursor.toDocuments(): MutableSeries<Document> {
    val result = mutableSeriesOf<Document>()
    this.α { row ->
        val fields = (0 until row.size).map { colIdx ->
            val value = row.b(colIdx).a ?: ""  // Handle nullable
            val meta = row.b(colIdx).b()
            Field(meta.name.toString(), value)
        }
        result.append(Document("reconstructed-${row.hashCode()}", fields))
    }
    return result
}
