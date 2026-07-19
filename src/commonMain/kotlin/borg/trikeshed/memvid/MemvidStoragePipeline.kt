package borg.trikeshed.memvid

import borg.trikeshed.cursor.*
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.Syntax

class MemvidStoragePipeline(
    private val cas: CasStore,
    private val maxFrameBytes: Int
) {
    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be greater than 0" }
    }

    private fun createFrameRow(values: Series<Any?>): RowVec {
        val meta0: `ColumnMeta↻` = { MemvidFrameColumn.DOCUMENT_ORDINAL.meta }
        val meta1: `ColumnMeta↻` = { MemvidFrameColumn.PAYLOAD.meta }
        val metas: Series<`ColumnMeta↻`> = values.size j { i: Int ->
            if (i == 0) meta0 else meta1
        }
        return borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>(values, metas)
    }

    private fun createDocRow(values: Series<Any?>): RowVec {
        val metaString: `ColumnMeta↻` = { ColumnMeta("path", borg.trikeshed.isam.meta.IOMemento.IoString) }
        val metaId: `ColumnMeta↻` = { ColumnMeta("cid", borg.trikeshed.isam.meta.IOMemento.IoByteArray) }
        val metas: Series<`ColumnMeta↻`> = values.size j { i: Int ->
            if (i < 2) metaString else metaId
        }
        return borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>(values, metas)
    }

    /**
     * Stores a series of MemvidDocuments, splitting them into frames and storing
     * them in the CAS. Returns a typed meta-series indexable by MemvidK.
     */
    fun store(documents: Series<MemvidDocument>): Series<Any?> {
        var frameCount = 0
        val framesList = mutableListOf<RowVec>()

        for (docOrdinal in 0 until documents.size) {
            val doc = documents.b(docOrdinal)
            val bytes = doc.bytes

            if (bytes.isEmpty()) {
                val chunk = ByteArray(0)
                val cid = cas.put(chunk)
                val rowValues: Series<Any?> = 2 j { i: Int -> if (i == 0) docOrdinal else cid }
                framesList.add(createFrameRow(rowValues))
                frameCount++
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
        }

        val frames: Cursor = framesList.size j { framesList[it] }
        val docsCursor = buildDocsCursor(documents)

        // Build canonical manifest for CID
        val manifestJson = buildManifestJson(documents, framesList)
        val manifestDoc = confixDoc(manifestJson) // Fix: just pass string for JSON
        val manifestCid = ContentId.of(manifestDoc) // This canonicalizes internally
        cas.put(manifestDoc) // Puts canonical CBOR

        return MemvidK.entries.size j { i ->
            when (MemvidK.entries[i]) {
                MemvidK.ArchiveId -> manifestCid
                MemvidK.ManifestCid -> manifestCid
                MemvidK.DocumentCount -> documents.size
                MemvidK.FrameCount -> frameCount
                MemvidK.Documents -> docsCursor
                MemvidK.Frames -> frames
            }
        }
    }

    private fun buildDocsCursor(documents: Series<MemvidDocument>): Cursor =
        documents α { doc ->
            val expectedCid = ContentId.of(doc.bytes)
            val rowValues: Series<Any?> = 3 j { j: Int ->
                when (j) {
                    0 -> doc.path
                    1 -> doc.mediaType
                    else -> expectedCid
                }
            }
            createDocRow(rowValues)
        }

    private fun buildManifestJson(documents: Series<MemvidDocument>, frames: List<RowVec>): String {
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
        val frames = archive.b(MemvidK.Frames.ordinal) as Cursor
        val documents = archive.b(MemvidK.Documents.ordinal) as Cursor

        if (ordinal < 0 || ordinal >= documents.size) {
            throw IllegalArgumentException("Invalid document ordinal: $ordinal")
        }

        val docVals = (documents.b(ordinal) as borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).leftSeries
        val expectedCid = docVals.b(2) as ContentId

        var totalBytes = 0
        val chunks = mutableListOf<ByteArray>()

        for (i in 0 until frames.size) {
            val frame = frames.b(i)
            val vals = (frame as borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).leftSeries
            val frameDocOrdinal = vals.b(0) as Int
            if (frameDocOrdinal == ordinal) {
                val cid = vals.b(1) as ContentId
                val chunk = cas.get(cid) ?: throw IllegalStateException("CAS corruption: chunk $cid not found")
                // cas.get verifies the digest implicitly
                chunks.add(chunk)
                totalBytes += chunk.size
            }
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
