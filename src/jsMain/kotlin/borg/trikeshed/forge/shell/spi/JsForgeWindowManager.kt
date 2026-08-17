package borg.trikeshed.forge.shell.spi

import borg.trikeshed.forge.window.ForgeWindowManager
import borg.trikeshed.forge.window.ScriptSnippet
import borg.trikeshed.forge.window.WindowEvent
import borg.trikeshed.forge.window.WindowSnapshot
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CustomEvent
import org.w3c.dom.CustomEventInit
import org.w3c.dom.HTMLScriptElement

class JsForgeWindowManager : ForgeWindowManager {
    override fun launch(html: String) {
        document.body?.innerHTML = html
    }

    override fun bind(html: String) {
        document.body?.innerHTML = html
    }

    override fun injectScript(snippet: ScriptSnippet) {
        val scriptElement = document.createElement("script") as HTMLScriptElement
        scriptElement.text = snippet.source
        document.head?.appendChild(scriptElement)
    }

    override fun dispatchEvent(event: WindowEvent) {
        val customEvent = CustomEvent(event.type, CustomEventInit(detail = event.payload))
        window.dispatchEvent(customEvent)
    }

    override fun captureSnapshot(): WindowSnapshot = WindowSnapshot(
        timestampMillis = 0L, dom = document.body?.innerHTML ?: "",
        boundScripts = emptyList(), dispatchedEvents = emptyList(), isNoop = false,
    )
}
