package borg.trikeshed.forge.window

import java.awt.Desktop
import java.net.URI
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * JVM-based Window Manager.
 * Serves the HTML over local HTTP and opens it in the system's default browser.
 */
class JvmForgeWindowManager : ForgeWindowManager {
    private var currentHtml: String = ""
    private val scripts = mutableListOf<String>()
    private val events = mutableListOf<WindowEvent>()

    override fun launch(html: String) {
        currentHtml = html
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { os ->
                os.write(bytes)
            }
        }
        server.start()

        val port = server.address.port
        val url = "http://localhost:$port/"
        println("JvmForgeWindowManager: Serving HTML on $url")

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        } else {
            println("Desktop browsing not supported. Open this URL manually: $url")
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
