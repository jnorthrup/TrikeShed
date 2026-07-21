package borg.trikeshed.forge.window

class NativeForgeWindowManager : ForgeWindowManager {
    private var currentHtml: String = ""
    private val scripts = mutableListOf<String>()
    private val events = mutableListOf<WindowEvent>()

    override fun launch(html: String) {
        currentHtml = html
    }

    override fun bind(html: String) {
        currentHtml = html
    }

    override fun injectScript(snippet: ScriptSnippet) {
        scripts.add(snippet.source)
    }

    override fun dispatchEvent(event: WindowEvent) {
        events.add(event)
    }

    override fun captureSnapshot(): WindowSnapshot {
        return WindowSnapshot(
            timestampMillis = 0L,
            dom = currentHtml,
            boundScripts = scripts.toList(),
            dispatchedEvents = events.toList(),
            isNoop = false
        )
    }
}
