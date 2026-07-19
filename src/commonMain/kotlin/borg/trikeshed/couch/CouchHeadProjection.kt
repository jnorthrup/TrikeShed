package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.mutable.mutableSeriesOf
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

    // docId -> current committed frame (including tombstones)
    private val frames = mutableMapOf<String, CouchCommittedFrame>()

    fun applyCommit(frame: CouchCommittedFrame) {
        val existingFrame = frames[frame.docId]

        frames[frame.docId] = frame

        if (frame.deleted) {
            val idx = docIndex.remove(frame.docId)
            if (idx != null) {
                removeFromFieldIndex(docs[idx])
                docs.removeAt(idx)
                rebuildDocIndex()
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
        for ((i, doc) in docs.view.withIndex()) {
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
        val matchedIds = fieldIndex[fieldName]?.get(value) ?: emptySet()
        val matched = ArrayList<Document>(matchedIds.size)
        for (id in matchedIds) {
            docIndex[id]?.let { idx -> matched.add(docs[idx]) }
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
}
