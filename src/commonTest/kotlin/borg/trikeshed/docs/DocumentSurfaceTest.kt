package borg.trikeshed.docs

import borg.trikeshed.parse.json.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The page model: the routes' JSON in, escaped HTML with selection state out. */
class DocumentSurfaceTest {

    private val projectsJson = """{"scopes":[{"name":"genesis-notes","path":"/x/files/genesis-notes","kind":"assets","prefix":"genesis-notes/","paths":3,"minted":2,"docs":3},{"name":"trikeshed","kind":"git","docs":0}]}"""
    private val docsJson = """{"project":"genesis-notes","docs":[{"project":"genesis-notes","id":"a.md","cid":"sha256:59204cb01ed1947253923d","rev":"1-sha256:8fc0","seq":0,"length":73,"contentType":"text/markdown"},{"id":"c.txt","cid":"sha256:abc","rev":"1-x","seq":2,"length":13,"contentType":"text/plain"}],"count":2}"""

    @Test
    fun theSidebarNestsAWorkspacesPagesUnderTheOpenOne() {
        val projects = DocumentSurface.projects(JsonSupport.parse(projectsJson))
        assertEquals(listOf("genesis-notes", "trikeshed"), projects.map { it.name })
        assertEquals(3, projects[0].docs)
        val docs = DocumentSurface.docs(JsonSupport.parse(docsJson))
        assertEquals(listOf("a.md", "c.txt"), docs.map { it.id })
        assertEquals(0L, docs[0].seq)

        val side = DocumentSurface.sidebarHtml(projects, "genesis-notes", docs, "c.txt")
        assertTrue(side.contains("ds-proj ds-selected\" aria-current=\"true\" href=\"#genesis-notes\""), side)
        assertTrue(side.contains("▾"), side)                              // the open workspace
        assertTrue(side.contains("data-project=\"trikeshed\""), side)
        assertTrue(side.contains("data-doc=\"c.txt\""), side)
        assertTrue(side.contains("title=\"sha256:59204cb01ed1947253923d\""), side)
        // only the open workspace carries pages, which is what keeps a long sidebar readable
        val closed = DocumentSurface.sidebarHtml(projects, "trikeshed", emptyList(), null)
        assertFalse(closed.contains("data-doc="), closed)
        assertTrue(closed.contains("▸"), closed)              // genesis-notes, now shut
    }

    @Test
    fun theBreadcrumbAndViewSwitchSayWhereTheReaderIs() {
        val page = DocumentSurface.crumbHtml("genesis-notes", "a.md", SurfaceView.PAGE)
        assertTrue(page.contains("<span class=\"ds-here\">a.md</span>"), page)
        assertTrue(page.contains("class=\"ds-view ds-on\" aria-pressed=\"true\" data-view=\"page\""), page)
        // the table is the workspace, so the page it came from is not part of the trail
        val table = DocumentSurface.crumbHtml("genesis-notes", "a.md", SurfaceView.TABLE)
        assertFalse(table.contains("ds-here"), table)
        assertTrue(table.contains("data-view=\"table\""), table)
        assertTrue(DocumentSurface.crumbHtml(null, null, SurfaceView.PAGE).contains("Pick a workspace"))
    }

    @Test
    fun theTableSortsTheWorkspaceWithoutEditingIt() {
        val docs = DocumentSurface.docs(JsonSupport.parse(docsJson))
        assertEquals(listOf("a.md", "c.txt"), DocumentSurface.sortDocs(docs, DocColumn.NAME, true).map { it.id })
        assertEquals(listOf("c.txt", "a.md"), DocumentSurface.sortDocs(docs, DocColumn.NAME, false).map { it.id })
        // 13 B before 73 B: size sorts as a number, never as its rendered string
        assertEquals(listOf("c.txt", "a.md"), DocumentSurface.sortDocs(docs, DocColumn.SIZE, true).map { it.id })
        assertEquals(listOf("a.md", "c.txt"), DocumentSurface.sortDocs(docs, DocColumn.SEQ, true).map { it.id })
        assertEquals(docs, DocumentSurface.docs(JsonSupport.parse(docsJson)))   // sorting edits nothing
        assertEquals(DocColumn.SEQ, DocColumn.of("seq"))
        assertEquals(DocColumn.NAME, DocColumn.of("nonsense"))

        val html = DocumentSurface.tableHtml("genesis-notes", docs, DocColumn.SIZE, false, "a.md")
        assertTrue(html.contains("<h1 class=\"ds-title\">genesis-notes</h1>"), html)
        assertTrue(html.contains("2 pages"), html)
        assertTrue(html.contains("class=\"ds-th ds-desc\" data-sort=\"size\">Size<span class=\"ds-arrow\">↓</span>"), html)
        assertTrue(html.contains("<tr class=\"ds-row ds-selected\" data-doc=\"a.md\">"), html)
        assertTrue(html.indexOf("data-doc=\"a.md\"") < html.indexOf("data-doc=\"c.txt\""), "descending size puts 73 B first: " + html)
        assertTrue(html.contains("73 B"), html)
        assertTrue(DocumentSurface.tableHtml(null, docs).contains("Pick a workspace"))
        assertEquals("1.5 KB", DocumentSurface.bytes(1536))
        assertEquals("512 B", DocumentSurface.bytes(512))
        // a revision keeps its generation and sheds the rest of the hash
        assertEquals("1-sha256:c8071d21bfd5", DocumentSurface.shortRev("1-sha256:c8071d21bfd547bb60db0aced40a34f4"))
        assertEquals("sha256:0a55c66fb592", DocumentSurface.shortRev("sha256:0a55c66fb592abc"))
    }

