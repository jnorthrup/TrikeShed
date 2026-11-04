package borg.trikeshed.docs

import borg.trikeshed.lib.*
import kotlin.test.*

class DocumentNavigationTest {
    @Test fun workspaceAndDocumentLinksEncodeIdentifiersAndReservedProject() {
        assertEquals("%63uration", DocumentSurface.projectFragment("curation"))
        assertEquals("dept%2Fresearch", DocumentSurface.projectFragment("dept/research"))
        val doc = DocRow("dir/100%20#résumé.txt", "cid", "rev", 1, 0, "text/plain")
        val page = DocumentSurface.pagesHtml("curation", _l[doc], null)
        assertTrue(page.contains("href=\"#%63uration/dir%2F100%2520%23r%C3%A9sum%C3%A9.txt\""), page)
        assertTrue(page.contains("data-doc=\"dir/100%20#résumé.txt\""), page)
        val side = DocumentSurface.sidebarHtml(_l[ProjectRow("curation", "assets", 1, "")], "curation", _l[doc])
        assertTrue(side.contains("href=\"#%63uration\" data-project=\"curation\""), side)
        assertTrue(side.contains("href=\"#curation\" data-curate=\"1\""), side)
        assertTrue(DocumentSurface.crumbHtml("curation", null, SurfaceView.TABLE).contains("href=\"#%63uration\""))
    }

    @Test fun sourceReferencesCanNameTheCurationWorkspace() {
        val selected = SourceSelection("sha256:" + "a".repeat(64), 0, "curation", "dir/100%20.txt")
        assertEquals(selected, SourceSelection.parse(SourceSelection.queryValues(selected.query())))
        assertTrue(selected.documentsHref().contains("#%63uration/dir%2F100%2520.txt"))
        val result = CurationResult(selected.receiptCid, "name", "text", _l.get(), _l.get(), _l.get(),
            project = selected.project, documentId = selected.document)
        assertTrue(DocumentSurface.curationResultHtml(result).contains("href=\"/documents#%63uration/dir%2F100%2520.txt\""))
    }
}
