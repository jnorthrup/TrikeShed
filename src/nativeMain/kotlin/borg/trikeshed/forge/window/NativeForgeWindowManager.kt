package borg.trikeshed.forge.window

import borg.trikeshed.userspace.nio.file.spi.PosixFileOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixProcessOperations
import kotlinx.coroutines.runBlocking

class NativeForgeWindowManager : ForgeWindowManager {
    private var currentHtml: String = ""
    private val scripts = mutableListOf<String>()
    private val events = mutableListOf<WindowEvent>()

    override fun launch(html: String) {
        currentHtml = html
        val fileOps = PosixFileOperations()
        val tempDir = fileOps.createTempDir("forge")
        val path = "$tempDir/forge-window.html"
        fileOps.write(path, html)

        runBlocking {
            val processOps = PosixProcessOperations()
            // Try 'open' (macOS) then fallback to 'xdg-open' (Linux)
            try {
                val res = processOps.exec(
                    command = "open",
                    args = listOf(path),
                    env = emptyMap(),
                    stdin = null
                )
                if (res.exitCode != 0) {
                    processOps.exec(
                        command = "xdg-open",
                        args = listOf(path),
                        env = emptyMap(),
                        stdin = null
                    )
                }
            } catch (e: Exception) {
                // Ignore fallback execution errors
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
