package borg.trikeshed.mcp

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.MatchGrade
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.memory.content
import borg.trikeshed.memory.description
import borg.trikeshed.memory.memoryFile
import modelmux.acp.MemoryHarnessProfile
import modelmux.acp.MemoryRole
import modelmux.acp.toolNames

/**
 * MCP server surface for citation-backed search (Prong 5).
 *
 * The paper's search agent contract (Section 2.2): (a, Gamma) = s(eta, q, M)
 * where Gamma is a set of references into M. MCP exposes the memory store as
 * addressable resources with citation support. External agents (Claude Code,
 * Codex) can query the store and get attributed answers.
 *
 * This is the JSON-RPC handler surface — it maps MCP method names to
 * [MemoryStore] operations. The transport layer (HTTP, stdio) plugs in via
 * [McpTransport]. The search agent returns (answer, citations) where citations
 * are memory file paths.
 */

/**
 * MCP resource: a memory file exposed to external agents.
 * uri j (description j contentCid)
 */
typealias McpResource = Triple<String, String, ContentId>

/**
 * MCP search result: an answer with citations.
 * answer j (citation paths)
 */
typealias McpSearchResult = Pair<String, List<String>>

/**
 * Transport abstraction — the caller (HTTP server, stdio bridge, kanban
 * route) implements this to connect the MCP handler to its wire format.
 */
interface McpTransport {
    fun respond(requestId: String, result: String)
    fun respondError(requestId: String, code: Int, message: String)
}

/**
 * MCP server handler over a [MemoryStore]. Handles the paper's search-agent
 * contract: list resources, read resources, search with citations.
 *
 * All operations are read-only — no write surface (paper: "search is read-only
 * in intent"). Write operations go through the ACP tool dispatcher (Prong 3).
 */
class McpServerHandler(
    private val store: MemoryStore,
    private val transport: McpTransport,
) {

    /**
     * Dispatch an MCP JSON-RPC method call.
     *
     * Supported methods:
     * - tools/list: returns the read tool set (paper's search-agent tools)
     * - resources/list: returns all memory files as MCP resources
     * - resources/read: returns a memory file's content by URI
     * - tools/call (search): returns (answer, citations) for a query
     */
    fun dispatch(method: String, params: Map<String, String>, requestId: String) {
        when (method) {
            "tools/list" -> transport.respond(requestId, toolsListJson())
            "resources/list" -> transport.respond(requestId, resourcesListJson())
            "resources/read" -> {
                val uri = params["uri"] ?: run {
                    transport.respondError(requestId, 400, "missing 'uri'")
                    return
                }
                val result = readResource(uri)
                if (result != null) transport.respond(requestId, result)
                else transport.respondError(requestId, 404, "resource not found: $uri")
            }
            "tools/call" -> {
                val query = params["query"] ?: run {
                    transport.respondError(requestId, 400, "missing 'query'")
                    return
                }
                val limit = params["limit"]?.toIntOrNull() ?: 3
                transport.respond(requestId, searchJson(query, limit))
            }
            else -> transport.respondError(requestId, 404, "unknown method: $method")
        }
    }

    /** List the read tools available to the search agent. */
    private fun toolsListJson(): String {
        val tools = MemoryHarnessProfile.CENTER.readTools
        val names = toolNames(tools)
        return buildString {
            append("{\"tools\":[")
            names.forEachIndexed { i, name ->
                if (i > 0) append(",")
                append("{\"name\":\"$name\"}")
            }
            append("]}")
        }
    }

    /** List all memory files as MCP resources. */
    private fun resourcesListJson(): String {
        val paths = store.listPaths()
        val resources = mutableListOf<McpResource>()
        for (i in 0 until paths.size) {
            val path = paths[i]
            val file = store.get(path) ?: continue
            val cid = ContentId.of(file.content)
            resources.add(McpResource(path, file.description, cid))
        }
        return buildString {
            append("{\"resources\":[")
            resources.forEachIndexed { i, res ->
                if (i > 0) append(",")
                append("{\"uri\":\"${res.first}\",\"description\":\"${escapeJson(res.second)}\",\"cid\":\"${res.third.value}\"}")
            }
            append("]}")
        }
    }

    /** Read a single resource by URI (memory file path). */
    private fun readResource(uri: String): String? {
        val file = store.get(uri) ?: return null
        return buildString {
            append("{\"uri\":\"$uri\",")
            append("\"description\":\"${escapeJson(file.description)}\",")
            append("\"content\":")
            append("\"${escapeJson(file.content.decodeToString())}\"}")
        }
    }

    /**
     * Search the memory store. Returns (answer, citations) — the paper's
     * (a, Gamma) contract. Uses LineCas link search (Ring 1) for line-level
     * matching, falls back to grep-style content search.
     */
    private fun searchJson(query: String, limit: Int): String {
        // Try LineCas link search first (Ring 1 — structural matching).
        val hits = try {
            store.linkSearch(query, MatchGrade.CONTENT_ONLY)
        } catch (e: Exception) {
            null
        }

        val citations = mutableListOf<String>()
        val answerParts = mutableListOf<String>()

        if (hits != null && hits.size > 0) {
            // Collect unique document CIDs from the hits.
            val seenPaths = mutableSetOf<String>()
            var count = 0
            for (i in 0 until hits.size) {
                if (count >= limit) break
                val hit = hits[i]
                val docCidHex = hit.docCid.hex
                // Resolve the docCid back to a path via the store.
                val paths = store.listPaths()
                for (j in 0 until paths.size) {
                    val p = paths[j]
                    if (p in seenPaths) continue
                    val spineCid = store.spineCidOf(p)
                    if (spineCid?.hex == docCidHex) {
                        seenPaths.add(p)
                        citations.add(p)
                        count++
                        break
                    }
                }
            }
            answerParts.add("Found ${hits.size} line-level matches across ${seenPaths.size} files.")
        }

        // Fallback: grep-style content search if LineCas found nothing.
        if (citations.isEmpty()) {
            val regex = try { Regex(query, RegexOption.IGNORE_CASE) } catch (e: Exception) {
                Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
            }
            val paths = store.listPaths()
            for (i in 0 until paths.size) {
                if (citations.size >= limit) break
                val p = paths[i]
                val file = store.get(p) ?: continue
                val text = file.content.decodeToString()
                if (regex.containsMatchIn(text)) {
                    citations.add(p)
                }
            }
            if (citations.isNotEmpty()) {
                answerParts.add("Found ${citations.size} files matching '$query'.")
            }
        }

        return buildString {
            append("{\"answer\":\"${escapeJson(answerParts.joinToString(" "))}\",")
            append("\"citations\":[")
            citations.forEachIndexed { i, path ->
                if (i > 0) append(",")
                append("\"$path\"")
            }
            append("]}")
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

/**
 * Simple in-memory transport for testing. Captures responses.
 */
class InMemoryMcpTransport : McpTransport {
    val responses = mutableMapOf<String, String>()
    val errors = mutableMapOf<String, Pair<Int, String>>()

    override fun respond(requestId: String, result: String) {
        responses[requestId] = result
    }

    override fun respondError(requestId: String, code: Int, message: String) {
        errors[requestId] = code to message
    }
}
