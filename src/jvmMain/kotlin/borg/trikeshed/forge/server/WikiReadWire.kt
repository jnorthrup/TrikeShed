package borg.trikeshed.forge.server

import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify
import borg.trikeshed.wiki.*
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Read-only HTTP surface onto the curation plane at `<forgeHome>/wiki`.
 *
 * Mission-002 (VAL-CROSS-002) found the curation store unreachable through the daemon: the
 * only wiki legos registered are `WIKI_CONSOLIDATE` and `WIKI_PROPOSE`, both wired to
 * `BrainClient.chatSeat`, so READING a curated artifact required an API key and a model
 * round-trip — and the couch attachment plane held git refs alone. The store was hosted and
 * durable but had no way to be read as itself; `GET /api/cas/get?cid=…` could return the
 * bytes only because the caller already knew the content id.
 *
 * Read-only routes:
 *
 *  - `GET /api/wiki/list[?prefix=…]` — the plane's files as JSON: relative path, byte length,
 *    and sha256 content id, so a caller can verify what it is about to fetch.
 *  - `GET /api/wiki/read?path=…`     — one file's bytes verbatim, with its cid in the
 *    `X-Content-Id` header for verification against a freeze record.
 *  - `GET /wiki[?page=…]` — linked retained library sections and wiki articles, with contents and backlinks.
 *
 * Paths are resolved against the plane root and REJECTED if they escape it; symlinks are
 * resolved before that check, so a link pointing out of the plane cannot serve the filesystem.
 */
