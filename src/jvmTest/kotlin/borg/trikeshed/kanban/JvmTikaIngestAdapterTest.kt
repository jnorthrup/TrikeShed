package borg.trikeshed.kanban

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmTikaIngestAdapterTest {

    @Test
    fun markdownPassesThroughVerbatim() {
        val tmp = Files.createTempFile("tika-test", ".md")
        Files.writeString(tmp, "# Hello\n\nworld\n")
        val out = JvmTikaIngestAdapter.extract(tmp)
        assertTrue(out.contains("# Hello"))
        assertTrue(out.contains("world"))
    }

    @Test
    fun extensionGateKeepsTextFormatsLocal() {
        assertFalse(JvmTikaIngestAdapter.isTikaCandidate(Path.of("doc.md")))
        assertFalse(JvmTikaIngestAdapter.isTikaCandidate(Path.of("notes.txt")))
        assertTrue(JvmTikaIngestAdapter.isTikaCandidate(Path.of("scan.pdf")))
        assertTrue(JvmTikaIngestAdapter.isTikaCandidate(Path.of("letter.docx")))
        assertTrue(JvmTikaIngestAdapter.isTikaCandidate(Path.of("photo.png")))
    }

    @Test
    fun docxExtractsViaTika() {
        // Minimal DOCX = zip with word/document.xml. Build one in-memory.
        val tmp = Files.createTempFile("tika-test", ".docx")
        java.util.zip.ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
            fun entry(name: String, content: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            entry("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>tika extraction smoke token zqxw</w:t></w:r></w:p></w:body></w:document>""")
        }
        val out = JvmTikaIngestAdapter.extract(tmp)
        assertTrue(out.contains("zqxw"), "expected DOCX body text via Tika, got: ${out.take(200)}")
    }
}
