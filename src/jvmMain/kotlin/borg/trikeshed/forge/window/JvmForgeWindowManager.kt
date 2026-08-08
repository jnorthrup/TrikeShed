package borg.trikeshed.forge.window

import borg.trikeshed.common.Files
import java.awt.Desktop
import java.net.URI

/**
 * JVM-based Window Manager.
 * Writes the HTML to a temp file and opens it in the system's default browser.
 *
 * Uses the project's own [borg.trikeshed.common.Files] expect/actual abstraction
 * (posix-implemented, JVM-facaded over [borg.trikeshed.userspace.nio.file.spi.JvmFileOperations]).
 * No raw java.nio.file / com.sun.net.httpserver — those are forbidden by
 * AGENTS.md ("userspace.nio only"). The reactor Litebike path (JvmKanbanServer)
 * is the only sanctioned HTTP server.
 */
class JvmForgeWindowManager : ForgeWindowManager {
    private var currentHtml: String = ""
    private val scripts = mutableListOf<String>()
    private val events = mutableListOf<WindowEvent>()

    override fun launch(html: String) {
        currentHtml = html
        val tempDir = Files.createTempDir("forgeApp_jvm_")
        val tempPath = Files.resolvePath(tempDir, "index.html")
        Files.write(tempPath, html)
        println("JvmForgeWindowManager: Wrote HTML to file://$tempPath")

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI("file://$tempPath"))
        } else {
            println("Desktop browsing not supported. Open this URL manually: file://$tempPath")
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
