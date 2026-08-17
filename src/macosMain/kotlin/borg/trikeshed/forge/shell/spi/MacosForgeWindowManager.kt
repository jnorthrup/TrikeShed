package borg.trikeshed.forge.shell.spi

import borg.trikeshed.forge.window.ForgeWindowManager
import borg.trikeshed.forge.window.ScriptSnippet
import borg.trikeshed.forge.window.WindowEvent
import borg.trikeshed.forge.window.WindowSnapshot

class MacosForgeWindowManager : ForgeWindowManager {
    override fun launch(html: String) { /* no-op */ }
    override fun bind(html: String) { /* no-op */ }
    override fun injectScript(snippet: ScriptSnippet) { /* no-op */ }
    override fun dispatchEvent(event: WindowEvent) { /* no-op */ }
    override fun captureSnapshot(): WindowSnapshot = WindowSnapshot(
        timestampMillis = 0L, dom = "", boundScripts = emptyList(),
        dispatchedEvents = emptyList(), isNoop = true,
    )
}
