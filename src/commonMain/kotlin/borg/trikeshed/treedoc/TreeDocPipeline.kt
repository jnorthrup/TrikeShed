package borg.trikeshed.treedoc

import borg.trikeshed.cursor.*
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.nioSupervisor
import kotlin.coroutines.coroutineContext

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

    suspend fun archive(documents: Series<TreeDocument>): Series<Any?> {
        val nioSupervisor = coroutineContext.nioSupervisor()
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

        // Encode the structured manifest, not the Confix parser's lazy child-row views.
        val manifestCid = cas.put(CanonicalCbor.encodeMap(buildManifest(documents, framesList)))

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

        val manifestCid = cas.put(CanonicalCbor.encodeMap(buildManifest(documents, framesList)))

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

    private fun buildManifest(documents: Series<TreeDocument>, frames: List<RowVec>): Map<String, Any?> {
        val docs = (0 until documents.size).map { i ->
            val doc = documents.b(i)
            mapOf("path" to doc.path, "mediaType" to doc.mediaType, "cid" to ContentId.of(doc.bytes).value)
        }
        val chunks = frames.map { r ->
            val vals = (r as borg.trikeshed.cursor.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).leftSeries
            mapOf("doc" to vals.b(0), "cid" to (vals.b(1) as ContentId).value)
        }
        return mapOf("docs" to docs, "frames" to chunks)
    }

    /** Reconstitute cursor indexes from the durable manifest, never a session cache. */
    fun open(cid: ContentId, maxDocuments: Int, maxFrames: Int, maxManifestBytes: Int): Series<Any?> {
        require(maxDocuments >= 0 && maxFrames >= 0 && maxManifestBytes > 0)
        val bytes = cas.get(cid) ?: throw NoSuchElementException("Archive not found: $cid")
        require(bytes.size <= maxManifestBytes) { "Manifest byte limit exceeded" }
        val manifest = CanonicalCbor.decodeMap(bytes)
        val docs = manifest["docs"] as? List<*> ?: error("Invalid archive documents")
        val chunks = manifest["frames"] as? List<*> ?: error("Invalid archive frames")
        require(docs.size <= maxDocuments && chunks.size <= maxFrames) { "Archive index limit exceeded" }
        val first = IntArray(docs.size)
        val counts = IntArray(docs.size)
        var previous = -1
        val frames = chunks.mapIndexed { i, raw ->
            val frame = raw as? Map<*, *> ?: error("Invalid frame")
            val number = frame["doc"] as? Number ?: error("Invalid document ordinal")
            val ordinal = number.toInt()
            require(number.toDouble() == ordinal.toDouble() && ordinal in docs.indices && ordinal >= previous) { "Invalid frame order" }
            if (counts[ordinal] == 0) first[ordinal] = i
            counts[ordinal]++
            previous = ordinal
            val frameCid = ContentId(frame["cid"] as? String ?: error("Invalid frame CID"))
            createFrameRow(2 j { column -> if (column == 0) ordinal else frameCid })
        }.toSeries()
        val rows = docs.mapIndexed { i, raw ->
            val doc = raw as? Map<*, *> ?: error("Invalid document")
            require(counts[i] > 0) { "Missing document frames" }
            val path = doc["path"] as? String ?: error("Invalid path")
            val mediaType = doc["mediaType"] as? String ?: error("Invalid media type")
            val docCid = ContentId(doc["cid"] as? String ?: error("Invalid document CID"))
            createDocRow(5 j { column -> when (column) {
                0 -> path; 1 -> mediaType; 2 -> docCid; 3 -> first[i]; else -> counts[i]
            } })
        }.toSeries()
        return TreeDocK.entries.size j { i -> when (TreeDocK.entries[i]) {
            TreeDocK.ArchiveId, TreeDocK.ManifestCid -> cid
            TreeDocK.DocumentCount -> docs.size
            TreeDocK.FrameCount -> chunks.size
            TreeDocK.Documents -> rows
            TreeDocK.Frames -> frames
        } }
    }

    suspend fun replay(archive: Series<Any?>, ordinal: Int): ByteArray {
        val nioSupervisor = coroutineContext.nioSupervisor()
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

    /**
     * Restores a document from the archive by its ordinal.
     */
    fun restoreDocument(archive: Series<Any?>, ordinal: Int, maxBytes: Int = Int.MAX_VALUE): ByteArray {
        require(maxBytes >= 0)
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
            require(chunk.size <= maxBytes - totalBytes) { "Document byte limit exceeded" }
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
