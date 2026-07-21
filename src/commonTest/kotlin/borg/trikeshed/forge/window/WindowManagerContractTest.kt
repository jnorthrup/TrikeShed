package borg.trikeshed.forge.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

abstract class WindowManagerContractTest {
    abstract fun getManager(): ForgeWindowManager

    @Test
    fun `test launch sets dom`() {
        val manager = getManager()
        val html = "<html><body><h1>Hello</h1></body></html>"
        manager.launch(html)
        val snapshot = manager.captureSnapshot()
        assertEquals(html, snapshot.dom)
    }

    @Test
    fun `test bind sets dom`() {
        val manager = getManager()
        val html = "<html><body><h1>Hello bound</h1></body></html>"
        manager.bind(html)
        val snapshot = manager.captureSnapshot()
        assertEquals(html, snapshot.dom)
    }

    @Test
    fun `test injectScript updates boundScripts`() {
        val manager = getManager()
        val script = ScriptSnippet(id = "test-script", source = "console.log('test');")
        manager.injectScript(script)
        val snapshot = manager.captureSnapshot()
        assertTrue(snapshot.boundScripts.contains(script.source))
    }

    @Test
    fun `test dispatchEvent updates dispatchedEvents`() {
        val manager = getManager()
        val event = WindowEvent("TestEvent", "TestPayload", 0L)
        manager.dispatchEvent(event)
        val snapshot = manager.captureSnapshot()
        assertTrue(snapshot.dispatchedEvents.contains(event))
    }
}
