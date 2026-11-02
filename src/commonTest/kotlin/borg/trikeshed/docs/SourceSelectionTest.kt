package borg.trikeshed.docs

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.wiki.*
import kotlin.test.*

class SourceSelectionTest {
    private val receipt = "sha256:" + "b".repeat(64)
    private val selected = SourceSelection(receipt, 2, "Dept + legal", "notes/a & duty.md")

    @Test fun boundedNavigationPreservesExactIdentitiesAndRejectsAmbiguity() {
        assertEquals(selected, SourceSelection.parse(SourceSelection.queryValues(selected.query())))
        assertTrue(selected.documentsHref().contains("sourceReceipt="))
        assertTrue(selected.documentsHref().contains("passageReceipt="))
        assertEquals(selected.documentsHref(), selected.applicationHref("documents"))
        assertEquals(selected.href("panels"), selected.applicationHref("panels"))
        assertEquals(selected.href("panels"), selected.applicationHref("board"))
        assertNull(selected.applicationHref("pi"))
        assertNull(selected.applicationHref("hermes"))
        assertTrue(selected.documentsHref().contains("#Dept%20%2B%20legal/notes%2Fa%20%26%20duty.md"))
        for (bad in s_[selected.query() + "&sourceProposal=3", selected.query().replace("sourceProposal=2", "sourceProposal=02"),
            selected.query().replace("sourceProject=Dept%20%2B%20legal", "sourceProject=%C3%28"),
            selected.query().replace("sourceProject=Dept%20%2B%20legal", "sourceProject=" + "x".repeat(1025)),
            selected.query().replace("sourceReceipt=sha256", "sourceReceipt=SHA256")].view)
            assertFails { SourceSelection.parse(SourceSelection.queryValues(bad)) }
        assertFails { SourceSelection.parse(SourceSelection.queryValues("sourceReceipt=$receipt")) }
        assertFails { SourceSelection.queryValues("sourceProject=%GG") }
        assertFails { SourceSelection.queryValues("sourceProject=%F0%9F") }
    }

    @Test fun wikiKeepsPageIdentitySeparateAndCarriesContextAcrossWikiLinks() {
        val article = WikiArticle("authored.md", "# Authored\n[[second.md]]", "wiki-cid")
        val articles = s_[article, WikiArticle("second.md", "# Second", "another-wiki-cid")]
        val html = WikiSurface.render(articles, article.path, sourceContext = selected)
        assertTrue(html.contains("The wiki page has its own identity"))
        assertTrue(html.contains("data-source-context=\"1\""))
        assertTrue(html.contains("/wiki?page=second.md&amp;sourceReceipt="))
        assertTrue(html.contains("/panels?sourceReceipt="))
        assertTrue(html.contains("Return to source passage"))
        assertTrue(html.contains("authored.md"))
        assertFalse(html.contains("Matched to the saved text"))
        val invalid = WikiSurface.render(articles, sourceContextUnavailable = true)
        assertTrue(invalid.contains("invalid navigation reference"))
        assertFalse(invalid.contains("data-source-context=\"1\""))
    }

    @Test fun actualPassageProjectionKeepsReturnReferenceWhenSourceBecomesStale() {
        val text = "A 😀 duty."
        val cid = ContentId.of(text.encodeToByteArray()).value
        val revision = DocRevision(selected.project, selected.document, cid, "1-source", 4)
        val passage = DocumentPassage(receipt, 2, revision, revision, 2, 4, "😀", 0, text.length, text)
        val view = DocView(revision.project, revision.id, cid, revision.rev, revision.seq, "text/plain", text,
            sourceRevision = revision, displayTextRevision = revision)
        assertTrue(selected.matches(passage))
        assertFalse(selected.copy(document = "other.md").matches(passage))
        val matched = DocumentSurface.passageHtml(view, passage)
        assertTrue(matched.contains("<mark>😀</mark>"))
        assertTrue(matched.contains("/panels?sourceReceipt="))
        val stale = DocumentSurface.passageHtml(view.copy(sourceRevision = revision.copy(seq = 5)), passage)
        assertTrue(stale.contains("Passage unavailable"))
        assertTrue(stale.contains("Return to source passage"))
        assertFalse(stale.contains("<mark>"))
    }
}
