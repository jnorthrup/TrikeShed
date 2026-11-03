package borg.trikeshed.forge.server

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.test.*

class PdfLinksTest {
    /** Actual two-page PDF with five URI occurrences and one separate GoTo action. */
    internal fun fixture(): ByteArray {
        val objects = s_[
            "<< /Type /Catalog /Pages 2 0 R /Dests << /chapterTwo [4 0 R /Fit] >> >>",
            "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 10 0 R /Annots [5 0 R 6 0 R 7 0 R 8 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 11 0 R /Annots [9 0 R 12 0 R] >>",
            "<< /Type /Annot /Subtype /Link /Rect [10 10 100 30] /A << /S /URI /URI (https://example.org/legal/article?section=1) >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [10 40 100 60] /A << /S /URI /URI (https://example.org/legal/article?section=1) >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [10 70 100 90] /A << /S /URI /URI (javascript:alert\\(1\\)) >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [10 100 100 120] /A << /S /GoTo /D (chapterTwo) >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [10 10 100 30] /A << /S /URI /URI (https://example.net/notice) >> >>",
            "<< /Length 4 >>\nstream\nq\nQ\nendstream",
            "<< /Length 4 >>\nstream\nq\nQ\nendstream",
            "<< /Type /Annot /Subtype /Link /Rect [10 40 100 60] /A << /S /URI /URI (#chapterTwo) >> >>",
        ]
        val out = ByteArrayOutputStream()
        fun write(text: String) { out.write(text.encodeToByteArray()) }
        write("%PDF-1.7\n")
        val offsets = mutableListOf(0)
        for (index in 0 until objects.size) {
            offsets.add(out.size())
            write("${index + 1} 0 obj\n${objects[index]}\nendobj\n")
        }
        val xref = out.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets.drop(1)) write("%010d 00000 n \n".format(offset))
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    @Test fun actualPopplerPreservesSourceOccurrencesAndRefusesUnsafeTargets(): Unit = runBlocking(JvmFileOperations()) {
        val bytes = fixture()
        val result = PdfLinks().discover(bytes, ContentId.of(bytes))
        assertEquals(PdfLinkStatus.AVAILABLE, result.status, result.diagnostic)
        assertEquals(ContentId.of(bytes), result.sourceCid)
        assertEquals(2, result.pages)
        assertFalse(result.toolVersion.isNullOrBlank())
        assertEquals(5, result.links.size)
        assertEquals(result.links[0].uri, result.links[1].uri)
        assertEquals(0, result.links[0].index)
        assertEquals(1, result.links[1].index)
        assertEquals(1, result.links[0].page)
        assertEquals(2, result.links[3].page)
        assertEquals("Annotation", result.links[0].type)
        assertTrue(result.links[0].followable)
        assertEquals("javascript:alert(1)", result.links[2].uri)
        assertFalse(result.links[2].followable)
        assertEquals("#chapterTwo", result.links[4].uri)
        assertFalse(result.links[4].followable)
        assertTrue(result.coverage.contains("excludes GoTo"))
    }

    @Test fun failuresAreUnavailableAndIdentityMismatchIsRefused(): Unit = runBlocking(JvmFileOperations()) {
        val bytes = fixture()
        assertFailsWith<IllegalArgumentException> { PdfLinks().discover(bytes, ContentId.of(_a[1.toByte()])) }
        assertEquals(PdfLinkStatus.UNAVAILABLE, PdfLinks("/nonexistent/trikeshed-pdfinfo").discover(bytes).status)
        val invalid = PdfLinks().discover("not a PDF".encodeToByteArray())
        assertEquals(PdfLinkStatus.UNAVAILABLE, invalid.status)
        assertEquals(0, invalid.links.size)
    }

    @Test fun invalidOrUnlocatedOutputCannotBecomeEmptySuccess() {
        assertFails { PdfLinks.parsePages("") }
        assertFails { PdfLinks.parsePages("Pages: 10001\n") }
        assertFails { PdfLinks.parseUrls("", 2) }
        assertFails { PdfLinks.parseUrls("Page Type URL\n3 Annotation https://example.org", 2) }
        assertFails { PdfLinks.parseUrls("Page Type URL\nunlocated https://example.org", 2) }
        assertEquals(0, PdfLinks.parseUrls("Page Type URL\n", 2).size)
        assertEquals(5425, PdfLinks.parsePages("Pages: 5425\n"))
        val rows = "Page Type URL\n" + "1 Annotation https://example.org\n".repeat(PdfLinks.LINK_LIMIT + 1)
        assertFails { PdfLinks.parseUrls(rows, 2) }
        val credentials = PdfLinks.parseUrls("Page Type URL\n1 Annotation https://user:secret@example.org\n", 1)
        assertFalse(credentials[0].followable)
    }
}