    @Test
    fun rendersMarkdownDocumentsAndEscapesTheRest() {
        val md = DocumentSurface.document(JsonSupport.parse("""{"project":"genesis-notes","id":"a.md","cid":"sha256:59204cb01ed1947253923d6a198d","rev":"1-x","seq":0,"contentType":"text/markdown","text":"# Alpha\n\nfirst"}"""))!!
        val html = DocumentSurface.documentHtml(md)
        assertTrue(html.contains("<h1 class=\"ds-title\">a.md</h1>"), html)
        assertTrue(html.contains("title=\"sha256:59204cb01ed1947253923d6a198d\">sha256:59204cb01ed1</code>"), html)
        assertTrue(html.contains("<article class=\"ds-body\"><h1>Alpha</h1>\n<p>first</p>\n</article>"), html)

        val txt = DocumentSurface.document(JsonSupport.parse("""{"project":"p","id":"c.txt","cid":"sha256:abc","rev":"1-x","seq":2,"contentType":"text/plain","text":"<b>not markup</b>","extract":"## mined\n\ntwin"}"""))!!
        val plain = DocumentSurface.documentHtml(txt)
        assertTrue(plain.contains("<pre>&lt;b&gt;not markup&lt;/b&gt;</pre>"), plain)
        assertTrue(plain.contains("<section class=\"ds-extract\"><h2>Extract (the miner's twin)</h2><h2>mined</h2>\n<p>twin</p>\n</section>"), plain)
    }

    /**
     * A page carrying build targets (AutoTools, Cut B): each `lcnc-run` fence renders its frame in
     * the flow of the document, in fence order, and a block that is not a request renders its own
     * bytes back with no button.
     */
    @Test
    fun runBlocksRenderAsFramesInTheFlowOfThePage() {
        val text = "# Digest\n\n```lcnc-run\n{\"program\":\"corpus\",\"inputs\":{\"project\":\"genesis-notes\"},\"show\":\"n-show\"}\n```\n\n" +
            "and then\n\n```lcnc-run\n{\"nope\":true}\n```\n"
        val view = DocView("genesis-notes", "digest.md", "sha256:abc", "1-x", 4, "text/markdown", text)
        val cid = "sha256:" + "a".repeat(64)
        val states = mapOf(0 to RunBlock.State(
            lamp = borg.trikeshed.lcnc.LcncRunHead.Lamp.STALE, word = "Stale",
            reason = "Completed against inputs that have since changed: a.md",
            moved = listOf("a.md"), runId = "r1", receiptCid = cid, show = "n-show", shown = mapOf("x" to "alpha"),
        ))
        val html = DocumentSurface.documentHtml(view, states)
        assertTrue(html.contains("<h1>Digest</h1>"), html)
        assertTrue(html.contains(">Stale</span>"), html)
        assertTrue(html.contains("title=\"$cid\">" + DocumentSurface.shortCid(cid) + "</code>"), html)
        assertTrue(html.contains("data-run-rebuild=\"0\">Rebuild</button>"), html)
        assertTrue(html.contains("a.md"), html)
        // The second block is not a request: escaped bytes, a reason, and nothing to press.
        assertTrue(html.contains("ds-run-refused"), html)
        assertTrue(html.contains("&quot;nope&quot;"), html)
        assertEquals(1, Regex("<button").findAll(html).count(), html)
        // With no states at all the frames still render, unread, and the page never invents a lamp.
        val unread = DocumentSurface.documentHtml(view)
        assertTrue(unread.contains("ds-run-lamp-unread"), unread)
        assertFalse(unread.contains("Stale"), unread)
        // A run block on a non-markdown document is text, never a target.
        val txt = DocumentSurface.documentHtml(view.copy(id = "digest.txt", contentType = "text/plain"))
        assertFalse(txt.contains("ds-run"), txt)
    }

    @Test
    fun absentOrMalformedJsonIsEmptyNotAnException() {
        assertEquals(emptyList(), DocumentSurface.projects(JsonSupport.parse("""{"error":"x"}""")))
        assertEquals(emptyList(), DocumentSurface.docs(null))
        assertNull(DocumentSurface.document(JsonSupport.parse("""{"error":"absent"}""")))
        assertTrue(DocumentSurface.documentHtml(null).contains("Pick a page"))
        assertTrue(DocumentSurface.tableHtml(null, emptyList()).contains("Pick a workspace"))
        assertTrue(DocumentSurface.sidebarHtml(emptyList(), null).contains("No project is mounted"))
    }
}
