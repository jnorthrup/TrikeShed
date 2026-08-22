package borg.trikeshed.forge.server

import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.kanban.JvmTikaIngestAdapter
import borg.trikeshed.parse.json.JsonSupport
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Forge main ingester: serves docs/ and takes `POST /ingest` (body = file bytes, `X-Forge-Name` = filename,
 * `?persist=<userId>` optional). Bytes go through [JvmTikaIngestAdapter] — PDF/DOCX via Tika, images and scans via
 * Tesseract with the ffmpeg pre-pass — then through the shape gate; a plan persists only when asked and only
 * when [ForgeKanbanIngest.isPlan]. Reply: `{name, markdown, plan, persisted}`. Pages has no server: the page's Shape
 * view falls back to in-browser shapes there.
 */
object ForgeIngestServer {
    fun ingest(name: String, bytes: ByteArray, persistUser: String?): Map<String, Any?> {
        val dir = Files.createTempDirectory("forge-ingest-")
        val tmp = dir.resolve(name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "upload" })
        try {
            Files.write(tmp, bytes)
            val md = if (JvmTikaIngestAdapter.isTikaCandidate(tmp)) JvmTikaIngestAdapter.extractToMarkdown(tmp) else JvmTikaIngestAdapter.extract(tmp)
            val plan = ForgeKanbanIngest.isPlan(md)
            val persisted = persistUser != null && plan && Files.createTempFile("forge-ingest-", ".md").let { f ->
                Files.writeString(f, md); runBlocking { ForgeKanbanIngest.persistMarkdown(persistUser, f.toString()) }; Files.deleteIfExists(f)
            }
            return mapOf("name" to name, "markdown" to md, "plan" to plan, "persisted" to persisted)
        } finally { Files.deleteIfExists(tmp); Files.deleteIfExists(dir) }
    }

    private val types = mapOf("html" to "text/html", "js" to "text/javascript", "css" to "text/css", "json" to "application/json",
        "svg" to "image/svg+xml", "png" to "image/png", "webmanifest" to "application/manifest+json", "wasm" to "application/wasm", "map" to "application/json")

    private fun HttpExchange.reply(status: Int, type: String, body: ByteArray) {
        responseHeaders.add("Content-Type", type); sendResponseHeaders(status, body.size.toLong()); responseBody.use { it.write(body) }
    }

    @JvmStatic fun main(args: Array<String>) {
        val docs = Path.of(args.getOrElse(0) { "docs" }).toAbsolutePath().normalize()
        val port = args.getOrElse(1) { "8765" }.toInt()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/ingest") { ex ->
            if (ex.requestMethod != "POST") return@createContext ex.reply(405, "text/plain", "POST".encodeToByteArray())
            val name = ex.requestHeaders.getFirst("X-Forge-Name") ?: "upload"
            val persist = ex.requestURI.rawQuery?.split('&')?.firstOrNull { it.startsWith("persist=") }?.substringAfter('=')?.ifEmpty { null }
            val body = runCatching { ingest(name, ex.requestBody.readBytes(), persist) }
                .getOrElse { mapOf("name" to name, "error" to (it.message ?: it.toString())) }
            ex.reply(if ("error" in body) 422 else 200, "application/json", JsonSupport.stringify(body).encodeToByteArray())
        }
        server.createContext("/") { ex ->
            val rel = ex.requestURI.path.trimStart('/').ifEmpty { "index.html" }
            val file = docs.resolve(rel).normalize()
            if (!file.startsWith(docs) || !Files.isRegularFile(file)) return@createContext ex.reply(404, "text/plain", rel.encodeToByteArray())
            ex.reply(200, types[file.fileName.toString().substringAfterLast('.', "")] ?: "application/octet-stream", Files.readAllBytes(file))
        }
        server.start()
        println("Forge: serving $docs at http://127.0.0.1:$port/  —  POST /ingest (Tika; ffmpeg+tesseract for scans)  Ctrl-C to stop")
    }
}
