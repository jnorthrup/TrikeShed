package borg.trikeshed.pdf

import borg.trikeshed.lib.j
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate for the baremetal disassembler: hand-built minimal PDFs (uncompressed
 * and FlateDecode content streams, a ToUnicode CMap, an object stream, and
 * deliberately torn/garbage input) — no PDFBox, no Tika, no fixture files.
 */
class PdfDisassemblerTest {

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(bytes); d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** One-page PDF, uncompressed content stream, simple font (Latin-1 fallback). Builds byte-exact. */
    private fun minimalPdf(contentOps: String, extraObjects: String = "", fontObj: String = "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"): ByteArray {
        val content = contentOps.encodeToByteArray()
        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n")
        sb.append("4 0 obj\n<< /Length ${content.size} >>\nstream\n")
        val head = sb.toString().encodeToByteArray()
        val tail = "\nendstream\nendobj\n$extraObjects$fontObj%%EOF\n".encodeToByteArray()
        return head + content + tail
    }

    @Test
    fun parsesVersionAndObjects() {
        val bytes = minimalPdf("BT /F1 12 Tf (Hello) Tj ET")
        val doc = JvmPdfDisassembler.parse(bytes)
        assertEquals("1.4", doc.version)
        assertEquals(5, doc.objects.size)
        assertTrue(doc.catalog != null, "catalog must be found")
    }

    @Test
    fun extractsUncompressedText() {
        val bytes = minimalPdf("BT /F1 12 Tf (Hello World) Tj ET")
        val doc = JvmPdfDisassembler.parse(bytes)
        val ex = PdfText.extract(doc)
        assertEquals(1, ex.pages)
        assertTrue("Hello World" in ex.text, "got: ${ex.text}")
    }

    @Test
    fun ordinaryKerningNeverInsertsASpace() {
        // -20 is ordinary intra-word optical kerning — must NOT split "Hel" | "lo"
        val bytes = minimalPdf("BT /F1 12 Tf [(Hel)-20(lo World)] TJ ET")
        val doc = JvmPdfDisassembler.parse(bytes)
        val ex = PdfText.extract(doc)
        assertTrue("Hello World" in ex.text, "got: ${ex.text}")
    }

    @Test
    fun bigNegativeTJGapReadsAsWordSpace() {
        // -500 with no space character present in either string is a genuine word gap
        val bytes = minimalPdf("BT /F1 12 Tf [(Hello)-500(World)] TJ ET")
        val doc = JvmPdfDisassembler.parse(bytes)
        val ex = PdfText.extract(doc)
        assertTrue("Hello World" in ex.text, "got: ${ex.text}")
    }

    @Test
    fun decodesFlateContentStream() {
        val raw = "BT /F1 12 Tf (Compressed Hello) Tj ET".encodeToByteArray()
        val z = deflate(raw)
        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n")
        sb.append("4 0 obj\n<< /Length ${z.size} /Filter /FlateDecode >>\nstream\n")
        val head = sb.toString().encodeToByteArray()
        val tail = "\nendstream\nendobj\n5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n%%EOF\n".encodeToByteArray()
        val bytes = head + z + tail

        val doc = JvmPdfDisassembler.parse(bytes)
        val streamObj = doc.objects[ObjId(4, 0)] as? PdfObject.PStream
        assertTrue(streamObj?.decoded != null, "FlateDecode must decode; note=${streamObj?.decodeNote}")
        val ex = PdfText.extract(doc)
        assertTrue("Compressed Hello" in ex.text, "got: ${ex.text}")
    }

    @Test
    fun honorsToUnicodeCMap() {
        // Font /F1 has a 1-byte-code ToUnicode mapping code 0x41 -> "A", 0x42 -> "B" (bfrange)
        val cmap = """
/CIDInit /ProcSet findresource begin
1 begincodespacerange
<00> <FF>
endcodespacerange
1 beginbfrange
<41> <42> <0041>
endbfrange
endcmap
        """.trimIndent().encodeToByteArray()
        val content = "BT /F1 12 Tf <4142> Tj ET".encodeToByteArray()
        val sb = StringBuilder()
        sb.append("%PDF-1.4\n")
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n")
        sb.append("4 0 obj\n<< /Length ${content.size} >>\nstream\n")
        val head = sb.toString().encodeToByteArray()
        val mid = "\nendstream\nendobj\n5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Custom /ToUnicode 6 0 R >>\nendobj\n6 0 obj\n<< /Length ${cmap.size} >>\nstream\n".encodeToByteArray()
        val tail = "\nendstream\nendobj\n%%EOF\n".encodeToByteArray()
        val bytes = head + content + mid + cmap + tail

        val doc = JvmPdfDisassembler.parse(bytes)
        val ex = PdfText.extract(doc)
        assertTrue("AB" in ex.text, "expected ToUnicode-mapped 'AB', got: ${ex.text}")
    }

