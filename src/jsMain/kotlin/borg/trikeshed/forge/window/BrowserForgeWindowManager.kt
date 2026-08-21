package borg.trikeshed.forge.window

import kotlin.time.TimeSource

class BrowserForgeWindowManager : ForgeWindowManager {
    private var boundHtml: String = ""
    private val injected = mutableListOf<ScriptSnippet>()
    private val events = mutableListOf<WindowEvent>()

    override fun launch(html: String) {
        val isBrowser = js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean
        if (isBrowser) {
            js("document.open(); document.write(html); document.close();")
        }
    }

    override fun bind(html: String) {
        boundHtml = html
        val isBrowser = js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean
        if (isBrowser) {
            js("document.body.innerHTML = html;")
        }
    }

    override fun injectScript(snippet: ScriptSnippet) {
        injected.add(snippet)
        val isBrowser = js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean
        if (isBrowser) {
            val scriptCode = snippet.source
            js("var s = document.createElement('script'); s.type = 'text/javascript'; s.text = scriptCode; document.head.appendChild(s);")
        }
    }

    override fun dispatchEvent(event: WindowEvent) {
        events.add(event)
        val isBrowser = js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean
        if (isBrowser) {
            val type = event.type
            val payload = event.payload
            js("var e = new CustomEvent(type, { detail: payload }); window.dispatchEvent(e);")
        }
    }

    override fun captureSnapshot(): WindowSnapshot = WindowSnapshot(
        timestampMillis = TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds,
        dom = boundHtml,
        boundScripts = injected.map { it.id },
        dispatchedEvents = events.toList(),
        isNoop = false,
    )
}
