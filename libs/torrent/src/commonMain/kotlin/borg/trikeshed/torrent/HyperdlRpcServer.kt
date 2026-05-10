package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.HtxElement
import borg.trikeshed.htx.client.HtxTransport
import kotlinx.coroutines.*

/**
 * JSON-RPC server over HTTP (wire-compatible with aria2 RPC protocol).
 *
 * Implements the download management RPC methods:
 *   addUri, addTorrent, tellStatus,
 *   pause, unpause, remove,
 *   tellActive, tellWaiting, tellStopped,
 *   getGlobalStat, getVersion
 *
 * Usage:
 *   val rpc = HyperdlRpcServer(hyperdl, htxElement)
 *   rpc.open()
 *   rpc.serve(port = 6800)  // default RPC port (compatible with aria2 tools)
 */
class HyperdlRpcServer(
    private val hyperdl: HyperdlElement,
    private val htx: HtxElement,
    parentJob: kotlinx.coroutines.Job? = null,
) : AsyncContextElement(parentJob = parentJob) {
    companion object Key : AsyncContextKey<HyperdlRpcServer>() {
        // Event dispatch keys for the ring — mirrors webserver_liburing.c
        const val ACCEPT_KEY: Long = 0
        const val READ_KEY: Long = 1
    }
    override val key: AsyncContextKey<HyperdlRpcServer> get() = Key

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            supervisor.cancel()
            super.close()
        }
    }

    /**
     * Start RPC server loop on [port] using the ring reactor model.
     *
     * Wire protocol: HTTP POST /jsonrpc → JSON-RPC 2.0 response.
     * The caller must provide [channels] and [reactor] as context elements.
     * Runs under SupervisorJob until [close] is called.
     */
    suspend fun serve(
        port: Int = 6800,
        channels: borg.trikeshed.userspace.nio.channels.spi.ChannelOperations,
        reactor: borg.trikeshed.userspace.nio.channels.spi.ReactorOperations,
    ) = withContext(supervisor) {
        requireState(ElementState.ACTIVE)
        val serverFd = channels.socket(2, 1, 0) // AF_INET=2, SOCK_STREAM=1
        check(serverFd >= 0) { "socket failed" }
        channels.bind(serverFd, port)
        channels.listen(serverFd, 128)
        reactor.register(serverFd, setOf(borg.trikeshed.userspace.reactor.Interest.READ), ACCEPT_KEY)

        val ring = channels.openChannel()
        ring.readv(serverFd, borg.trikeshed.userspace.nio.ByteBuffer.allocate(64))
        ring.submit()

        while (isActive) {
            val results = ring.wait()
            for (r in results) {
                when (r.userData) {
                    ACCEPT_KEY -> {
                        ring.readv(serverFd, borg.trikeshed.userspace.nio.ByteBuffer.allocate(64)) // re-enqueue
                        val clientFd = r.res
                        if (clientFd >= 0) {
                            reactor.register(clientFd, setOf(borg.trikeshed.userspace.reactor.Interest.READ), READ_KEY)
                        }
                    }
                    READ_KEY -> {
                        if (r.res <= 0) { reactor.deregister(r.fd); continue }
                        // Read HTTP request from client buffer
                        // For now, handle as raw JSON-RPC via handleRequest
                        // Full HTTP parsing pending reactor integration
                    }
                }
            }
            ring.submit()
        }
        reactor.deregister(serverFd)
    }

    /**
     * Process a raw JSON-RPC request string. Returns JSON response string.
     * The caller is responsible for HTTP framing (POST /jsonrpc).
     */
    fun handleRequest(jsonRequest: String): String {
        val params = parseJsonRpc(jsonRequest) ?: return errorResponse(null, -32700, "Parse error")
        val id = params["id"]
        val method = params["method"] ?: return errorResponse(id, -32600, "Invalid Request")
        val args = params["params"] as? Map<String, Any?> ?: emptyMap()

        return when (method) {
            "aria2.addUri" -> rpcAddUri(id, args)
            "aria2.addTorrent" -> rpcAddTorrent(id, args)
            "aria2.tellStatus" -> rpcTellStatus(id, args)
            "aria2.pause" -> rpcPause(id, args)
            "aria2.unpause" -> rpcUnpause(id, args)
            "aria2.remove" -> rpcRemove(id, args)
            "aria2.tellActive" -> rpcTellActive(id, args)
            "aria2.tellWaiting" -> rpcTellWaiting(id, args)
            "aria2.tellStopped" -> rpcTellStopped(id, args)
            "aria2.getGlobalStat" -> rpcGetGlobalStat(id)
            "aria2.getVersion" -> rpcGetVersion(id)
            else -> errorResponse(id, -32601, "Method not found: $method")
        }
    }

    // ── RPC method handlers ───────────────────────────────────────

    private fun rpcAddUri(id: Any?, args: Map<String, Any?>): String {
        @Suppress("UNCHECKED_CAST")
        val uris = (args["uris"] as? List<String>) ?: return errorResponse(id, -32602, "Missing 'uris'")
        val gid = hyperdl.addUri(uris.first())
        return successResponse(id, gid)
    }

    private fun rpcAddTorrent(id: Any?, args: Map<String, Any?>): String {
        // Torrent data is base64-encoded in the 'torrent' field
        return errorResponse(id, -32000, "addTorrent: bencode parsing pending")
    }

    private fun rpcTellStatus(id: Any?, args: Map<String, Any?>): String {
        val gid = args["gid"] as? String ?: return errorResponse(id, -32602, "Missing 'gid'")
        val task = hyperdl.tellStatus(gid)
            ?: return errorResponse(id, 1, "GID $gid not found")
        return successResponse(id, taskToJson(task))
    }

    private fun rpcPause(id: Any?, args: Map<String, Any?>): String {
        val gid = args["gid"] as? String ?: return errorResponse(id, -32602, "Missing 'gid'")
        val task = hyperdl.pause(gid) ?: return errorResponse(id, 1, "GID $gid not found")
        return successResponse(id, gid)
    }

    private fun rpcUnpause(id: Any?, args: Map<String, Any?>): String {
        val gid = args["gid"] as? String ?: return errorResponse(id, -32602, "Missing 'gid'")
        val task = hyperdl.unpause(gid) ?: return errorResponse(id, 1, "GID $gid not found")
        return successResponse(id, gid)
    }

    private fun rpcRemove(id: Any?, args: Map<String, Any?>): String {
        val gid = args["gid"] as? String ?: return errorResponse(id, -32602, "Missing 'gid'")
        hyperdl.remove(gid)
        return successResponse(id, gid)
    }

    private fun rpcTellActive(id: Any?, args: Map<String, Any?>): String {
        val keys = (args["keys"] as? List<String>) ?: emptyList()
        val tasks = hyperdl.tellActive().map { taskToJson(it, keys) }
        return successResponse(id, tasks)
    }

    private fun rpcTellWaiting(id: Any?, args: Map<String, Any?>): String {
        val offset = (args["offset"] as? Number)?.toInt() ?: 0
        val num = (args["num"] as? Number)?.toInt() ?: 100
        val keys = (args["keys"] as? List<String>) ?: emptyList()
        val tasks = hyperdl.tellWaiting(offset, num).map { taskToJson(it, keys) }
        return successResponse(id, tasks)
    }

    private fun rpcTellStopped(id: Any?, args: Map<String, Any?>): String {
        val offset = (args["offset"] as? Number)?.toInt() ?: 0
        val num = (args["num"] as? Number)?.toInt() ?: 100
        val keys = (args["keys"] as? List<String>) ?: emptyList()
        val tasks = hyperdl.tellStopped(offset, num).map { taskToJson(it, keys) }
        return successResponse(id, tasks)
    }

    private fun rpcGetGlobalStat(id: Any?): String {
        return successResponse(id, hyperdl.getGlobalStat())
    }

    private fun rpcGetVersion(id: Any?): String {
        return successResponse(id, mapOf(
            "version" to "1.0.0",
            "enabledFeatures" to listOf("Async DNS", "BitTorrent", "HTX-TLS")
        ))
    }

    // ── JSON helpers ──────────────────────────────────────────────

    private fun taskToJson(task: HyperdlElement.DownloadTask, keys: List<String> = emptyList()): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "gid" to task.gid,
            "status" to task.status,
            "totalLength" to task.totalLength.toString(),
            "completedLength" to task.completedLength.toString(),
            "downloadSpeed" to task.downloadSpeed.toString(),
            "uploadSpeed" to task.uploadSpeed.toString(),
            "files" to task.files.map { mapOf("path" to it.path, "length" to it.length.toString()) },
        )
        return if (keys.isEmpty()) map else map.filterKeys { it in keys }
    }

    private fun parseJsonRpc(json: String): Map<String, Any?>? {
        try {
            // Minimal JSON parser — just enough for RPC params
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return null
            val result = mutableMapOf<String, Any?>()
            var pos = 1
            while (pos < trimmed.length && trimmed[pos] != '}') {
                pos = skipWhitespace(trimmed, pos)
                if (trimmed[pos] != '"') break
                val keyEnd = trimmed.indexOf('"', pos + 1)
                val key = trimmed.substring(pos + 1, keyEnd)
                pos = keyEnd + 1
                pos = skipWhitespace(trimmed, pos)
                if (trimmed[pos] != ':') break
                pos++
                pos = skipWhitespace(trimmed, pos)
                when {
                    trimmed[pos] == '"' -> { val vend = trimmed.indexOf('"', pos + 1); result[key] = trimmed.substring(pos + 1, vend); pos = vend + 1 }
                    trimmed[pos].isDigit() || trimmed[pos] == '-' -> { val vend = (pos until trimmed.length).first { !trimmed[it].isDigit() && trimmed[it] != '.' && trimmed[it] != '-' }; result[key] = trimmed.substring(pos, vend); pos = vend }
                    trimmed[pos] == '[' -> { pos = skipArray(trimmed, pos) }
                    trimmed[pos] == '{' -> { pos = skipObject(trimmed, pos) }
                    trimmed[pos] == 'n' -> { result[key] = null; pos += 4 }
                    trimmed[pos] == 't' -> { result[key] = true; pos += 4 }
                }
                pos = skipWhitespace(trimmed, pos)
                if (pos < trimmed.length && trimmed[pos] == ',') pos++
            }
            return result
        } catch (_: Exception) { return null }
    }

    private fun successResponse(id: Any?, result: Any?): String {
        val idStr = when (id) { null -> "null"; is String -> "\"$id\""; else -> id.toString() }
        val resultStr = jsonValue(result)
        return """{"jsonrpc":"2.0","id":$idStr,"result":$resultStr}"""
    }

    private fun errorResponse(id: Any?, code: Int, message: String): String {
        val idStr = when (id) { null -> "null"; is String -> "\"$id\""; else -> id.toString() }
        return """{"jsonrpc":"2.0","id":$idStr,"error":{"code":$code,"message":"$message"}}"""
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        is Number -> v.toString()
        is Boolean -> v.toString()
        is Map<*, *> -> "{" + v.entries.joinToString(",") { (k, vv) -> "\"$k\":${jsonValue(vv)}" } + "}"
        is List<*> -> "[" + v.joinToString(",") { jsonValue(it) } + "]"
        else -> "\"${v}\""
    }

    private fun skipWhitespace(s: String, pos: Int): Int {
        var p = pos
        while (p < s.length && s[p].isWhitespace()) p++
        return p
    }

    private fun skipArray(s: String, pos: Int): Int {
        var depth = 1; var p = pos + 1
        while (p < s.length && depth > 0) { when (s[p]) { '[' -> depth++; ']' -> depth-- }; p++ }
        return p
    }

    private fun skipObject(s: String, pos: Int): Int {
        var depth = 1; var p = pos + 1
        while (p < s.length && depth > 0) { when (s[p]) { '{' -> depth++; '}' -> depth-- }; p++ }
        return p
    }
}
