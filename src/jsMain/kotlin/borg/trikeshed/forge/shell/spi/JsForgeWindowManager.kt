package borg.trikeshed.forge.shell.spi

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CustomEvent
import org.w3c.dom.CustomEventInit
import org.w3c.dom.HTMLScriptElement

class JsForgeWindowManager : ForgeWindowManager {
    override fun bind(html: String) {
        document.body?.innerHTML = html
    }

    override fun injectScript(script: String) {
        val scriptElement = document.createElement("script") as HTMLScriptElement
        scriptElement.text = script
        document.head?.appendChild(scriptElement)
    }

    override fun dispatchEvent(event: String, payload: String) {
        val customEvent = CustomEvent(event, CustomEventInit(detail = payload))
        window.dispatchEvent(customEvent)
    }

    override suspend fun captureSnapshot(): ByteArray {
        // Simple no-op implementation for now since JS doesn't have a direct way to take screenshot of itself easily
        return ByteArray(0)
    }
}
