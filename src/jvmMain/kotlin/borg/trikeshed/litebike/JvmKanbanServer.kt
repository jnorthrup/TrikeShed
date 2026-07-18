@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.litebike

import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.TraitSpace
import borg.trikeshed.context.nuid.Workgroup
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.charset.StandardCharsets
import java.nio.file.Files as NioFiles
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * JvmKanbanServer — the daemon that owns one [LitebikeListenerElement] and
 * registers workers for the protocols we serve.
 *
 * There is exactly ONE bind (in [JvmLitebikeBindAdapter]); everything
 * downstream is CCEK. Workers consume channel slots from the listener
 * and run their side of the request.
 *
 * The HTTP layer is *not* a `com.sun.net.httpserver` or a ktor app; it
 * is the HTTP branch of [LitebikeListenerElement.fanoutChannels]. Its job:
 * parse the request line + headers, derive a NUID, dispatch it through
 * [NuidFanoutElement], route to `/api/health`, `/api/cap`, `/api/board`,
 * `/api/submit`, etc., and write back the JSON.
 *
 * The litebike taxonomy file (`borg.trikeshed.litebike.taxonomy.Taxonomy.kt`)
 * is the wire-stable identifier table — same numeric IDs as the Rust
 * side — and is the input that the ProtocolDetector uses to choose the
 * per-request channel.
 */
object JvmKanbanServer {

    /**
     * Wire-level HTTP response built by the HTTP worker. Serialized
     * back through the listener as bytes on the same connection.
     */
    data class HttpResponse(
        val status: Int,
        val body: String,
        val contentType: String = "application/json; charset=utf-8",
    )

    /**
     * Marker carrier passed between workers when a request must cross
     * the listener boundary (e.g. submit → board projection).
     */
    data class HttpWorkItem(
        val requestBytes: ByteArray,
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        var port = 8888
        var donor: String? = null
        val i = args.iterator()
        while (i.hasNext()) {
            when (val a = i.next()) {
                "--port"  -> if (i.hasNext()) port = i.next().toIntOrNull() ?: 8888
                "--donor" -> if (i.hasNext()) donor = i.next()
                "-h", "--help" -> {
                    System.err.println("Usage: JvmKanbanServer [--port N] [--donor path]")
                    exitProcess(2)
                }
            }
        }
        runBlocking { run(port, donor) }
    }

