package borg.trikeshed.forge.window

import kotlin.time.TimeSource

class NoopForgeWindowManager : ForgeWindowManager {
    private val injected = mutableListOf<ScriptSnippet>()
    private val events = mutableListOf<WindowEvent>()
    private var boundHtml: String = ""

    override fun launch(html: String) {
        // no-op
    }

    override fun bind(html: String) { boundHtml = html }

    override fun injectScript(snippet: ScriptSnippet) { injected.add(snippet) }

    override fun dispatchEvent(event: WindowEvent) { events.add(event) }

    override fun captureSnapshot(): WindowSnapshot = WindowSnapshot(
        timestampMillis = TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds,
        dom = boundHtml,
        boundScripts = injected.map { it.id },
        dispatchedEvents = events.toList(),
        isNoop = true,
    )
}
