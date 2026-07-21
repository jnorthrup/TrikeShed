package borg.trikeshed.forge.window

/**
 * WASM/JS Browser-based Window Manager that writes HTML directly into the document.
 */
class BrowserForgeWindowManager : ForgeWindowManager {
    private var currentHtml: String = ""
    private val scripts = mutableListOf<String>()
    private val events = mutableListOf<WindowEvent>()

    override fun launch(html: String) {
        currentHtml = html
        if (isBrowser()) {
            writeHtml(html)
        }
    }

    override fun bind(html: String) {
        currentHtml = html
        if (isBrowser()) {
            bindHtml(html)
        }
    }

    override fun injectScript(snippet: ScriptSnippet) {
        scripts.add(snippet.source)
        if (isBrowser()) {
            appendScript(snippet.source)
        }
    }

    override fun dispatchEvent(event: WindowEvent) {
        events.add(event)
        if (isBrowser()) {
            dispatchCustomEvent(event.type, event.payload)
        }
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

fun isBrowser(): Boolean = js("typeof window !== 'undefined' && typeof document !== 'undefined'")

fun writeHtml(html: String): Unit = js("document.open(); document.write(html); document.close();")

fun bindHtml(html: String): Unit = js("window.requestAnimationFrame(function() { document.body.innerHTML = html; });")

fun appendScript(src: String): Unit = js("var s = document.createElement('script'); s.textContent = src; document.head.appendChild(s);")

fun dispatchCustomEvent(type: String, payload: String): Unit = js("window.dispatchEvent(new CustomEvent(type, {detail: payload}));")
