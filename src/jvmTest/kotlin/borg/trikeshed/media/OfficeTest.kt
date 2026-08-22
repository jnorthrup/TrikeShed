package borg.trikeshed.media

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipEntry as JZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OfficeTest {
    private val inflate: suspend (ByteArray) -> ByteArray = { raw ->
        Inflater(true).run { setInput(raw); val buf = ByteArray(1 shl 16); val n = inflate(buf); end(); buf.copyOf(n) }
    }
    private fun zip(vararg parts: Pair<String, String>, stored: Set<String> = emptySet()): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, text) in parts) {
                val bytes = text.encodeToByteArray()
                val e = JZipEntry(name)
                if (name in stored) { e.method = JZipEntry.STORED; e.size = bytes.size.toLong(); e.crc = CRC32().apply { update(bytes) }.value }
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun docxParagraphsDeflatedAndStored() = runBlocking {
        val doc = """<w:document><w:body><w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t xml:space="preserve">&amp; world</w:t></w:r></w:p><w:p><w:r><w:t>Second</w:t></w:r></w:p></w:body></w:document>"""
        val bytes = zip("[Content_Types].xml" to "<Types/>", "word/document.xml" to doc)
        assertEquals(2, bytes.zipEntries().a)
        assertEquals("Hello & world\nSecond", bytes.officeText(inflate))
        val storedBytes = zip("word/document.xml" to doc, stored = setOf("word/document.xml"))
        assertEquals(0, storedBytes.zipEntries().b(0).b.a)
        assertEquals("Hello & world\nSecond", storedBytes.officeText(inflate))
        assertNull(bytes.zipEntry("nope.xml", inflate))
    }

    @Test
    fun pptxSlidesInOrderAndXlsxStrings() = runBlocking {
        val slide = { t: String -> "<p:sld><p:txBody><a:p><a:r><a:t>$t</a:t></a:r></a:p></p:txBody></p:sld>" }
        val pptx = zip("ppt/slides/slide10.xml" to slide("ten"), "ppt/slides/slide2.xml" to slide("two"), "ppt/slides/slide1.xml" to slide("one"))
        assertEquals("one\n\ntwo\n\nten", pptx.officeText(inflate))
        val xlsx = zip("xl/sharedStrings.xml" to "<sst><si><t>alpha</t></si><si><t>beta</t></si></sst>", "xl/worksheets/sheet1.xml" to "<row/>")
        assertEquals("alphabeta", xlsx.officeText(inflate))
    }

    @Test
    fun ooxmlTextTagsBreaksAndEntities() {
        // w:t (docx), a:t (pptx), bare t (xlsx); </w:p>, </a:p>, </row> end lines; attributes on t; entities unescaped.
        val xml = """<w:p><w:r><w:t xml:space="preserve">a &lt;b&gt; </w:t><w:t>&quot;c&quot;</w:t></w:r></w:p>""" +
            """<a:p><a:r><a:t>it&apos;s &amp;</a:t></a:r></a:p><row><c><t>x</t></c></row><w:tbl>ignored</w:tbl>"""
        assertEquals("a <b> \"c\"\nit's &\nx", ooxmlText(xml))
        assertEquals("", ooxmlText("<w:document/>"))
    }

    @Test
    fun truncatedZipIndexesNothingAndNeverCrashes() = runBlocking {
        val whole = zip("word/document.xml" to "<w:p><w:t>hi</w:t></w:p>", "x.xml" to "<a/>")
        assertEquals(2, whole.zipEntries().a)
        // Cut the EOCD off: no central directory → empty index, null entry, empty text.
        val noEocd = whole.copyOf(whole.size - 22)
        assertEquals(0, noEocd.zipEntries().a)
        assertNull(noEocd.zipEntry("word/document.xml", inflate))
        assertEquals("", noEocd.officeText(inflate))
        // Every prefix length: never an index crash.
        for (n in 0 until whole.size) whole.copyOf(n).zipEntries()
        // EOCD kept but its offset points into the void: entries run past the buffer → empty.
        val badOffset = whole.copyOf().also { it[it.size - 6] = 0x7F; it[it.size - 5] = 0x7F.toByte(); it[it.size - 4] = 0x7F; it[it.size - 3] = 0x7F }
        assertEquals(0, badOffset.zipEntries().a)
        assertEquals(0, ByteArray(0).zipEntries().a)
        assertEquals(0, "not a zip at all".encodeToByteArray().zipEntries().a)
    }

    @Test
    fun zip64SentinelIsRefusedClearly() {
        val whole = zip("word/document.xml" to "<w:t>hi</w:t>")
        val z64 = whole.copyOf().also { it[it.size - 12] = 0xFF.toByte(); it[it.size - 11] = 0xFF.toByte() } // EOCD entry count 0xFFFF
        assertEquals("zip64", assertFailsWith<UnsupportedOperationException> { z64.zipEntries() }.message)
    }

    @Test
    fun prepassGraysAndStretches() {
        val px = byteArrayOf(0xCC.toByte(), 0x22, 0x00, 0xFF.toByte(), 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()).ocrPrepassRgba()
        assertEquals(listOf(82, 82, 82), px.slice(0..2).map { it.toInt() and 0xFF })
        assertEquals(listOf(0, 0, 0), px.slice(4..6).map { it.toInt() and 0xFF })
        assertEquals(listOf(255, 255, 255), px.slice(8..10).map { it.toInt() and 0xFF })
        assertEquals(0xFF, px[3].toInt() and 0xFF)
    }
}