    suspend fun run(port: Int, donorPath: String?) {
        val serverJob = SupervisorJob()
        val scope = CoroutineScope(serverJob + Dispatchers.Default)
        val listener = LitebikeListenerElement(parentJob = serverJob).also { it.open() }
        val fanout = NuidFanoutElement(parentJob = serverJob).also { it.open() }

        val processWorkgroup = Workgroup(
            name = "kanban-process-local",
            scope = Subnet.local,
            traits = traitSpaceOf(Capability.ProcessAll),
        )
        val casWorkgroup = Workgroup(
            name = "kanban-cas-local",
            scope = Subnet.local,
            traits = traitSpaceOf(Capability.CasAll),
        )
        val wireprotoWorkgroup = Workgroup(
            name = "kanban-wireproto-lan",
            scope = Subnet.lanLocalhost,
            traits = traitSpaceOf(Capability.WireprotoAll),
        )
        fanout.register(processWorkgroup)
        fanout.register(casWorkgroup)
        fanout.register(wireprotoWorkgroup)
        fanout.activate()
        listOf(processWorkgroup, casWorkgroup, wireprotoWorkgroup).forEach { workgroup ->
            val slot = requireNotNull(fanout.slotOf(workgroup.name))
            scope.launch {
                try {
                    while (true) {
                        // Workgroup reducers attach at this seam. Drain every
                        // accepted claim now so production fanout has live workers.
                        slot.consume()
                    }
                } catch (_: ClosedReceiveChannelException) {
                    // Fanout closed during structured shutdown.
                }
            }
        }

        // Register Http + Json + Socks5 + Tls + Bonjour + Upnp.
        // IDs are TrikeShed-local conventions (Taxonomy.kt), not FFI-stable.
        listOf(
            Protocol.Http,
            Protocol.Json,
            Protocol.Socks5,
            Protocol.Tls,
            Protocol.Bonjour,
            Protocol.Upnp,
        ).forEach { listener.register(it) }
        listener.activate()

        // R05 — register the connection registry. The bind adapter
        // calls registry.register(channel) on every accepted socket and
        // receives a connectionId; it then stamps the same id onto a
        // sequence→connection side map so the HTTP worker (which only
        // sees ChannelMessage.sequenceId) can write back through the
        // originating socket.
        val connections = ConnectionRegistry()

        // Join multicast groups so Bonjour/UPnP datagrams flow into the listener.
        // This is the only UDP bind in the daemon.
        // R04 — keep the handles (Job + MembershipKey) so the read loops can
        // be cancelled on shutdown. The previous version dropped them on the
        // floor and leaked CoroutineScopes.
        val multicastHandles = try {
            JvmMulticastAdapter.joinAll(listener)
        } catch (t: Throwable) {
            System.err.println("multicast join failed: ${t.message}")
            emptyList()
        }
        System.err.println("multicast joined: ${multicastHandles.size} groups")

        // R04 — shutdown hook cancels read loops + drops multicast memberships
        // so the daemon doesn't leak DatagramChannels past JVM exit.
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { JvmMulticastAdapter.close() }
            runCatching { connections.closeAll() }
            runCatching {
                runBlocking {
                    listener.close()
                    fanout.close()
                }
            }
            serverJob.cancel()
        })

        if (donorPath != null && NioFiles.exists(Paths.get(donorPath))) {
            // Replay donor on startup; mirrors prior daemon behavior.
            try {
                ForgeKanbanIngest.persistMarkdown("jim", donorPath)
                System.err.println("donor replayed: $donorPath")
            } catch (t: Throwable) {
                System.err.println("donor replay failed: ${t.message}")
            }
        }

        // One structured fanout consumer per registered protocol slot. HTTP
        // requests cross the NUID dispatcher before routing; JSON, Socks5,
        // and TLS are deliberately drained even when no reducer is mounted.
        scope.launch {
            listener.fanoutChannels { protocol, msg ->
                when (protocol) {
                    Protocol.Http -> handleHttpMessage(listener, fanout, connections, msg)
                    Protocol.Bonjour -> emitMulticastLine(
                        listener,
                        "bonjour",
                        parseBonjourDatagram(msg.payload),
                    )
                    Protocol.Upnp -> emitMulticastLine(
                        listener,
                        "upnp",
                        parseSsdpDatagram(msg.payload),
                    )
                    else -> Unit
                }
                true
            }
        }

        // multicastHandles is held so the shutdown hook above can see
        // the live membership count via the adapter's internal table
        // (JvmMulticastAdapter.close() walks every registered handle).
        // No further use here — the variable documents the wiring.
        @Suppress("UNUSED_VARIABLE") val keptForDocs = multicastHandles

        System.err.println("trikeshed-kanban: listening on :$port  donor=${donorPath ?: "<none>"}")
        System.err.println("Endpoints (CCEK): GET /api/health /api/cap /api/board POST /api/submit /api/donor")

        // Bind happens here — only place outside the worker scope that
        // opens a socket. The adapter resumes this coroutine on close.
        JvmLitebikeBindAdapter.bindAndServe(listener, port = port, connections = connections)
    }

    // ── routes (single worker, hand-rolled) ──────────────────────────────

    private fun routeHttp(payload: ByteArray): HttpResponse {
        val text = String(payload, StandardCharsets.UTF_8)
        val firstLine = text.lineSequence().firstOrNull() ?: ""
        val parts = firstLine.split(' ')
        val method = parts.getOrNull(0) ?: "GET"
        val path = parts.getOrNull(1) ?: "/"
        return when (path) {
            "/api/health" -> HttpResponse(200, """{"ok":true,"server":"kanban","now":${System.currentTimeMillis()}}""")
            "/api/cap"    -> HttpResponse(200, """{"protocols":["Http","Json","Socks5","Tls","Bonjour","Upnp"],"capabilities":["Process@local","Cas@local","Wireproto@lan.localhost"]}""")
            "/api/board"  -> HttpResponse(200, boardJson())
            "/api/submit" -> if (method == "POST") submit(text) else HttpResponse(405, """{"error":"method_not_allowed"}""")
            "/api/donor"  -> if (method == "POST") submit(text) else HttpResponse(405, """{"error":"method_not_allowed"}""")
            "/"           -> HttpResponse(200, "<html><body>Forge litebike listener — see /api/health</body></html>", "text/html; charset=utf-8")
            else           -> HttpResponse(404, """{"error":"not_found","path":"$path"}""")
        }
    }

    private fun boardJson(): String = runCatching {
        val reduction = ForgeKanbanIngest.load("jim")
        JsonSupport.stringify(
            linkedMapOf(
                "title" to reduction.source.title,
                "userId" to reduction.source.userId,
                "items" to reduction.board.cards.sortedBy { it.order }.map { card ->
                    linkedMapOf(
                        "id" to card.id.value,
                        "title" to card.title,
                        "status" to card.columnId.value,
                    )
                },
                "correlations" to reduction.correlations.size,
            )
        )
    }.getOrElse { """{"error":"load_failed","reason":"${it.message}"}""" }

    private fun submit(body: String): HttpResponse {
        val payload = body.substringAfter("\r\n\r\n", "").ifEmpty {
            // Some clients use \n separators; tolerate that.
            body.substringAfter("\n\n", "")
        }
        if (payload.isBlank()) return HttpResponse(400, """{"error":"empty_body"}""")
        return runCatching {
            val tmp = "/tmp/hi"
            writeStringJvm(tmp, payload)
            val reduction = ForgeKanbanIngest.persistMarkdown("jim", tmp)
            HttpResponse(
                201,
                JsonSupport.stringify(
                    linkedMapOf(
                        "ok" to true,
                        "correlations" to reduction.correlations.size,
                        "firstCausalKey" to (reduction.correlations.firstOrNull()?.causalKey ?: ""),
                    )
                ),
            )
        }.getOrElse { HttpResponse(500, """{"error":"submit_failed","reason":"${it.message}"}""") }
    }

    private fun statusReason(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    /** Convert an HTTP request into the NUID bearer used by fanout.
     *  The request line selects the Wireproto trait, the full request
     *  supplies a derived bearer nonce, and network ingress starts at LAN. */
    private fun nuidForRequest(request: HttpWorkItem): Nuid = nuid(
        cap = Capability.Wireproto("http:${request.method.lowercase()}:${request.path}"),
        nonce = Nonce.Derived(
            buildString {
                append(request.method).append(' ').append(request.path).append('\n')
                request.headers.toSortedMap().forEach { (key, value) ->
                    append(key.lowercase()).append(':').append(value).append('\n')
                }
                append(String(request.requestBytes, StandardCharsets.UTF_8))
            },
        ),
        subnet = Subnet.lanLocalhost,
    )

    private fun parseHttpWorkItem(payload: ByteArray): HttpWorkItem {
        val text = String(payload, StandardCharsets.UTF_8)
        val lines = text.split("\r\n", "\n")
        val firstLine = lines.firstOrNull().orEmpty()
        val parts = firstLine.split(' ', limit = 3)
        val headers = lines.drop(1)
            .takeWhile { it.isNotBlank() }
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
            .toMap()
        return HttpWorkItem(
            requestBytes = payload,
            method = parts.getOrNull(0) ?: "GET",
            path = parts.getOrNull(1) ?: "/",
            headers = headers,
        )
    }

    private suspend fun handleHttpMessage(
        listener: LitebikeListenerElement,
        fanout: NuidFanoutElement,
        connections: ConnectionRegistry,
        msg: LitebikeListenerElement.ChannelMessage,
    ) {
        val request = parseHttpWorkItem(msg.payload)
        val dispatch = fanout.dispatch(nuidForRequest(request), request, timeoutMillis = 50L)
        val response = routeHttp(request.requestBytes)
        if (dispatch.winner == null) {
            System.err.println("http NUID dispatch unclaimed: claimId=${dispatch.claimId} path=${request.path}")
        }
        val responseBytes = buildString {
            append("HTTP/1.1 ${response.status} ${statusReason(response.status)}\r\n")
            append("Content-Length: ${response.body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Access-Control-Allow-Origin: *\r\n\r\n")
            append(response.body)
        }.toByteArray(StandardCharsets.UTF_8)
        val connId = connections.consumeForSequence(msg.sequenceId)
        val written = if (connId != null) {
            connections.write(connId, responseBytes)
        } else {
            listener.accept(Protocol.Json, responseBytes)
            true
        }
        if (!written) {
            System.err.println("http write failed for sequence ${msg.sequenceId}")
        }
    }

    // ── R02 — multicast slot consumer helpers ────────────────────────────

    /**
     * Emit one JSON line per datagram onto the Json slot. Reuses the
     * existing JsonSupport to keep escaping consistent with the
     * /api/board and /api/submit handlers.
     */
    private suspend fun emitMulticastLine(
        listener: LitebikeListenerElement,
        source: String,
        body: Map<String, Any?>,
    ) {
        val merged = linkedMapOf<String, Any?>("src" to source, "ts" to System.currentTimeMillis())
        merged.putAll(body)
        val line = JsonSupport.stringify(merged).toByteArray(StandardCharsets.UTF_8)
        listener.accept(Protocol.Json, line)
    }

    /**
     * Minimal mDNS / DNS-SD parse — just enough to surface a service
     * name and a sender note. Skips DNS compression-pointer resolution
     * (RFC 1035 §4.1.4) — if a label starts with 0xC0 we treat it as
     * "compressed" and stop. No full record decoding: the goal is
     * a service-name hint per query, not a wire-faithful parser.
     */
    private fun parseBonjourDatagram(payload: ByteArray): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("bytes" to payload.size)
        if (payload.size < 12) {
            out["note"] = "too_short"
            return out
        }
        val qdcount = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        out["qd"] = qdcount
        // Walk the first question's QNAME label sequence starting at offset 12.
        var off = 12
        val labels = mutableListOf<String>()
        while (off < payload.size && labels.size < 8) {
            val len = payload[off].toInt() and 0xFF
            if (len == 0) { off++; break }
            // Compression pointer (top two bits set) — stop here, no inline decode.
            if ((len and 0xC0) != 0) {
                out["compressed"] = true
                off++
                break
            }
            if (off + 1 + len > payload.size) break
            val labelBytes = payload.copyOfRange(off + 1, off + 1 + len)
            labels += String(labelBytes, StandardCharsets.UTF_8)
            off += 1 + len
        }
        if (labels.isNotEmpty()) {
            out["name"] = labels.joinToString(".")
        }
        // QTYPE/QCLASS (4 bytes) if present
        if (off + 4 <= payload.size) {
            val qtype = ((payload[off].toInt() and 0xFF) shl 8) or (payload[off + 1].toInt() and 0xFF)
            out["qtype"] = qtype
        }
        out["note"] = "first_question"
        return out
    }

    /**
     * Minimal SSDP / UPnP parse — extract the first method/response
     * line and one header value (ST/NT/USN/CACHE-CONTROL — whichever
     * appears first). SSDP packets are HTTP-like text; this reads
     * up to the first CRLF and the first matching header line.
     */
    private fun parseSsdpDatagram(payload: ByteArray): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("bytes" to payload.size)
        val text = runCatching { String(payload, StandardCharsets.US_ASCII) }
            .getOrElse { "<binary:${payload.size}>" }
        val firstLine = text.lineSequence().firstOrNull().orEmpty().trim()
        if (firstLine.isNotEmpty()) {
            out["method"] = firstLine.substringBefore(' ')
            out["raw"] = firstLine.take(160)
        }
        val st = Regex("(?im)^(?:ST|NT|USN|CACHE-CONTROL):\\s*(.+)\\s*$").find(text)
        if (st != null) out["header"] = st.groupValues[1].take(160)
        out["note"] = if (firstLine.startsWith("M-SEARCH")) "msearch"
            else if (firstLine.startsWith("NOTIFY")) "notify"
            else if (firstLine.startsWith("HTTP/1.")) "response"
            else "other"
        return out
    }
}

