package borg.trikeshed.forge.window

import platform.posix.*
import kotlinx.cinterop.*

/**
 * POSIX-based Window Manager.
 * Serves HTML to a temp file and opens it using the system browser command (`open` or `xdg-open`).
 */
class NativeForgeWindowManager : ForgeWindowManager {
    private var currentHtml: String = ""
    private val scripts = mutableListOf<String>()
    private val events = mutableListOf<WindowEvent>()

    @OptIn(ExperimentalForeignApi::class)
    override fun launch(html: String) {
        currentHtml = html
        val tempFilePath = "/tmp/forgeApp_native_${getpid()}.html"
        val file = fopen(tempFilePath, "w")
        if (file != null) {
            fputs(html, file)
            fclose(file)

            // Try to open using 'open' (macOS) or 'xdg-open' (Linux)
            if (system("open $tempFilePath") != 0) {
                system("xdg-open $tempFilePath")
            }
        }
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

    override fun captureSnapshot(): WindowSnapshot = WindowSnapshot(
        timestampMillis = 0L,
        dom = currentHtml,
        boundScripts = scripts.toList(),
        dispatchedEvents = events.toList(),
        isNoop = false,
    )
}