class WikiReadWire(
    private val curations: (() -> borg.trikeshed.narsese.ConstellationNodes?)? = null,
    /** Resolved lazily: the daemon builds the wire before the plane is guaranteed to exist. */
    private val wikiRoot: () -> File,
) {
    companion object {
        const val LIST_ROUTE: String = "/api/wiki/list"
        const val READ_ROUTE: String = "/api/wiki/read"
        const val PREFIX: String = "/api/wiki/"

        val ROUTES: List<Pair<String, String>> = listOf("GET" to LIST_ROUTE, "GET" to READ_ROUTE, "GET" to "/wiki")

        /** Guard against a `list` on a huge plane answering an unbounded document. */
        private const val MAX_LIST_ENTRIES = 5000

        internal fun cidOf(bytes: ByteArray): String =
            "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { b -> "%02x".format(b) }

        private fun jsonString(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }

        private fun decode(raw: String): String =
            try { URLDecoder.decode(raw, StandardCharsets.UTF_8) }
            catch (_: IllegalArgumentException) { raw }

        internal fun query(path: String, name: String): String? =
            path.substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.let(::decode)

        /**
         * The plane-relative path of [target] if it genuinely lives under [root], else null.
         * Both sides are canonicalized first, so `..` segments and symlinks are resolved
         * BEFORE containment is judged rather than pattern-matched away.
         */
        internal fun relativeWithin(root: File, target: File): String? {
            val rootPath = root.canonicalFile.toPath()
            val targetPath = target.canonicalFile.toPath()
            if (targetPath == rootPath) return ""
            if (!targetPath.startsWith(rootPath)) return null
            return rootPath.relativize(targetPath).toString().replace(File.separatorChar, '/')
        }
    }

    private fun json(status: Int, body: String) =
        JvmKanbanServer.HttpResponse(status, body, "application/json; charset=utf-8")

    private fun contentType(name: String): String = when {
        name.endsWith(".md") -> "text/markdown; charset=utf-8"
        name.endsWith(".json") -> "application/json; charset=utf-8"
        name.endsWith(".txt") || name.endsWith(".tsv") -> "text/plain; charset=utf-8"
        name.endsWith(".html") -> "text/html; charset=utf-8"
        else -> "application/octet-stream"
    }

    /** Project retained curation identities into the bounded, containment-checked wiki plane. */
    private suspend fun page(path: String): JvmKanbanServer.HttpResponse {
        val selected = query(path, "page")?.takeIf { it.isNotBlank() }
        val source = query(path, "source") == "1"
        fun html(status: Int, articles: Series<WikiArticle>, unavailable: String? = null, truncated: Boolean = false) =
            JvmKanbanServer.HttpResponse(status,
                WikiSurface.render(articles, selected, source, unavailable, truncated), "text/html; charset=utf-8")
        val files = mutableListOf<WikiArticle>()
        val listing = checkNotNull(route("GET", LIST_ROUTE, ByteArray(0)))
        val index = if (listing.status == 200) reify(listing.body) as Map<*, *> else emptyMap<Any?, Any?>()
        var truncated = index["truncated"] == true
        var selectedStatus = if (selected == null) 200 else 404
        for (entry in (index["files"] as? List<*>).orEmpty()) {
            val name = (entry as? Map<*, *>)?.get("path") as? String ?: continue
            if (!name.endsWith(".md", ignoreCase = true)) continue
            val response = checkNotNull(route("GET", "$READ_ROUTE?path=${WikiSurface.encode(name)}", ByteArray(0)))
            if (selected == name) selectedStatus = response.status
            if (response.status != 200) { truncated = true; continue }
            files.add(WikiArticle(name, response.payloadBytes.decodeToString(), response.headers["X-Content-Id"].orEmpty()))
        }
        val articles = try {
            val source = files.toSeries()
            curations?.invoke()?.let { CurationWiki(it).pages(selected, source) } ?: source
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            return html(500, emptySeriesOf(), "The retained library could not be read: ${error.message}")
        }
        if (listing.status != 200 && articles.size == 0)
            return html(listing.status, articles, "The curation wiki is unavailable.")
        if (articles.view.any { it.path == selected }) selectedStatus = 200
        return html(selectedStatus, articles,
            if (selectedStatus >= 500) "This curation could not be read." else null, truncated)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun route(
        method: String,
        path: String,
        payload: ByteArray,
        respond: (suspend (ByteArray) -> Unit)? = null,
    ): JvmKanbanServer.HttpResponse? {
        val bare = path.substringBefore('?')
        if (bare == "/wiki" || bare == "/wiki/" || bare == "/wiki.html") {
            return if (method == "GET") page(path)
            else json(405, """{"error":"method_not_allowed"}""")
        }
        if (!bare.startsWith(PREFIX)) return null
        if (method != "GET") {
            return json(405, """{"error":"method_not_allowed","method":${jsonString(method)},"path":${jsonString(bare)}}""")
        }

        val root = wikiRoot()
        if (!root.isDirectory) {
            return json(503, """{"error":"no_curation_plane","root":${jsonString(root.absolutePath)}}""")
        }

        return when (bare) {
            LIST_ROUTE -> {
                val filter = query(path, "prefix").orEmpty()
                val entries = ArrayList<String>()
                var truncated = false
                root.walkTopDown().onEnter { it.name != ".git" }.forEach { f ->
                    if (truncated || !f.isFile) return@forEach
                    val rel = relativeWithin(root, f) ?: return@forEach
                    if (filter.isNotEmpty() && !rel.startsWith(filter)) return@forEach
                    if (entries.size >= MAX_LIST_ENTRIES) { truncated = true; return@forEach }
                    val bytes = try { f.readBytes() } catch (_: Exception) { return@forEach }
                    entries.add(
                        "{\"path\":${jsonString(rel)},\"bytes\":${bytes.size},\"cid\":${jsonString(cidOf(bytes))}}"
                    )
                }
                entries.sort()
                json(
                    200,
                    "{\"ok\":true,\"root\":${jsonString(root.absolutePath)},\"count\":${entries.size}," +
                        "\"truncated\":$truncated,\"files\":[${entries.joinToString(",")}]}"
                )
            }

            READ_ROUTE -> {
                val rel = query(path, "path")
                if (rel.isNullOrBlank()) return json(400, """{"error":"path_required","route":"$READ_ROUTE"}""")
                val target = File(root, rel)
                val within = relativeWithin(root, target)
                    ?: return json(403, """{"error":"outside_curation_plane","path":${jsonString(rel)}}""")
                if (within.isEmpty() || !target.isFile) {
                    return json(404, """{"error":"not_found","path":${jsonString(rel)}}""")
                }
                val bytes = try {
                    target.readBytes()
                } catch (t: Throwable) {
                    return json(500, "{\"ok\":false,\"error\":${jsonString(t.toString())}}")
                }
                JvmKanbanServer.HttpResponse(
                    200, "", contentType(within), bytes,
                    headers = mapOf(
                        "X-Content-Id" to cidOf(bytes),
                        "X-Wiki-Path" to within,
                    ),
                )
            }

            else -> json(404, """{"error":"no_such_route","path":${jsonString(bare)}}""")
        }
    }
}
