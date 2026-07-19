package borg.trikeshed.treedoc

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.seriesOf
import borg.trikeshed.lib.size
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.isam.meta.IOMemento
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import borg.trikeshed.cursor.ReifiedSplitSeries2
import borg.trikeshed.cursor.`ColumnMeta↻`

class TreeDocPipelineTest {

    @Test
    fun testTwoDocsFrameSize4() {
        val cas = CasStore.inMemory()
        val pipeline = TreeDocPipeline(cas, 4)

        val docs = seriesOf(listOf(
            TreeDocument("doc1.txt", "text/plain", "abcdefgh".encodeToByteArray()),
            TreeDocument("doc2.txt", "text/plain", "xyz".encodeToByteArray())
        ))

        val archive = pipeline.store(docs)

        assertEquals(2, archive.b(TreeDocK.DocumentCount.ordinal) as Int)
        assertEquals(3, archive.b(TreeDocK.FrameCount.ordinal) as Int)

        val restored1 = pipeline.restoreDocument(archive, 0)
        assertEquals("abcdefgh", restored1.decodeToString())

        val restored2 = pipeline.restoreDocument(archive, 1)
        assertEquals("xyz", restored2.decodeToString())
    }

    @Test
    fun testFrameSize3GivesPayloadRowsWithSchema() {
        val cas = CasStore.inMemory()
        val pipeline = TreeDocPipeline(cas, 3)

        val docs = seriesOf(listOf(
            TreeDocument("doc1.txt", "text/plain", "abcdef".encodeToByteArray())
        ))

        val archive = pipeline.store(docs)
        val frames = archive.b(TreeDocK.Frames.ordinal) as Cursor

        assertEquals(2, frames.size)

        val frame0 = frames.b(0)
        val schema0 = (frame0 as ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).rightSeries.b(0).invoke()
        val schema1 = (frame0 as ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).rightSeries.b(1).invoke()
        assertEquals("DOCUMENT_ORDINAL", schema0.name.toString())
        assertEquals(IOMemento.IoInt, schema0.type)
        assertEquals("PAYLOAD", schema1.name.toString())
        assertEquals(IOMemento.IoByteArray, schema1.type)

        val restored = pipeline.restoreDocument(archive, 0)
        assertEquals("abcdef", restored.decodeToString())
    }

    @Test
    fun testUtf8BytesRestoreExactlyAndCasCorruptionThrows() {
        val cas = CasStore.inMemory()
        val pipeline = TreeDocPipeline(cas, 2)

        val bytes = "Hello, 世界".encodeToByteArray()
        val docs = seriesOf(listOf(TreeDocument("doc", "text/plain", bytes)))

        val archive = pipeline.store(docs)

        val restored = pipeline.restoreDocument(archive, 0)
        assertTrue(bytes.contentEquals(restored))

        val frames = archive.b(TreeDocK.Frames.ordinal) as Cursor
        val firstCid = (frames.b(0) as ReifiedSplitSeries2<Any?, `ColumnMeta↻`>).leftSeries.b(1) as ContentId
        cas.corrupt(firstCid)

        assertFailsWith<IllegalStateException> {
            pipeline.restoreDocument(archive, 0)
        }
    }

    @Test
    fun testDeterministicArchiveAndManifestCid() {
        val cas1 = CasStore.inMemory()
        val pipeline1 = TreeDocPipeline(cas1, 4)
        val docs1 = seriesOf(listOf(TreeDocument("d", "text/plain", "abc".encodeToByteArray())))
        val archive1 = pipeline1.store(docs1)

        val cas2 = CasStore.inMemory()
        val pipeline2 = TreeDocPipeline(cas2, 4)
        val docs2 = seriesOf(listOf(TreeDocument("d", "text/plain", "abc".encodeToByteArray())))
        val archive2 = pipeline2.store(docs2)

        val cid1 = archive1.b(TreeDocK.ArchiveId.ordinal) as ContentId
        val cid2 = archive2.b(TreeDocK.ArchiveId.ordinal) as ContentId

        assertEquals(cid1, cid2)
        assertEquals(cid1, archive1.b(TreeDocK.ManifestCid.ordinal))
    }

    @Test
    fun testEmptyArchiveAndInvalidFrameSize() {
        assertFailsWith<IllegalArgumentException> {
            TreeDocPipeline(CasStore.inMemory(), 0)
        }

        assertFailsWith<IllegalArgumentException> {
            TreeDocPipeline(CasStore.inMemory(), -1)
        }

        val cas = CasStore.inMemory()
        val pipeline = TreeDocPipeline(cas, 10)
        val emptyDocs = seriesOf(emptyList<TreeDocument>())

        val archive = pipeline.store(emptyDocs)
        assertEquals(0, archive.b(TreeDocK.DocumentCount.ordinal))
        assertEquals(0, archive.b(TreeDocK.FrameCount.ordinal))
    }
}
