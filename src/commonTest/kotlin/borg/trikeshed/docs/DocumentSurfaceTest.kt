package borg.trikeshed.docs

import borg.trikeshed.parse.json.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The page model: the routes' JSON in, escaped HTML with selection state out. */
class DocumentSurfaceTest {

    private val projectsJson = """{"scopes":[{"name":"genesis-notes","path":"/x/files/genesis-notes","kind":"assets","prefix":"genesis-notes/","paths":3,"minted":2,"docs":3},{"name":"trikeshed","kind":"git","docs":0}]}"""
    private val docsJson = """{"project":"genesis-notes","docs":[{"project":"genesis-notes","id":"a.md","cid":"sha256:59204cb01ed1947253923d","rev":"1-sha256:8fc0","seq":0,"length":73,"contentType":"text/markdown"},{"id":"c.txt","cid":"sha256:abc","rev":"1-x","seq":2,"length":13,"contentType":"text/plain"}],"count":2}"""

    @Test
    fun parsesTheRoutesAndRendersSelection() {
        val projects = DocumentSurface.projects(JsonSupport.parse(projectsJson))
        assertEquals(listOf("genesis-notes", "trikeshed"), projects.map { it.name })
        assertEquals(3, projects[0].docs)
        val html = DocumentSurface.projectsHtml(projects, "genesis-notes")
        assertTrue(html.contains("data-project=\"genesis-notes\" class=\"ds-selected\" aria-current=\"true\""), html)
        assertTrue(html.contains("data-project=\"trikeshed\">"), html)

        val docs = DocumentSurface.docs(JsonSupport.parse(docsJson))
        assertEquals(listOf("a.md", "c.txt"), docs.map { it.id })
        assertEquals(0L, docs[0].seq)
        val list = DocumentSurface.docsHtml("genesis-notes", docs, "c.txt")
        assertTrue(list.contains("data-doc=\"c.txt\" class=\"ds-selected\""), list)
        assertTrue(list.contains("title=\"sha256:59204cb01ed1947253923d\""), list)
        assertTrue(list.contains(DocumentSurface.DIGEST_HREF), list)
    }

    @Test
    fun rendersMarkdownDocumentsAndEscapesTheRest() {
        val md = DocumentSurface.document(JsonSupport.parse("""{"project":"genesis-notes","id":"a.md","cid":"sha256:59204cb01ed1947253923d6a198d","rev":"1-x","seq":0,"contentType":"text/markdown","text":"# Alpha\n\nfirst"}"""))!!
        val html = DocumentSurface.documentHtml(md)
        assertTrue(html.contains("<h1>a.md</h1>"), html)
        assertTrue(html.contains("<code title=\"sha256:59204cb01ed1947253923d6a198d\">sha256:59204cb01ed1</code>"), html)
        assertTrue(html.contains("<article class=\"ds-body\"><h1>Alpha</h1>\n<p>first</p>\n</article>"), html)

        val txt = DocumentSurface.document(JsonSupport.parse("""{"project":"p","id":"c.txt","cid":"sha256:abc","rev":"1-x","seq":2,"contentType":"text/plain","text":"<b>not markup</b>","extract":"## mined\n\ntwin"}"""))!!
        val plain = DocumentSurface.documentHtml(txt)
        assertTrue(plain.contains("<pre>&lt;b&gt;not markup&lt;/b&gt;</pre>"), plain)
        assertTrue(plain.contains("<section class=\"ds-extract\"><h2>Extract (the miner's twin)</h2><h2>mined</h2>\n<p>twin</p>\n</section>"), plain)
    }

    @Test
    fun absentOrMalformedJsonIsEmptyNotAnException() {
        assertEquals(emptyList(), DocumentSurface.projects(JsonSupport.parse("""{"error":"x"}""")))
        assertEquals(emptyList(), DocumentSurface.docs(null))
        assertNull(DocumentSurface.document(JsonSupport.parse("""{"error":"absent"}""")))
        assertTrue(DocumentSurface.documentHtml(null).contains("Pick a document"))
        assertTrue(DocumentSurface.docsHtml(null, emptyList(), null).contains("Pick a project"))
    }
}
