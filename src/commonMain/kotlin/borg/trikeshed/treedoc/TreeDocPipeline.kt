package borg.trikeshed.treedoc

import borg.trikeshed.cursor.*
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.Syntax

class TreeDocPipeline(
    private val cas: CasStore,
    private val maxFrameBytes: Int
) {
    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be greater than 0" }
    }

    private fun createFrameRow(values: Series<Any?>): RowVec {
        val meta0: `ColumnMeta↻` = { TreeDocFrameColumn.DOCUMENT_ORDINAL.meta }
        val meta1: `ColumnMeta↻` = { TreeDocFrameColumn.PAYLOAD.meta }
        val metas: Series<`ColumnMeta↻`> = values.size j { i: Int ->
            if (i == 0) meta0 else meta1
        }
        return borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>(values, metas)
    }

    private fun createDocRow(values: Series<Any?>): RowVec {
        val metaString: `ColumnMeta↻` = { ColumnMeta("path", borg.trikeshed.isam.meta.IOMemento.IoString) }
        val metaId: `ColumnMeta↻` = { ColumnMeta("cid", borg.trikeshed.isam.meta.IOMemento.IoByteArray) }
        val metaInt: `ColumnMeta↻` = { ColumnMeta("frameInfo", borg.trikeshed.isam.meta.IOMemento.IoInt) }
        val metas: Series<`ColumnMeta↻`> = values.size j { i: Int ->
            when {
                i < 2 -> metaString
                i == 2 -> metaId
                else -> metaInt
            }
        }
        return borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>(values, metas)
    }

    /**
     * Stores a series of TreeDocuments, splitting them into frames and storing
     * them in the CAS. Returns a typed meta-series indexable by TreeDocK.
     */
    fun store(documents: Series<TreeDocument>): Series<Any?> {
        var frameCount = 0
        val framesList = mutableListOf<RowVec>()
        val docFirstFrame = IntArray(documents.size)
        val docFrameCount = IntArray(documents.size)

        for (docOrdinal in 0 until documents.size) {
            val doc = documents.b(docOrdinal)
            val bytes = doc.bytes
            docFirstFrame[docOrdinal] = frameCount

            if (bytes.isEmpty()) {
                val chunk = ByteArray(0)
                val cid = cas.put(chunk)
                val rowValues: Series<Any?> = 2 j { i: Int -> if (i == 0) docOrdinal else cid }
                framesList.add(createFrameRow(rowValues))
                frameCount++
                docFrameCount[docOrdinal] = 1
                continue
            }

            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(maxFrameBytes, bytes.size - offset)
                val chunk = bytes.sliceArray(offset until offset + length)
                val cid = cas.put(chunk)

                val rowValues: Series<Any?> = 2 j { i: Int -> if (i == 0) docOrdinal else cid }
                framesList.add(createFrameRow(rowValues))

                frameCount++
                offset += length
            }
            docFrameCount[docOrdinal] = frameCount - docFirstFrame[docOrdinal]
        }

        val frames: Cursor = framesList.size j { framesList[it] }
        val docsCursor = buildDocsCursor(documents, docFirstFrame, docFrameCount)

        // Build canonical manifest for CID
        val manifestJson = buildManifestJson(documents, framesList)
        val manifestDoc = confixDoc(manifestJson) // Fix: just pass string for JSON
        val manifestCid = ContentId.of(manifestDoc) // This canonicalizes internally
        cas.put(manifestDoc) // Puts canonical CBOR

        return TreeDocK.entries.size j { i ->
            when (TreeDocK.entries[i]) {
                TreeDocK.ArchiveId -> manifestCid
                TreeDocK.ManifestCid -> manifestCid
                TreeDocK.DocumentCount -> documents.size
                TreeDocK.FrameCount -> frameCount
                TreeDocK.Documents -> docsCursor
                TreeDocK.Frames -> frames
            }
        }
    }

    private fun buildDocsCursor(
        documents: Series<TreeDocument>,
        docFirstFrame: IntArray,
        docFrameCount: IntArray
    ): Cursor =
        documents.size j { i ->
            val doc = documents.b(i)
            val expectedCid = ContentId.of(doc.bytes)
            val rowValues: Series<Any?> = 5 j { j: Int ->
                when (j) {
                    0 -> doc.path
                    1 -> doc.mediaType
                    2 -> expectedCid
                    3 -> docFirstFrame[i]
                    4 -> docFrameCount[i]
                    else -> error("unexpected column $j")
                }
            }
            createDocRow(rowValues)
        }

    private fun buildManifestJson(documents: Series<TreeDocument>, frames: List<RowVec>): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"docs\":[")
        for (i in 0 until documents.size) {
            if (i > 0) sb.append(",")
            val doc = documents.b(i)
            sb.append("{\"path\":\"${doc.path}\",\"mediaType\":\"${doc.mediaType}\",\"cid\":\"${ContentId.of(doc.bytes).value}\"}")
        }
        sb.append("],")
        sb.append("\"frames\":[")
        for (i in 0 until frames.size) {
            if (i > 0) sb.append(",")
            val r = frames[i]
            val vals = (r as borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).leftSeries
            val docOrd = vals.b(0) as Int
            val cid = vals.b(1) as ContentId
            sb.append("{\"doc\":$docOrd,\"cid\":\"${cid.value}\"}")
        }
        sb.append("]")
        sb.append("}")
        return sb.toString()
    }

    /**
     * Restores a document from the archive by its ordinal.
     */
    fun restoreDocument(archive: Series<Any?>, ordinal: Int): ByteArray {
        val frames = archive.b(TreeDocK.Frames.ordinal) as Cursor
        val documents = archive.b(TreeDocK.Documents.ordinal) as Cursor

        if (ordinal < 0 || ordinal >= documents.size) {
            throw IllegalArgumentException("Invalid document ordinal: $ordinal")
        }

        val docVals = (documents.b(ordinal) as borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).leftSeries
        val expectedCid = docVals.b(2) as ContentId
        val firstFrame = docVals.b(3) as Int
        val docFrameCount = docVals.b(4) as Int

        var totalBytes = 0
        val chunks = mutableListOf<ByteArray>()

        // Direct slice — doc cursor carries firstFrameOrdinal/frameCount so
        // restore is O(f_d) not O(F). No scan over the full frame cursor.
        for (i in firstFrame until firstFrame + docFrameCount) {
            val frame = frames.b(i)
            val vals = (frame as borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).leftSeries
            val cid = vals.b(1) as ContentId
            val chunk = cas.get(cid) ?: throw IllegalStateException("CAS corruption: chunk $cid not found")
            // cas.get verifies the digest implicitly
            chunks.add(chunk)
            totalBytes += chunk.size
        }

        val result = ByteArray(totalBytes)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }

        val actualCid = ContentId.of(result)
        if (actualCid != expectedCid) {
            throw IllegalStateException("Restored document CID $actualCid does not match expected $expectedCid")
        }

        return result
    }
}
