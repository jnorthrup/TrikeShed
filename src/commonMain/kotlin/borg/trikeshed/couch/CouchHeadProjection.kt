package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.lib.`▶`
import borg.trikeshed.collections.MutableSeries
import borg.trikeshed.collections.mutableSeriesOf
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.ColumnMeta

/**
 * A committed frame representing a document mutation.
 */
data class CouchCommittedFrame(
    val sequence: Long,
    val docId: String,
    val rev: String,
    val deleted: Boolean,
    val doc: Document?
)

/**
 * CouchHeadProjection - Replayable projection of the current document state.
 */
class CouchHeadProjection {
    private val docs = mutableSeriesOf<Document>()
    private val docIndex = mutableMapOf<String, Int>() // docId -> index in docs
    private val fieldIndex = mutableMapOf<String, MutableMap<Any, MutableSet<String>>>()
    private val indexQueryCache = mutableMapOf<Pair<String, Any>, IntArray>()

    // docId -> current committed frame (including tombstones)
    private val frames = mutableMapOf<String, CouchCommittedFrame>()
    private var lastSequence: Long = -1L

    fun applyCommit(frame: CouchCommittedFrame) {
        val existingFrame = frames[frame.docId]
        if (existingFrame != null) {
            require(frame.sequence > existingFrame.sequence) { "stale revision rejected" }
            require(frame.rev != existingFrame.rev) { "duplicate rev rejected" }
        }

        require(lastSequence == -1L || frame.sequence == lastSequence + 1 || frame.sequence == 0L) {
            "_changes resumes after the sequence without gaps"
        }
        lastSequence = frame.sequence

        require(frame.deleted || frame.doc?.id == frame.docId) {
            "Insert/Update frame docId must match document id"
        }

        frames[frame.docId] = frame
        indexQueryCache.clear()

        if (frame.deleted) {
            val tombstone = Document(frame.docId, listOf(Field("_deleted", true)))
            val existingIndex = docIndex[frame.docId]
            if (existingIndex != null) {
                removeFromFieldIndex(docs[existingIndex])
                docs.set(existingIndex, tombstone)
            } else {
                docs.append(tombstone)
                docIndex[frame.docId] = docs.a - 1
            }
        } else {
            val doc = frame.doc ?: error("Insert/Update frame must contain a document")
            val existingIndex = docIndex[doc.id]
            if (existingIndex != null) {
                removeFromFieldIndex(docs[existingIndex])
                docs.set(existingIndex, doc)
                addToFieldIndex(doc)
            } else {
                docs.append(doc)
                docIndex[doc.id] = docs.a - 1
                addToFieldIndex(doc)
            }
        }
    }

    private fun rebuildDocIndex() {
        docIndex.clear()
        for ((i, doc) in docs.`▶`.withIndex()) {
            docIndex[doc.id] = i
        }
    }

    private fun addToFieldIndex(doc: Document) {
        for (field in doc.fields) {
            fieldIndex.getOrPut(field.name) { mutableMapOf() }
                      .getOrPut(field.value) { mutableSetOf() }
                      .add(doc.id)
        }
    }

    private fun removeFromFieldIndex(doc: Document) {
        for (field in doc.fields) {
            val innerMap = fieldIndex[field.name]
            if (innerMap != null) {
                innerMap[field.value]?.remove(doc.id)
            }
        }
    }

    fun get(docId: String): Document? {
        val idx = docIndex[docId] ?: return null
        return docs[idx]
    }

    fun getRev(docId: String): String? {
        return frames[docId]?.rev
    }

    fun isDeleted(docId: String): Boolean {
        return frames[docId]?.deleted ?: false
    }

    fun contains(docId: String): Boolean = docId in docIndex

    val size: Int get() = docs.a

    fun all(): List<Document> = docs.sequence().toList()

    fun ids(): Join<Int, (Int) -> String> = docs.α { it.id }

    fun query(): QueryResult {
        val cursor = buildCursorFromDocs(docs)
        return QueryResult(cursor, docs.a.toLong())
    }

    fun query(fieldName: String, value: Any): QueryResult {
        val cacheKey = Pair(fieldName, value)
        var matchedIndices = indexQueryCache[cacheKey]
        var count = matchedIndices?.size ?: 0

        if (matchedIndices == null) {
            val matchedIds = fieldIndex[fieldName]?.get(value) ?: emptySet()
            matchedIndices = IntArray(matchedIds.size)
            var i = 0
            for (id in matchedIds) { docIndex[id]?.let { idx -> matchedIndices[i++] = idx } }
            count = i
            if (count < matchedIndices.size) {
                matchedIndices = matchedIndices.copyOf(count)
            }
            indexQueryCache[cacheKey] = matchedIndices
        }

        // ⚡ Bolt: Return a Series utilizing buildSeries via buildCursorFromSeries mapping to original docs to prevent ArrayList overhead
        return QueryResult(buildCursorFromSeries(count j { docs[matchedIndices!![it]] }), count.toLong())
    }

    private fun buildCursorFromDocs(documents: MutableSeries<Document>): Cursor =
        buildCursorFromSeries(documents.a j { documents[it] })

    private fun buildCursorFromSeries(documents: Series<Document>): Cursor {
        if (documents.size == 0) return 0 j { error("empty cursor") }
        return documents.size j { rowIdx -> documentToRowVec(documents[rowIdx]) }
    }

    private fun documentToRowVec(doc: Document): RowVec {
        val rev = frames[doc.id]?.rev ?: ""
        val n = 2 + doc.fields.size
        val keys = Array(n) { i ->
            when (i) {
                0 -> "_id"
                1 -> "_rev"
                else -> doc.fields[i - 2].name
            }
        }
        val cells = Array<Any?>(n) { i ->
            when (i) {
                0 -> doc.id
                1 -> rev
                else -> doc.fields[i - 2].value
            }
        }
        return borg.trikeshed.cursor.cellsToRowVec(
            n j { cells[it] },
            n j { keys[it] },
        )
    }
}
