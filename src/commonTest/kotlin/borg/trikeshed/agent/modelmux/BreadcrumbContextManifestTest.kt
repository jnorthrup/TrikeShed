package borg.trikeshed.agent.modelmux

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BreadcrumbContextManifestTest {
    private fun names(m: BreadcrumbContextManifest) = (0 until m.documents.size).map { m.documents[it].name }
    private fun parentsOf(m: BreadcrumbContextManifest) = (0 until m.parents.size).map { m.parents[it] }

    private fun doc(name: String, text: String) = ManifestDocument.of(name, bytesOf(text))

    private fun sample(): BreadcrumbContextManifest {
        val parent = ContentId.of(bytesOf("earlier revision"))
        return BreadcrumbContextManifest.of(
            "proj/alpha", "policy-1", "child-a", listOf(parent),
            listOf(doc("src/B.kt", "class B"), doc("README.md", "# hi\n"), doc("src/A.kt", "class A")),
        )
    }

    private fun rejects(bytes: ByteArray) {
        assertFailsWith<ManifestFormatException> { BreadcrumbContextManifest.decode(bytes) }
    }

    private fun rejects(text: String) = rejects(bytesOf(text))

    @Test
    fun canonicalSnapshotPinsExactSources() {
        val a = doc("src/A.kt", "class A")
        val b = doc("src/B.kt", "class B")
        val readme = doc("README.md", "# hi\n")
        val p1 = ContentId.of(bytesOf("p1"))
        val p2 = ContentId.of(bytesOf("p2"))

        val one = BreadcrumbContextManifest.of("s", "p", "me", listOf(p2, p1), listOf(b, readme, a))
        val two = BreadcrumbContextManifest.of("s", "p", "me", listOf(p1, p2, p1), listOf(a, readme, b))

        assertContentEquals(one.encode(), two.encode(), "input order and duplicate parents do not change the revision")
        assertEquals(one.cid, two.cid)
        assertEquals(listOf("README.md", "src/A.kt", "src/B.kt"), names(one))
        assertEquals(listOf(p1, p2).sortedBy { it.value }, parentsOf(one))

        val decoded = BreadcrumbContextManifest.decode(one.encode())
        assertContentEquals(one.encode(), decoded.encode())
        assertEquals(one.cid, decoded.cid)
        assertEquals("s", decoded.scope)
        assertEquals("p", decoded.policyRevision)
        assertEquals("me", decoded.author)
        assertEquals(a, decoded.documents[1])
    }

    @Test
    fun formattingChangesDocumentNotNormalizedSpine() {
        val tidy = doc("src/F.kt", "fun f() {\n    return 1\n}\n")
        val padded = doc("src/F.kt", "fun f() {\n\n  return 1\n}\n\n\n")

        assertNotEquals(tidy.content, padded.content, "execution and citation need the exact bytes, which differ")
        assertEquals(tidy.spine, padded.spine, "discovery keys on the normalized spine, which formatting does not move")

        val m1 = BreadcrumbContextManifest.of("s", "p", "me", emptyList(), listOf(tidy))
        val m2 = BreadcrumbContextManifest.of("s", "p", "me", emptyList(), listOf(padded))
        assertNotEquals(m1.cid, m2.cid, "a formatting-only change is still a different revision of the evidence")
    }

    @Test
    fun anEmptyRevisionIsValid() {
        val empty = BreadcrumbContextManifest.of("s", "p", "me", emptyList(), emptyList())
        assertEquals(0, empty.documents.size)
        assertContentEquals(empty.encode(), BreadcrumbContextManifest.decode(empty.encode()).encode())
    }

    @Test
    fun decodeRejectsAnythingThatIsNotCanonical() {
        val good = sample().encode().decodeToString()
        val lines = good.trimEnd('\n').split('\n')
        fun withLines(transform: (List<String>) -> List<String>) = transform(lines).joinToString("\n", postfix = "\n")

        assertContentEquals(good.encodeToByteArray(), BreadcrumbContextManifest.decode(good.encodeToByteArray()).encode())

        rejects(good.replace("manifest 1", "manifest 2"))                       // unknown version is never guessed at
        rejects(good.replace("\n", "\r\n"))                                      // CRLF
        rejects(good.trimEnd('\n'))                                              // no final newline
        rejects(good + "\n")                                                     // trailing blank line
        rejects(good + "note extra\n")                                           // unknown line
        rejects("")                                                              // empty
        rejects(withLines { it.drop(1) })                                        // no header
        rejects(withLines { it.filterNot { l -> l.startsWith("author ") } })     // missing field
        rejects(withLines { l -> listOf(l[0], l[2], l[1]) + l.drop(3) })         // fields out of order
        val docAt = lines.indexOfFirst { it.startsWith("doc ") }
        rejects(withLines { l -> l.toMutableList().also { it[docAt] = l[docAt + 1]; it[docAt + 1] = l[docAt] } })   // docs unsorted
        rejects(withLines { l -> l + l.last() })                                 // duplicate document line
        val parentLine = lines.first { it.startsWith("parent ") }
        rejects(withLines { l -> l.toMutableList().also { it.add(l.indexOf(parentLine), parentLine) } })           // duplicate parent
        rejects(good.replace(Regex("(parent sha256:)([0-9a-f])"), "$1" + "G"))   // not hex
        rejects(good.replace(Regex("(doc sha256:[0-9a-f]{64} sha256:)([0-9a-f]{3})"), "$1" + "ABC"))   // uppercase hex
        rejects(withLines { l -> l.map { if (it.startsWith("scope ")) "scope  padded" else it } })      // padded value
    }

    @Test
    fun decodeRejectsBadBytesAndOversize() {
        rejects(sample().encode() + byteArrayOf(0xFF.toByte()))                  // invalid UTF-8 tail
        val huge = ByteArray(BreadcrumbContextManifest.MAX_BYTES + 1) { 'a'.code.toByte() }
        rejects(huge)
    }

    @Test
    fun namesCannotEscapeTheirDirectory() {
        val content = ContentId.of(bytesOf("x"))
        for (bad in listOf("/abs", "a//b", "a/../b", "..", ".", "a/./b", "a\\b", "trailing/", " lead", "trail ", "tab\tname", "line\nbreak", "")) {
            assertFailsWith<ManifestFormatException>("name '${bad.replace("\n", "\\n")}'") { ManifestDocument(bad, content, content) }
        }
        ManifestDocument("dir/with space/file.txt", content, content)             // ordinary labels are fine
    }

    @Test
    fun fieldsAreSingleLineAndBounded() {
        val ok = emptyList<ManifestDocument>()
        for (bad in listOf("", " s", "s ", "two\nlines", "x".repeat(BreadcrumbContextManifest.MAX_FIELD + 1))) {
            assertFailsWith<ManifestFormatException> { BreadcrumbContextManifest.of(bad, "p", "me", emptyList(), ok) }
            assertFailsWith<ManifestFormatException> { BreadcrumbContextManifest.of("s", bad, "me", emptyList(), ok) }
            assertFailsWith<ManifestFormatException> { BreadcrumbContextManifest.of("s", "p", bad, emptyList(), ok) }
        }
    }

    @Test
    fun duplicateNamesAreRejected() {
        assertFailsWith<ManifestFormatException> {
            BreadcrumbContextManifest.of("s", "p", "me", emptyList(), listOf(doc("same", "one"), doc("same", "two")))
        }
    }

    @Test
    fun sizeLimitsAreEnforcedWhenBuilding() {
        val many = (0..BreadcrumbContextManifest.MAX_DOCUMENTS).map { ManifestDocument("d$it", ContentId.of(bytesOf("$it")), ContentId.of(bytesOf("s$it"))) }
        assertFailsWith<ManifestFormatException> { BreadcrumbContextManifest.of("s", "p", "me", emptyList(), many) }
        val parents = (0..BreadcrumbContextManifest.MAX_PARENTS).map { ContentId.of(bytesOf("parent $it")) }
        assertFailsWith<ManifestFormatException> { BreadcrumbContextManifest.of("s", "p", "me", parents, emptyList()) }
    }

    @Test
    fun aDocumentThatIsNotExactUtf8TextCannotBePinned() {
        val notText = listOf(
            byteArrayOf(0xC3.toByte()),                                  // a truncated two-byte sequence
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()),                   // an overlong encoding
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),    // a surrogate encoded in UTF-8
            byteArrayOf(0xFF.toByte(), 0x41),
        )
        for (bytes in notText) assertFailsWith<ManifestFormatException>(bytes.joinToString()) { ManifestDocument.of("x.bin", bytes) }
        val text = listOf("", "plain\n", "caf\u00e9 \u2603 \ud83d\ude00\n", "\ufeffwith a byte order mark\n", "crlf\r\nnul\u0000inside\n")
        for (t in text) {
            assertTrue(ManifestDocument.isExactText(t.encodeToByteArray()), "valid text of any shape is accepted: $t")
            ManifestDocument.of("ok.txt", t.encodeToByteArray())
        }
    }
}