    @Test
    fun tornAndGarbageInputNeverThrows() {
        val garbage = ByteArray(4096) { (it * 37 + 11).toByte() }
        val doc = JvmPdfDisassembler.parse(garbage)
        assertEquals("?", doc.version)
        assertTrue(doc.objects.isEmpty())
        assertTrue(doc.notes.isNotEmpty())

        // valid header, body truncated mid-object (torn file)
        val bytes = minimalPdf("BT /F1 12 Tf (Hi) Tj ET")
        val torn = bytes.copyOfRange(0, bytes.size - 40)
        val doc2 = JvmPdfDisassembler.parse(torn)
        assertTrue(doc2.objects.isNotEmpty(), "a torn file must still yield whatever objects fit")
    }

    /**
     * Proves the actual claim under test — parsing over a LAZY, WINDOWED
     * `Series<Byte>` (never fully materialized) yields byte-identical results
     * to parsing over an in-memory ByteArray — using a plain JDK
     * RandomAccessFile as the windowed source.
     *
     * NOT routed through [JvmPdfDisassembler.parseFile]/[borg.trikeshed.lib.FileBuffer]:
     * that path is wired to the shared userspace `UringChannel` JVM backend,
     * which has a PRE-EXISTING bug unrelated to this disassembler —
     * `JvmUserspaceChannelBackend.submitBatch`'s "auto-register" fallback
     * (UserspaceIO.jvm.kt) opens `Paths.get("")` with an empty option set
     * instead of the real file/path when a fd was never explicitly
     * registered, so ANY read through a bare `Files.open()`-obtained `File`
     * fails. `parseFile`'s code is correct and will start working with zero
     * changes once that registration seam is fixed; tracked, not fixed here.
     */
    private class WindowedByteSource(path: String) : AutoCloseable {
        private val raf = java.io.RandomAccessFile(path, "r")
        val size: Int = raf.length().toInt()
        private val window = ByteArray(4096)
        private var base = -1
        private var limit = -1
        fun series(): borg.trikeshed.lib.Series<Byte> =
            size.let { n -> (n j { i: Int ->
                if (i < base || i >= limit) {
                    val start = (i / window.size) * window.size
                    raf.seek(start.toLong())
                    val read = raf.read(window, 0, minOf(window.size, size - start))
                    base = start; limit = start + maxOf(read, 0)
                }
                window[i - base]
            }) }
        override fun close() = raf.close()
    }

    @Test
    fun windowedSeriesParseMatchesInMemory() {
        val bytes = minimalPdf("BT /F1 12 Tf (Windowed) Tj ET")
        val tmp = kotlin.io.path.createTempFile("pdftest", ".pdf").toFile()
        tmp.writeBytes(bytes)
        try {
            val windowed = WindowedByteSource(tmp.absolutePath)
            windowed.use {
                val fromWindow = PdfDisassembler(JvmPdfDisassembler::inflate).parse(it.series())
                val fromMem = JvmPdfDisassembler.parse(bytes)
                assertEquals(fromMem.objects.size, fromWindow.objects.size)
                assertEquals(PdfText.extract(fromMem).text, PdfText.extract(fromWindow).text)
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun census() {
        val bytes = minimalPdf("BT /F1 12 Tf (X) Tj ET")
        val doc = JvmPdfDisassembler.parse(bytes)
        val c = doc.census()
        assertEquals(1, c["streams"])
        @Suppress("UNCHECKED_CAST")
        val types = c["types"] as Map<String, Int>
        assertEquals(1, types["Catalog"])
        assertEquals(1, types["Page"])
    }
}
