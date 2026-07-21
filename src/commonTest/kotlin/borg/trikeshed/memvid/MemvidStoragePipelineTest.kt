package borg.trikeshed.memvid

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.seriesOf
import borg.trikeshed.lib.b
import borg.trikeshed.lib.size
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import borg.trikeshed.lib.ReifiedSplitSeries2
import borg.trikeshed.cursor.`ColumnMeta↻`

class MemvidStoragePipelineTest {

    @Test
    fun testTwoDocsFrameSize4() {
        val cas = CasStore.inMemory()
        val pipeline = MemvidStoragePipeline(cas, 4)
        
        val docs = seriesOf(
            MemvidDocument("doc1.txt", "text/plain", "abcdefgh".encodeToByteArray()),
            MemvidDocument("doc2.txt", "text/plain", "xyz".encodeToByteArray())
        )
        
        val archive = pipeline.store(docs)
        
        assertEquals(2, archive.b(MemvidK.DocumentCount.ordinal) as Int)
        assertEquals(3, archive.b(MemvidK.FrameCount.ordinal) as Int)
        
        val restored1 = pipeline.restoreDocument(archive, 0)
        assertEquals("abcdefgh", restored1.decodeToString())
        
        val restored2 = pipeline.restoreDocument(archive, 1)
        assertEquals("xyz", restored2.decodeToString())
    }

    @Test
    fun testFrameSize3GivesPayloadRowsWithSchema() {
        val cas = CasStore.inMemory()
        val pipeline = MemvidStoragePipeline(cas, 3)
        
        val docs = seriesOf(
            MemvidDocument("doc1.txt", "text/plain", "abcdef".encodeToByteArray())
        )
        
        val archive = pipeline.store(docs)
        val frames = archive.b(MemvidK.Frames.ordinal) as Cursor
        
        assertEquals(2, frames.size)
        
        val frame0 = frames.b(0)
        val metaSeries = (frame0 as ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).rightSeries
        val schema0 = metaSeries.b(0).invoke()
        val schemaPayload = metaSeries.b(MemvidFrameColumn.PAYLOAD.ordinal).invoke()
        assertEquals("DOCUMENT_ORDINAL", schema0.name.toString())
        assertEquals(IOMemento.IoInt, schema0.type)
        assertEquals("PAYLOAD", schemaPayload.name.toString())
        assertEquals(IOMemento.IoBytes, schemaPayload.type)
        
        val restored = pipeline.restoreDocument(archive, 0)
        assertEquals("abcdef", restored.decodeToString())
    }

    @Test
    fun testUtf8BytesRestoreExactlyAndCasCorruptionThrows() {
        val cas = CasStore.inMemory()
        val pipeline = MemvidStoragePipeline(cas, 2)
        
        val bytes = "Hello, 世界".encodeToByteArray()
        val docs = seriesOf(MemvidDocument("doc", "text/plain", bytes))
        
        val archive = pipeline.store(docs)
        
        val restored = pipeline.restoreDocument(archive, 0)
        assertTrue(bytes.contentEquals(restored))
        
        val frames = archive.b(MemvidK.Frames.ordinal) as Cursor
        val firstCidStr = (frames.b(0) as ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).leftSeries.b(MemvidFrameColumn.CHUNK_CID.ordinal) as String
        cas.corrupt(ContentId(firstCidStr))
        
        assertFailsWith<IllegalStateException> {
            pipeline.restoreDocument(archive, 0)
        }
    }

    @Test
    fun testDeterministicArchiveAndManifestCid() {
        val cas1 = CasStore.inMemory()
        val pipeline1 = MemvidStoragePipeline(cas1, 4)
        val docs1 = seriesOf(MemvidDocument("d", "text/plain", "abc".encodeToByteArray()))
        val archive1 = pipeline1.store(docs1)
        
        val cas2 = CasStore.inMemory()
        val pipeline2 = MemvidStoragePipeline(cas2, 4)
        val docs2 = seriesOf(MemvidDocument("d", "text/plain", "abc".encodeToByteArray()))
        val archive2 = pipeline2.store(docs2)
        
        val cid1 = archive1.b(MemvidK.ArchiveId.ordinal) as ContentId
        val cid2 = archive2.b(MemvidK.ArchiveId.ordinal) as ContentId
        
        assertEquals(cid1, cid2)
        assertEquals(cid1, archive1.b(MemvidK.ManifestCid.ordinal))
    }

    @Test
    fun testEmptyArchiveAndInvalidFrameSize() {
        assertFailsWith<IllegalArgumentException> {
            MemvidStoragePipeline(CasStore.inMemory(), 0)
        }
        
        assertFailsWith<IllegalArgumentException> {
            MemvidStoragePipeline(CasStore.inMemory(), -1)
        }
        
        val cas = CasStore.inMemory()
        val pipeline = MemvidStoragePipeline(cas, 10)
        val emptyDocs = seriesOf(emptyList<MemvidDocument>())
        
        val archive = pipeline.store(emptyDocs)
        assertEquals(0, archive.b(MemvidK.DocumentCount.ordinal))
        assertEquals(0, archive.b(MemvidK.FrameCount.ordinal))
    }
}
