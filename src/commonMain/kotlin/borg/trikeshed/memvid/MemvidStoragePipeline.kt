package borg.trikeshed.memvid

import borg.trikeshed.cursor.*
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.confix.confixDoc

class MemvidStoragePipeline(
    private val cas: CasStore,
    private val maxFrameBytes: Int
) {
    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be greater than 0" }
    }

    private fun createFrameRow(docOrd: Int, frameOrd: Int, offset: Int, length: Int, chunkCid: String, payload: ByteArray): RowVec {
        val values: Series<Any?> = 6 j { i: Int -> 
            when (i) {
                0 -> docOrd
                1 -> frameOrd
                2 -> offset
                3 -> length
                4 -> chunkCid
                else -> payload
            }
        }
        val metas: Series<`ColumnMeta↻`> = 6 j { i: Int -> 
            when (i) {
                0 -> { { MemvidFrameColumn.DOCUMENT_ORDINAL.meta } }
                1 -> { { MemvidFrameColumn.FRAME_ORDINAL.meta } }
                2 -> { { MemvidFrameColumn.BYTE_OFFSET.meta } }
                3 -> { { MemvidFrameColumn.BYTE_LENGTH.meta } }
                4 -> { { MemvidFrameColumn.CHUNK_CID.meta } }
                else -> { { MemvidFrameColumn.PAYLOAD.meta } }
            }
        }
        return borg.trikeshed.lib.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>(values, metas)
    }

    private fun createDocRow(docOrd: Int, path: String, mediaType: String, byteSize: Int, docCid: String, firstFrameOrd: Int, frameCount: Int): RowVec {
        val values: Series<Any?> = 7 j { i: Int -> 
            when (i) {
                0 -> docOrd
                1 -> path
                2 -> mediaType
                3 -> byteSize
                4 -> docCid
                5 -> firstFrameOrd
                else -> frameCount
            }
        }
        val metas: Series<`ColumnMeta↻`> = 7 j { i: Int -> 
            when (i) {
                0 -> { { MemvidDocumentColumn.DOCUMENT_ORDINAL.meta } }
                1 -> { { MemvidDocumentColumn.PATH.meta } }
                2 -> { { MemvidDocumentColumn.MEDIA_TYPE.meta } }
                3 -> { { MemvidDocumentColumn.BYTE_SIZE.meta } }
                4 -> { { MemvidDocumentColumn.DOCUMENT_CID.meta } }
                5 -> { { MemvidDocumentColumn.FIRST_FRAME_ORDINAL.meta } }
                else -> { { MemvidDocumentColumn.FRAME_COUNT.meta } }
            }
        }
        return borg.trikeshed.lib.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>(values, metas)
    }

    /**
     * Stores a series of MemvidDocuments, splitting them into frames and storing
     * them in the CAS. Returns a typed meta-series indexable by MemvidK.
     */
    fun store(documents: Series<MemvidDocument>): Series<Any?> {
        var globalFrameCount = 0
        val framesList = mutableListOf<RowVec>()
        val docsList = mutableListOf<RowVec>()

        val docManifestList = mutableListOf<Map<String, Any?>>()
        val frameManifestList = mutableListOf<Map<String, Any?>>()

        for (docOrdinal in 0 until documents.size) {
            val doc = documents.b(docOrdinal)
            val bytes = doc.bytes
            val docCid = ContentId.of(bytes).value
            
            if (bytes.isEmpty()) {
                val chunk = ByteArray(0)
                // Zero-length restoration semantics: Do not manufacture an inconsistent frame.
                // We add document row with 0 frames.
                docsList.add(createDocRow(docOrdinal, doc.path, doc.mediaType, 0, docCid, globalFrameCount, 0))
                
                docManifestList.add(mapOf(
                    "path" to doc.path,
                    "mediaType" to doc.mediaType,
                    "size" to 0,
                    "cid" to docCid,
                    "frameCount" to 0
                ))
                continue
            }
            
            var offset = 0
            val initialFrameOrdinal = globalFrameCount
            var localFrameCount = 0

            while (offset < bytes.size) {
                val length = minOf(maxFrameBytes, bytes.size - offset)
                val chunk = bytes.sliceArray(offset until offset + length)
                val cid = cas.put(chunk) // Put to cas returns ContentId
                
                // Add frame row
                framesList.add(createFrameRow(docOrdinal, globalFrameCount, offset, length, cid.value, chunk))
                
                frameManifestList.add(mapOf(
                    "docOrdinal" to docOrdinal,
                    "frameOrdinal" to globalFrameCount,
                    "offset" to offset,
                    "length" to length,
                    "cid" to cid.value
                ))

                globalFrameCount++
                localFrameCount++
                offset += length
            }
            
            docsList.add(createDocRow(docOrdinal, doc.path, doc.mediaType, bytes.size, docCid, initialFrameOrdinal, localFrameCount))
            
            docManifestList.add(mapOf(
                "path" to doc.path,
                "mediaType" to doc.mediaType,
                "size" to bytes.size,
                "cid" to docCid,
                "frameCount" to localFrameCount
            ))
        }
        
        val framesCursor: Cursor = framesList.size j { framesList[it] }
        val docsCursor: Cursor = docsList.size j { docsList[it] }

        // Build deterministic Confix using existing Confix APIs
        val docJsonParts = mutableListOf<String>()
        for (i in 0 until docManifestList.size) {
            val d = docManifestList[i]
            docJsonParts.add("{\"path\":\"${d["path"]}\",\"mediaType\":\"${d["mediaType"]}\",\"size\":${d["size"]},\"cid\":\"${d["cid"]}\",\"frameCount\":${d["frameCount"]}}")
        }
        val framesJsonParts = mutableListOf<String>()
        for (i in 0 until frameManifestList.size) {
            val f = frameManifestList[i]
            framesJsonParts.add("{\"docOrdinal\":${f["docOrdinal"]},\"frameOrdinal\":${f["frameOrdinal"]},\"offset\":${f["offset"]},\"length\":${f["length"]},\"cid\":\"${f["cid"]}\"}")
        }
        
        val manifestJson = "{\"docs\":[${docJsonParts.joinToString(",")}]," + "\"frames\":[${framesJsonParts.joinToString(",")}]}"
        
        val manifestDoc = confixDoc(manifestJson)
        val manifestCid = ContentId.of(manifestDoc)
        cas.put(manifestDoc)

        return MemvidK.entries.size j { i: Int ->
            when (MemvidK.entries[i]) {
                MemvidK.ArchiveId -> manifestCid
                MemvidK.ManifestCid -> manifestCid
                MemvidK.DocumentCount -> documents.size
                MemvidK.FrameCount -> globalFrameCount
                MemvidK.Documents -> docsCursor
                MemvidK.Frames -> framesCursor
            }
        }
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

        val docVals = (documents.b(ordinal) as borg.trikeshed.lib.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).leftSeries
        val expectedCid = ContentId(docVals.b(MemvidDocumentColumn.DOCUMENT_CID.ordinal) as String)
        val firstFrameOrd = docVals.b(MemvidDocumentColumn.FIRST_FRAME_ORDINAL.ordinal) as Int
        val frameCount = docVals.b(MemvidDocumentColumn.FRAME_COUNT.ordinal) as Int

        if (frameCount == 0) {
            return ByteArray(0)
        }

        var totalBytes = 0
        val chunks = mutableListOf<ByteArray>()

        // Frames are stored sequentially
        for (i in firstFrameOrd until firstFrameOrd + frameCount) {
            val frame = frames.b(i) as borg.trikeshed.lib.ReifiedSplitSeries2<Any?, `ColumnMeta↻`>
            val vals = frame.leftSeries
            
            // PAYLOAD must expose resolved ByteArray bytes through CAS!
            val chunkCid = ContentId(vals.b(MemvidFrameColumn.CHUNK_CID.ordinal) as String)
            val chunk = cas.get(chunkCid) ?: throw IllegalStateException("CAS corruption: chunk $chunkCid not found")
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
