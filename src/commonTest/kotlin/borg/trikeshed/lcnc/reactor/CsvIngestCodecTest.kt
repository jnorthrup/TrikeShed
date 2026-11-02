package borg.trikeshed.lcnc.reactor

import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import borg.trikeshed.lcnc.collections.associative.PropertyValue
import borg.trikeshed.lcnc.collections.associative.PropertyType

class CsvIngestCodecTest {
    
    @Test
    fun testCsvIngestProducesDatabase() = runTest {
        val codec = CsvIngestCodec()
        
        val csvContent = """
            id,title,value
            1,Row 1,100
            2,Row 2,200
        """.trimIndent()
        
        val result = codec.decodeText(csvContent, IngestFormat.CSV)
        
        assertEquals(1, result.a)
        val db = result.b(0)
        assertTrue(db is LcncDatabase)
        
        val pages = db.pages
        assertEquals(2, pages.a)
        
        val page1 = pages.b(0)
        assertEquals("1", page1.id)
        assertEquals("Row 1", page1.title)
        assertEquals(db.id, page1.parentId)
        
        val page2 = pages.b(1)
        assertEquals("2", page2.id)
        assertEquals("Row 2", page2.title)
        assertEquals(db.id, page2.parentId)
    }
    @Test
    fun quotedCellsRetainExactStringsAndSource() = runTest {
        val text = "\uFEFFid,title,amount,description,empty\r\n1, Item ,000.10,\"a,\"\"b\"\"\r\nc\",\r\n"
        val db = CsvIngestCodec().decodeText(text, IngestFormat.CSV).b(0) as LcncDatabase
        val page = db.pages.b(0)
        val amount = page.contentBlocks.b(2).content as PropertyValue
        assertEquals(PropertyType.TEXT, amount.type)
        assertEquals("000.10", amount.value)
        assertEquals(" Item ", page.title)
        assertEquals("a,\"b\"\r\nc", (page.contentBlocks.b(3).content as PropertyValue).value)
        assertEquals("", (page.contentBlocks.b(4).content as PropertyValue).value)
        val source = page.contentBlocks.b(3).children!!.b(0).content as CsvCellSource
        assertEquals("\"a,\"\"b\"\"\r\nc\"", text.substring(source.begin, source.end))
        assertEquals(2, source.record)
        assertEquals(4, source.column)
        assertEquals(borg.trikeshed.job.ContentId.of(text.encodeToByteArray()).value, source.sourceCid)
        assertEquals(db.id, page.parentId)
    }

    @Test
    fun invalidRecordsFailBeforeReturningPages() = runTest {
        for (text in s_[
            "id,id\n1,2", "id,\n1,2", "id,title\n1", "id\n1,2",
            "id,title\n1,\"unfinished", "id,title\n1,a\"b", "id,title\n1,\"a\"b",
            "id,title\n1,a\n1,b"
        ].view) {
            assertFailsWith<IllegalArgumentException>(text) { CsvIngestCodec().decodeText(text, IngestFormat.CSV) }
        }
    }

    @Test
    fun tsvTrailingEmptyAndBounds() = runTest {
        val db = CsvIngestCodec().decodeText("id\ttitle\tx\n1\t\"a\tb\"\t", IngestFormat.TSV).b(0) as LcncDatabase
        assertEquals("a\tb", db.pages.b(0).title)
        assertEquals("", (db.pages.b(0).contentBlocks.b(2).content as PropertyValue).value)
        assertFailsWith<IllegalArgumentException> {
            CsvIngestCodec(maxFieldChars = 2).decodeText("id\n123", IngestFormat.CSV)
        }
        assertFailsWith<IllegalArgumentException> {
            CsvIngestCodec(maxRows = 1).decodeText("id\n1\n2", IngestFormat.CSV)
        }
        assertFailsWith<IllegalArgumentException> {
            CsvIngestCodec(maxInputChars = 3).decodeText("id\n1", IngestFormat.CSV)
        }
    }
    @Test
    fun sourceIdentityAndOrdinaryParentAreRetained() = runTest {
        val codec = CsvIngestCodec()
        val a = codec.decodeText("id,parent,amount\n1,external,0\n2,,", IngestFormat.CSV).b(0) as LcncDatabase
        val b = codec.decodeText("id,parent,amount\n1,external,0\n2,,", IngestFormat.CSV).b(0) as LcncDatabase
        assertEquals(a.id, b.id)
        assertEquals(a.id, a.pages.b(0).parentId)
        assertEquals("external", (a.pages.b(0).contentBlocks.b(1).content as PropertyValue).value)
        assertEquals("0", (a.pages.b(0).contentBlocks.b(2).content as PropertyValue).value)
        assertEquals("", (a.pages.b(1).contentBlocks.b(2).content as PropertyValue).value)
        val changed = codec.decodeText("id,parent,amount\n1,external,0\n2,,0", IngestFormat.CSV).b(0) as LcncDatabase
        assertTrue(a.id != changed.id)
    }

    @Test
    fun parserRejectsBareCrAndExcessColumns() {
        assertFailsWith<IllegalArgumentException> { borg.trikeshed.parse.csv.CSVUtil.records("id\r1") }
        assertFailsWith<IllegalArgumentException> { borg.trikeshed.parse.csv.CSVUtil.records("a,b", maxColumns = 1) }
        assertEquals(0, borg.trikeshed.parse.csv.CSVUtil.records("\uFEFF").a)
        val rows = borg.trikeshed.parse.csv.CSVUtil.records("a,b\n1,\n")
        assertEquals(2, rows.a)
        assertEquals("", rows.b(1).cells.b(1).value)
    }
}
