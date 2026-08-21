package borg.trikeshed.forge.window

import kotlin.time.TimeSource

class NodeForgeWindowManager : ForgeWindowManager {
    private var boundHtml: String = ""
    private val injected = mutableListOf<ScriptSnippet>()
    private val events = mutableListOf<WindowEvent>()

    override fun launch(html: String) {
        val isNode = js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null") as Boolean
        if (isNode) {
            val http = js("require('http')")
            val server = http.createServer { req: dynamic, res: dynamic ->
                res.writeHead(200, js("{'Content-Type': 'text/html'}"))
                res.end(html)
            }
            server.listen(8080)
            println("NodeForgeWindowManager: Serving HTML on http://localhost:8080")
        }
    }

    override fun bind(html: String) {
        boundHtml = html
    }

    override fun injectScript(snippet: ScriptSnippet) {
        injected.add(snippet)
    }

    override fun dispatchEvent(event: WindowEvent) {
        events.add(event)
    }

    override fun captureSnapshot(): WindowSnapshot = WindowSnapshot(
        timestampMillis = TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds,
        dom = boundHtml,
        boundScripts = injected.map { it.id },
        dispatchedEvents = events.toList(),
        isNoop = false,
    )
}