private fun traitSpaceOf(vararg capabilities: Capability): TraitSpace = TraitSpace {
    capabilities.size j { index -> capabilities[index] }
}

/**
 * ConnectionRegistry — JVM-side per-connection state for the litebike
 * bind adapter. R05: the bind adapter hands each accepted
 * [AsynchronousSocketChannel] to [register]; the HTTP worker looks up
 * the originating channel by [ChannelMessage.sequenceId][LitebikeListenerElement.ChannelMessage.sequenceId]
 * via [consumeForSequence] and writes the response back via [write].
 *
 * Why a JVM-only registry? [LitebikeListenerElement.ChannelMessage]
 * lives in commonMain and adding a JVM-specific socket field there
 * would poison the KMP source set. The sequence id already exists in
 * the message; we map it to a socket in JVM space.
 *
 * Concurrency: backed by [ConcurrentHashMap]; safe under arbitrary
 * reader/writer concurrency. Writes are issued on the JVM NIO group
 * via [AsynchronousSocketChannel.write], so workers never block on I/O.
 */
class ConnectionRegistry {

    private val nextId = AtomicLong(0L)

    private data class Entry(
        val channel: AsynchronousSocketChannel,
        /** SequenceId of the in-flight request, if any. */
        @Volatile var pendingSequenceId: Long? = null,
    )

