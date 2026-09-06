package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A mounted folder as a document set a program can walk — over the in-memory corpus. */
class ProjectNodesTest {

    private fun corpus(): InMemoryProjectCorpus = InMemoryProjectCorpus().apply {
        put("notes", "a.md", "alpha".encodeToByteArray())
        put("notes", "sub/b.md", "beta".encodeToByteArray())
        put("notes", "c.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 1, 2, 3))
        put("notes", "a.md.extract.md", "mined alpha".encodeToByteArray())
        put("other", "readme.md", "other".encodeToByteArray())
    }

    @Test
    fun everyServedTypeHasAContractAndEveryProjectContractHasARunner() {
        val registry = ProjectNodes.registry(corpus())
        val contracts = LcncContracts.all().associateBy { it.type }
        for (type in ProjectNodes.servedTypes()) {
            assertTrue(type in contracts, "served type $type has no contract")
            assertTrue(type in registry, "served type $type has no runner")
        }
        assertTrue("project.list" !in SurfaceNodes.servedTypes(), "the surface family no longer serves project.list")
        assertEquals(ProjectDoc.LIST_KIND, contracts.getValue(ProjectNodes.DOCS).outputKinds["docs"])
        assertEquals(ProjectDoc.SHAPE, contracts.getValue(ProjectNodes.DOCS).kindShapes[ProjectDoc.LIST_KIND])
    }

    @Test
    fun docsFilterByPrefixAndGlobAndReportCount() = runBlocking {
        val docs = ProjectNodes.registry(corpus()).getValue(ProjectNodes.DOCS)
        val md = docs.run(LcncNode("d", ProjectNodes.DOCS, params = mapOf("project" to "notes", "glob" to "*.md")), emptyMap())
        assertEquals(3, md["count"], "a glob without a slash matches the last path segment, anywhere")
        assertEquals(listOf("a.md", "a.md.extract.md", "sub/b.md"), (md["docs"] as List<*>).map { (it as Map<*, *>)["id"] })
        val under = docs.run(LcncNode("d", ProjectNodes.DOCS, params = mapOf("project" to "notes", "prefix" to "sub/")), emptyMap())
        assertEquals(listOf("sub/b.md"), (under["docs"] as List<*>).map { (it as Map<*, *>)["id"] })
        val whole = docs.run(LcncNode("d", ProjectNodes.DOCS, params = mapOf("project" to "notes", "glob" to "sub/*.md")), emptyMap())
        assertEquals(1, whole["count"])
        val wired = docs.run(LcncNode("d", ProjectNodes.DOCS, params = mapOf("project" to "notes")), mapOf("project?" to "other"))
        assertEquals(listOf("readme.md"), (wired["docs"] as List<*>).map { (it as Map<*, *>)["id"] }, "the wire wins over the param")
        val row = (md["docs"] as List<*>)[0] as Map<*, *>
        assertEquals(ContentId.of("alpha".encodeToByteArray()).value, row["cid"])
        assertTrue((row["seq"] as Number).toLong() > 0)
        assertFailsWith<IllegalArgumentException> { docs.run(LcncNode("d", ProjectNodes.DOCS), emptyMap()) }
    }

    @Test
    fun readReturnsTextAndIdentityOrAnErrorForBinaries() = runBlocking {
        val read = ProjectNodes.registry(corpus()).getValue(ProjectNodes.READ)
        val docs = ProjectNodes.registry(corpus()).getValue(ProjectNodes.DOCS)
        val doc = ((docs.run(LcncNode("d", ProjectNodes.DOCS, params = mapOf("project" to "notes", "glob" to "a.md")), emptyMap())["docs"] as List<*>)[0]) as Map<*, *>
        val byWire = read.run(LcncNode("r", ProjectNodes.READ), mapOf("doc?" to doc))
        assertEquals("alpha", byWire["text"]); assertEquals(doc["cid"], byWire["cid"]); assertEquals("a.md", byWire["id"])
        assertEquals("a.md", (byWire["doc"] as Map<*, *>)["id"])
        val byParams = read.run(LcncNode("r", ProjectNodes.READ, params = mapOf("project" to "notes", "id" to "sub/b.md")), emptyMap())
        assertEquals("beta", byParams["text"])
        val binary = read.run(LcncNode("r", ProjectNodes.READ, params = mapOf("project" to "notes", "id" to "c.pdf")), emptyMap())
        assertNull(binary["text"]); assertTrue(binary["error"].toString().contains("binary"))
        val capped = read.run(LcncNode("r", ProjectNodes.READ, params = mapOf("project" to "notes", "id" to "a.md", "maxChars" to "3")), emptyMap())
        assertEquals("alp", capped["text"])
    }

    @Test
    fun extractReadsTheMinedTwinOrSaysNotFound() = runBlocking {
        val extract = ProjectNodes.registry(corpus()).getValue(ProjectNodes.EXTRACT)
        val found = extract.run(LcncNode("e", ProjectNodes.EXTRACT, params = mapOf("project" to "notes", "id" to "a.md")), emptyMap())
        assertEquals(true, found["found"]); assertEquals("mined alpha", found["text"])
        val missing = extract.run(LcncNode("e", ProjectNodes.EXTRACT, params = mapOf("project" to "notes", "id" to "sub/b.md")), emptyMap())
        assertEquals(false, missing["found"]); assertNull(missing["text"])
    }

    @Test
    fun listReportsTheProjectsAndTheirDocumentCounts() = runBlocking {
        val list = ProjectNodes.registry(corpus()).getValue(ProjectNodes.LIST).run(LcncNode("l", ProjectNodes.LIST), emptyMap())
        val refs = list["projects"] as List<*>
        assertEquals(listOf("notes", "other"), refs.map { (it as Map<*, *>)["name"] })
        assertEquals(4, (refs[0] as Map<*, *>)["docs"])
        assertEquals(refs, list["scopes"], "scopes keeps the shipped shape")
    }

    @Test
    fun globSemantics() {
        assertTrue(ProjectGlob.matches("", "anything/at/all"))
        assertTrue(ProjectGlob.matches("*.md", "deep/er/x.md"))
        assertTrue(!ProjectGlob.matches("*.md", "x.txt"))
        assertTrue(ProjectGlob.matches("docs/**/*.md", "docs/a/b/c.md"))
        assertTrue(!ProjectGlob.matches("docs/*.md", "docs/a/b.md"))
        assertTrue(ProjectGlob.matches("?.md", "a.md") && !ProjectGlob.matches("?.md", "ab.md"))
        assertTrue(ProjectGlob.isTextual("application/octet-stream", "notes.md") && !ProjectGlob.isTextual("application/pdf", "x.pdf"))
    }
}
