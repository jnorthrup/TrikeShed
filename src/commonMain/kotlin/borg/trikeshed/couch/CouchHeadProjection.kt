package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.lib.`▶`
import borg.trikeshed.collections.MutableSeries
import borg.trikeshed.collections.mutableSeriesOf
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.ColumnMeta
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

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

/** Immutable attachment metadata captured at the head publication boundary. */
data class CouchHeadSlot(
    val docId: String, val rev: String, val sequence: Long, val deleted: Boolean,
    val contentId: String?, val contentType: String?, val length: String?,
    val attachmentDeleted: Boolean, val ambiguous: Boolean,
)

/**
 * CouchHeadProjection - Replayable projection of the current document state.
 */
class CouchHeadProjection {
    private val gate = SynchronizedObject()
    private val docs = mutableSeriesOf<Document>()
    private val docIndex = mutableMapOf<String, Int>() // docId -> index in docs
    private val fieldIndex = mutableMapOf<String, MutableMap<Any, MutableSet<String>>>()
    private val indexQueryCache = mutableMapOf<Pair<String, Any>, IntArray>()

    // docId -> current committed frame (including tombstones)
    private val frames = mutableMapOf<String, CouchHeadSlot>()
    private var lastSequence: Long = -1L

    fun applyCommit(frame: CouchCommittedFrame) = synchronized(gate) {
        val existingFrame = frames[frame.docId]
        if (existingFrame != null) {
            require(frame.sequence > existingFrame.sequence) { "stale revision rejected" }
            require(frame.rev != existingFrame.rev) { "duplicate rev rejected" }
        }

        // Strictly monotonic, gaps allowed: a commit that fails AFTER minting its sequence number
        // must not poison every later commit. The no-gaps discipline lives in the ingress; the
        // projection only refuses to run time backwards.
        require(lastSequence == -1L || frame.sequence > lastSequence || frame.sequence == 0L) {
            "_changes resumes after the sequence without going backwards"
        }
        lastSequence = frame.sequence

        require(frame.deleted || frame.doc?.id == frame.docId) {
            "Insert/Update frame docId must match document id"
        }

        frames[frame.docId] = slot(frame)
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
            val values = fieldIndex[field.name] ?: continue
            val ids = values[field.value] ?: continue
            ids.remove(doc.id)
            if (ids.isEmpty()) values.remove(field.value)
            if (values.isEmpty()) fieldIndex.remove(field.name)
        }
    }

    fun get(docId: String): Document? {
        val idx = docIndex[docId] ?: return null
        return docs[idx]
    }

    /** The store sequence that last committed [docId], or null when it is unknown here. */
    fun sequenceOf(docId: String): Long? = frames[docId]?.sequence

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

    /** Capture the slot count, then read each immutable committed identity atomically. */
    fun slots(): Series<CouchHeadSlot> = synchronized(gate) {
        docs.a j { index: Int -> synchronized(gate) { requireNotNull(frames[docs[index].id]) } }
    }

    /** Read one immutable committed identity without exposing the mutable head body. */
    fun slot(docId: String): CouchHeadSlot? = synchronized(gate) { frames[docId] }

    private fun slot(frame: CouchCommittedFrame): CouchHeadSlot {
        var cid: Field? = null
        var type: Field? = null
        var length: Field? = null
        var deleted: Field? = null
        var hidden = false
        var ambiguous = false
        for (field in frame.doc?.fields.orEmpty()) {
            when (field.name) {
                "_id" -> ambiguous = true
                "contentId" -> if (cid == null) cid = field else ambiguous = true
                "contentType" -> if (type == null) type = field else ambiguous = true
                "length" -> if (length == null) length = field else ambiguous = true
                "deleted" -> {
                    if (deleted == null) deleted = field else ambiguous = true
                    if ((field.value as? String) == "true") hidden = true
                }
            }
        }
        return CouchHeadSlot(frame.docId, frame.rev, frame.sequence, frame.deleted,
            cid?.value as? String, type?.value as? String, length?.value as? String, hidden, ambiguous)
    }

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