    private val connections: ConcurrentHashMap<Long, Entry> = ConcurrentHashMap()

    /**
     * Register a freshly accepted channel. Returns a stable
     * connectionId that the caller can later use with [write] or
     * [unregister].
     */
    fun register(channel: AsynchronousSocketChannel): Long {
        val id = nextId.incrementAndGet()
        connections[id] = Entry(channel)
        return id
    }

    /**
     * Stamp a [sequenceId] onto an existing connection so the worker
     * can find the originating channel via [consumeForSequence].
     */
    fun attachSequence(connectionId: Long, sequenceId: Long) {
        val entry = connections[connectionId] ?: return
        entry.pendingSequenceId = sequenceId
    }

    /**
     * Pop the [connectionId] associated with [sequenceId]. Returns
     * null if no mapping exists (worker message did not originate
     * from a registered socket, e.g. an in-process emit).
     *
     * The mapping is one-shot: the next write is expected to be the
     * response. Callers that need to keep the connection alive for
     * further traffic should not consume here.
     */
    fun consumeForSequence(sequenceId: Long): Long? {
        for ((id, entry) in connections) {
            if (entry.pendingSequenceId == sequenceId) {
                entry.pendingSequenceId = null
                return id
            }
        }
        return null
    }

    /**
     * Write [bytes] back through the channel registered as
     * [connectionId]. Returns true on success, false if the write
     * completes with a negative result (peer closed) or throws.
     *
     * Asynchronous — completes via the supplied [CompletionHandler]
     * or the channel group's default executor. Does not block the
     * calling worker.
     *
     * On completion the channel is closed and unregistered; this is
     * HTTP/1.1 per-connection semantics. If you want keep-alive,
     * replace the `unregister` call with a reset of `pendingSequenceId`.
     */
    fun write(connectionId: Long, bytes: ByteArray): Boolean {
        val entry = connections[connectionId] ?: return false
        val channel = entry.channel
        val buf = ByteBuffer.wrap(bytes)
        val done = CompletableDeferred<Boolean>()
        try {
            channel.write(buf, null, object : CompletionHandler<Int, Any?> {
                override fun completed(written: Int, attached: Any?) {
                    done.complete(written >= 0)
                }
                override fun failed(t: Throwable, attached: Any?) {
                    done.complete(false)
                }
            })
        } catch (t: Throwable) {
            // Channel already closed or in invalid state.
            unregister(connectionId)
            return false
        }
        // Block briefly for the write to finish — the worker is on a
        // Default dispatcher so a short park here is fine, and we need
        // the boolean to decide whether to log failure. We don't want
        // to spin: the JDK NIO group completes writes in microseconds
        // for local sockets, and the daemon doesn't have latency SLOs.
        val ok = runBlocking { done.await() }
        unregister(connectionId)
        return ok
    }

    /**
     * Drop [connectionId] from the registry and close the underlying
     * channel. Idempotent.
     */
    fun unregister(connectionId: Long) {
        val entry = connections.remove(connectionId) ?: return
        runCatching { entry.channel.close() }
    }

    /**
     * Close every registered channel. Called from the JVM shutdown
     * hook so the daemon doesn't leak sockets on exit.
     */
    fun closeAll() {
        for (id in connections.keys.toList()) unregister(id)
    }

    /** Live connection count — useful for `/api/cap` and tests. */
    fun activeCount(): Int = connections.size
}

private fun writeStringJvm(path: String, text: String) {
    val p = Paths.get(path)
    if (p.parent != null) NioFiles.createDirectories(p.parent)
    NioFiles.writeString(
        p, text,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
    )
}
