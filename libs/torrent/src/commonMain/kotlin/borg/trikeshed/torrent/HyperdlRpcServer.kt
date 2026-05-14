package borg.trikeshed.torrent

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.reactor.Interest
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext

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
    private val htx: borg.trikeshed.htx.client.HtxElement,
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

    /** Per-client request accumulators. */
    private val clientBuffers = LinkedHashMap<Int, StringBuilder>()

    /**
     * Start RPC server loop. Uses [channels] for socket+ring I/O and
     * [reactor] for fd registration.
     *
     * Accept path: ring.prepAccept → submit → wait → read clientFd
     * Read path: ring.readv → submit → wait → parse → respond
     */
    suspend fun serve(
        port: Int = 6800,
        channels: ChannelOperations,
    ) = withContext(supervisor) {
        requireState(ElementState.ACTIVE)
        val reactor = currentCoroutineContext()[ReactorOperations.Key]
            ?: error("ReactorOperations not found in coroutine context")

        val serverFd = channels.socket(2, 1, 0) // AF_INET=2, SOCK_STREAM=1
        check(serverFd >= 0) { "socket() failed" }
        val bindRes = channels.bind(serverFd, port)
        check(bindRes == 0) { "bind(port=$port) failed" }
        channels.listen(serverFd, 128)

        val ring = channels.openChannel()
        reactor.register(serverFd, setOf(Interest.READ), ACCEPT_KEY)

        while (isActive) {
            val signals = reactor.poll(kotlin.time.Duration.INFINITE)
            for (signal in signals) {
                val fd = signal.fd
                if (fd == serverFd && signal.interests.contains(Interest.READ)) {
                    // Accept a new client connection
                    val clientFd = channels.accept(serverFd)
                    if (clientFd >= 0) {
                        clientBuffers[clientFd] = StringBuilder()
                        reactor.register(clientFd, setOf(Interest.READ), READ_KEY_BASE + clientFd.toLong())
                    }
                } else if (signal.interests.contains(Interest.READ)) {
                    handleClientData(fd, ring, channels)
                }
            }
        }
        reactor.deregister(serverFd)
        channels.close(serverFd)
    }

    /**
     * Read HTTP data from client fd, parse request, respond.
     */
    private suspend fun handleClientData(
        clientFd: Int, ring: ChannelOperations.ChannelHandle, channels: ChannelOperations,
    ) {
        val reactor = currentCoroutineContext()[ReactorOperations.Key]
            ?: error("ReactorOperations not found in coroutine context")
        val buf = clientBuffers.getOrPut(clientFd) { StringBuilder() }
        val rb = ByteBuffer.allocate(8192)
        ring.readv(clientFd, rb, 0L)
        val submitResults = ring.submit()
        val completions = ring.wait(minComplete = submitResults)
        val received = completions.find { it.fd == clientFd }

        val bytesRead = received?.res ?: -1
        if (bytesRead <= 0) {
            clientBuffers.remove(clientFd)
            reactor.deregister(clientFd)
            channels.close(clientFd)
            return
        }
        rb.position(0); rb.limit(bytesRead)
        val chunk = ByteArray(bytesRead)
        rb.get(chunk, 0, bytesRead)
        buf.append(chunk.decodeToString())
        val text = buf.toString()
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd < 0) {
            // Incomplete headers — wait for more data
            ring.readv(clientFd, ByteBuffer.allocate(8192), READ_KEY_BASE + clientFd.toLong())
            return
        }
        val header = text.substring(0, headerEnd)
        val contentLength = parseContentLength(header)
        val bodyStart = headerEnd + 4
        val bodyLength = text.length - bodyStart
        if (bodyLength < contentLength) {
            // Incomplete body — wait for more data
            ring.readv(clientFd, ByteBuffer.allocate(8192), READ_KEY_BASE + clientFd.toLong())
            return
        }
        val body = text.substring(bodyStart, bodyStart + contentLength)
        buf.clear()
        if (bodyStart + contentLength < text.length) buf.append(text.substring(bodyStart + contentLength))

        val firstLine = header.substringBefore("\r\n")
        val parts = firstLine.split(" ")
        if (parts.size < 2 || parts[1] != "/jsonrpc") {
            writeResponse(clientFd, ring, 404, "Not Found")
            clientBuffers.remove(clientFd)
            reactor.deregister(clientFd)
            channels.close(clientFd)
            return
        }

        val response = handleRequest(body)
        write200(clientFd, ring, response)
        clientBuffers.remove(clientFd)
        reactor.deregister(clientFd)
        channels.close(clientFd)

    }

    private fun writeResponse(clientFd: Int, ring: ChannelOperations.ChannelHandle, status: Int, body: String) {
        val statusText = when (status) { 200 -> "OK"; else -> "Error" }
        val http = "HTTP/1.1 $status $statusText\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        ring.write(ByteBuffer.wrap(http.encodeToByteArray()), 0L)
    }

    private fun write200(clientFd: Int, ring: ChannelOperations.ChannelHandle, response: CharSequence) {
        val body = response.toString()
        val http = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        ring.write(ByteBuffer.wrap(http.encodeToByteArray()), 0L)
    }

    private fun parseContentLength(header: String): Int {
        val idx = header.indexOf("Content-Length:", ignoreCase = true)
        if (idx < 0) return 0
        val lineEnd = header.indexOf("\r\n", idx)
        if (lineEnd < 0) return 0
        val line = header.substring(idx, lineEnd)
        val valEnd = line.indexOf('\n')
        if (valEnd < 0) return 0
        val colon = line.indexOf(':')
        if (colon < 0) return 0
        return line.substring(colon + 1).trim().toIntOrNull() ?: 0
    }

    /**
     * Process a raw JSON-RPC request string. Returns JSON response string.
     * The caller is responsible for HTTP framing (POST /jsonrpc).
     */
    fun handleRequest(jsonRequest: CharSequence): CharSequence {
        val params = parseJsonRpc(jsonRequest) ?: return errorResponse(null, -32700, "Parse error")
        val id = params["id"]
        val method = params["method"] ?: return errorResponse(id, -32600, "Invalid Request")
        val args = params["params"] as? Map<CharSequence, Any?> ?: emptyMap()

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

    private fun rpcAddUri(id: Any?, args: Map<CharSequence, Any?>): CharSequence {
        @Suppress("UNCHECKED_CAST")
        val uris = (args["uris"] as? List<CharSequence>) ?: return errorResponse(id, -32602, "Missing 'uris'")
        val gid = hyperdl.addUri(uris.first())
        return successResponse(id, gid)
    }

    private fun rpcAddTorrent(id: Any?, args: Map<CharSequence, Any?>): CharSequence {
        // Torrent data is base64-encoded in the 'torrent' field
        return errorResponse(id, -32000, "addTorrent: bencode parsing pending")
    }

    private fun rpcTellStatus(id: Any?, args: Map<CharSequence, Any?>): CharSequence {
        val gid = args["gid"] as? CharSequence ?: return errorResponse(id, -32602, "Missing 'gid'")
        val task = hyperdl.tellStatus(gid)
            ?: return errorResponse(id, 1, "GID $gid not found")
        return successResponse(id, taskToJson(task))
    }

    private fun rpcPause(id: Any?, args: Map<CharSequence, Any?>): CharSequence {
        val gid = args["gid"] as? CharSequence ?: return errorResponse(id, -32602, "Missing 'gid'")
        val task = hyperdl.pause(gid) ?: return errorResponse(id, 1, "GID $gid not found")
        return successResponse(id, gid)
    }

    private fun rpcUnpause(id: Any?, args: Map<CharSequence, Any?>): CharSequence {
        val gid = args["gid"] as? CharSequence ?: return errorResponse(id, -32602, "Missing 'gid'")
        val task = hyperdl.unpause(gid) ?: return errorResponse(id, 1, "GID $gid not found")
        return successResponse(id, gid)
    }

    private fun rpcRemove(id: Any?, args: Map<CharSequence, Any?>): CharSequence {
        val gid = args["gid"] as? CharSequence ?: return errorResponse(id, -32602, "Missing 'gid'")
        hyperdl.remove(gid)
        return successResponse(id, gid)
    }

    private fun rpcTellActive(id: Any?, args: Map<CharSequence, Any?>): CharSequence {
        val keys = (args["keys"] as? List<CharSequence>) ?: emptyList()
        val tasks = hyperdl.tellActive().map { taskToJson(it, keys) }
        return successResponse(id, tasks)
    }

    private fun rpcTellWaiting(id: Any?, args: Map<CharSequence, Any?>): CharSequence {
        val offset = (args["offset"] as? Number)?.toInt() ?: 0
        val num = (args["num"] as? Number)?.toInt() ?: 100
        val keys = (args["keys"] as? List<CharSequence>) ?: emptyList()
        val tasks = hyperdl.tellWaiting(offset, num).map { taskToJson(it, keys) }
        return successResponse(id, tasks)
    }

    private fun rpcTellStopped(id: Any?, args: Map<CharSequence, Any?>): CharSequence {
        val offset = (args["offset"] as? Number)?.toInt() ?: 0
        val num = (args["num"] as? Number)?.toInt() ?: 100
        val keys = (args["keys"] as? List<CharSequence>) ?: emptyList()
        val tasks = hyperdl.tellStopped(offset, num).map { taskToJson(it, keys) }
        return successResponse(id, tasks)
    }

    private fun rpcGetGlobalStat(id: Any?): CharSequence {
        return successResponse(id, hyperdl.getGlobalStat())
    }

    private fun rpcGetVersion(id: Any?): CharSequence {
        return successResponse(id, mapOf(
            "version" to "1.0.0",
            "enabledFeatures" to listOf("Async DNS", "BitTorrent", "HTX-TLS")
        ))
    }

    // ── JSON helpers ──────────────────────────────────────────────

    private fun taskToJson(task: HyperdlElement.DownloadTask, keys: List<CharSequence> = emptyList()): Map<CharSequence, Any?> {
        val map = LinkedHashMap<CharSequence, Any?>(
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

    private fun parseJsonRpc(json: CharSequence): Map<CharSequence, Any?>? {
        try {
            // Minimal JSON parser — just enough for RPC params
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return null
            val result = LinkedHashMap<CharSequence, Any?>()
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

    private fun successResponse(id: Any?, result: Any?): CharSequence {
        val idStr = when (id) { null -> "null"; is CharSequence -> "\"$id\""; else -> id.toString() }
        val resultStr = jsonValue(result)
        return """{"jsonrpc":"2.0","id":$idStr,"result":$resultStr}"""
    }

    private fun errorResponse(id: Any?, code: Int, message: CharSequence): CharSequence {
        val idStr = when (id) { null -> "null"; is CharSequence -> "\"$id\""; else -> id.toString() }
        return """{"jsonrpc":"2.0","id":$idStr,"error":{"code":$code,"message":"$message"}}"""
    }

    private fun jsonValue(v: Any?): CharSequence = when (v) {
        null -> "null"
        is CharSequence -> "\"$v\""
        is Number -> v.toString()
        is Boolean -> v.toString()
        is Map<*, *> -> "{" + v.entries.joinToString(",") { (k, vv) -> "\"$k\":${jsonValue(vv)}" } + "}"
        is List<*> -> "[" + v.joinToString(",") { jsonValue(it) } + "]"
        else -> "\"${v}\""
    }

    private fun skipWhitespace(s: CharSequence, pos: Int): Int {
        var p = pos
        while (p < s.length && s[p].isWhitespace()) p++
        return p
    }

    private fun skipArray(s: CharSequence, pos: Int): Int {
        var depth = 1; var p = pos + 1
        while (p < s.length && depth > 0) { when (s[p]) { '[' -> depth++; ']' -> depth-- }; p++ }
        return p
    }

    private fun skipObject(s: CharSequence, pos: Int): Int {
        var depth = 1; var p = pos + 1
        while (p < s.length && depth > 0) { when (s[p]) { '{' -> depth++; '}' -> depth-- }; p++ }
        return p
    }
}
