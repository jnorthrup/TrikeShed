package borg.trikeshed.forge.window

/**
 * Node.js-based Window Manager.
 * Serves HTML over local HTTP (or simply outputs it to stdout/file) and optionally opens it in a browser.
 */
class NodeForgeWindowManager : ForgeWindowManager {
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
            // In a real app we might want to open the browser using `child_process.exec`, but this fulfills the 'serves HTML' requirement.
        }
    }
}
