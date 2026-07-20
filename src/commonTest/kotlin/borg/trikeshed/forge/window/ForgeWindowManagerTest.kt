package borg.trikeshed.forge.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ForgeWindowManagerTest {

    @Test
    fun bindStoresHtml() {
        val manager = NoopForgeWindowManager()
        val html = "<html><body>hi</body></html>"
        manager.bind(html)
        val snapshot = manager.captureSnapshot()
        assertEquals(html, snapshot.dom)
    }

    @Test
    fun injectScriptAppends() {
        val manager = NoopForgeWindowManager()
        manager.injectScript(ScriptSnippet(id = "s1", source = "console.log(1);"))
        manager.injectScript(ScriptSnippet(id = "s2", source = "console.log(2);"))

        val snapshot = manager.captureSnapshot()
        assertEquals(2, snapshot.boundScripts.size)
        assertEquals("s1", snapshot.boundScripts[0])
        assertEquals("s2", snapshot.boundScripts[1])
    }

    @Test
    fun dispatchEventAppends() {
        val manager = NoopForgeWindowManager()
        val e1 = WindowEvent(type = "t1", payload = "p1", timestampMillis = 1)
        val e2 = WindowEvent(type = "t2", payload = "p2", timestampMillis = 2)
        val e3 = WindowEvent(type = "t3", payload = "p3", timestampMillis = 3)

        manager.dispatchEvent(e1)
        manager.dispatchEvent(e2)
        manager.dispatchEvent(e3)

        val snapshot = manager.captureSnapshot()
        assertEquals(3, snapshot.dispatchedEvents.size)
        assertEquals(e1, snapshot.dispatchedEvents[0])
        assertEquals(e2, snapshot.dispatchedEvents[1])
        assertEquals(e3, snapshot.dispatchedEvents[2])
    }

    @Test
    fun noOpManagerMarksSnapshotAsNoop() {
        val manager = NoopForgeWindowManager()
        val snapshot = manager.captureSnapshot()
        assertTrue(snapshot.isNoop)
    }

    @Test
    fun bindEmptyHtmlIsAllowed() {
        val manager = NoopForgeWindowManager()
        manager.bind("")
        val snapshot = manager.captureSnapshot()
        assertEquals("", snapshot.dom)
    }

    @Test
    fun injectScriptRejectsBlankSource() {
        assertFailsWith<IllegalArgumentException> {
            ScriptSnippet(id = "a", source = "")
        }
        assertFailsWith<IllegalArgumentException> {
            ScriptSnippet(id = "a", source = "   ")
        }
    }

    @Test
    fun injectScriptRejectsBlankId() {
        assertFailsWith<IllegalArgumentException> {
            ScriptSnippet(id = "", source = "a")
        }
        assertFailsWith<IllegalArgumentException> {
            ScriptSnippet(id = "   ", source = "a")
        }
    }

    @Test
    fun injectScriptRejectsOversizedSource() {
        val oversizedSource = "a".repeat(65_537)
        assertFailsWith<IllegalArgumentException> {
            ScriptSnippet(id = "a", source = oversizedSource)
        }
    }

    @Test
    fun injectScriptRunAtDefaultsToDomReady() {
        val snippet = ScriptSnippet(id = "a", source = "x")
        assertEquals(RunAt.DOMReady, snippet.runAt)
    }

    @Test
    fun snapshotDomReflectsLastBind() {
        val manager = NoopForgeWindowManager()
        val html1 = "<html><body>1</body></html>"
        val html2 = "<html><body>2</body></html>"

        manager.bind(html1)
        manager.bind(html2)

        val snapshot = manager.captureSnapshot()
        assertEquals(html2, snapshot.dom)
    }
}
