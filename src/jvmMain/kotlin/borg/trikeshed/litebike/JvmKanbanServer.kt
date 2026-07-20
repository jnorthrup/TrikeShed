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

    /** Wire-level HTTP response built by the HTTP worker. Serialized back through the listener as bytes on the same connection. */
    data class HttpResponse(
        val status: Int,
        val body: String,
        val contentType: String = "application/json; charset=utf-8",
    )

    /** Marker carrier passed between workers when a request must cross the listener boundary (e.g. submit → board projection). */
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
        var donorFormat: String = "md"
        val i = args.iterator()
        while (i.hasNext()) {
            when (val a = i.next()) {
                "--port"  -> if (i.hasNext()) port = i.next().toIntOrNull() ?: 8888
                "--donor" -> if (i.hasNext()) donor = i.next()
                "--donor-format" -> if (i.hasNext()) donorFormat = i.next()
                "-h", "--help" -> {
                    System.err.println("Usage: JvmKanbanServer [--port N] [--donor path] [--donor-format md|sqlite]")
                    exitProcess(2)
                }
            }
        }
        runBlocking { run(port, donor, donorFormat) }
    }

    suspend fun run(port: Int, donorPath: String?, donorFormat: String = "md") {
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
                val donor = Paths.get(donorPath)
                val ingestPath = if (borg.trikeshed.kanban.JvmTikaIngestAdapter.isTikaCandidate(donor)) {
                    // Non-markdown donor (PDF/DOCX/image) — extract text via Tika
                    // (tika4all tweaked config: Tesseract OCR + ffmpeg preprocessing).
                    val md = borg.trikeshed.kanban.JvmTikaIngestAdapter.extractToMarkdown(donor)
                    val tmp = NioFiles.createTempFile("tika-donor", ".md")
                    NioFiles.writeString(tmp, md)
                    tmp.toString()
                } else {
                    donorPath
                }
                borg.trikeshed.forge.donor.HermesDonorTrace.ingestDonor("jim", donorFormat, donorPath.toString())
                System.err.println("donor replayed: $donorPath")
            } catch (t: Throwable) {
                System.err.println("donor replay failed: ${t.message}")
            }
        }

        // The HTTP worker consumes the httpSlot. Each accepted byte
        // stream is a request; we route on the request path.
        val httpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // The HTTP handler logic is handled by the wireproto workgroup.
        httpScope.launch {
            val slot = fanout.slotOf("wireproto") ?: return@launch
            while (true) {
                val claim = slot.consume()
                val payload = claim.payload as? ByteArray ?: continue
                val resp = routeHttp(payload)
                val out = buildString {
                    append("HTTP/1.1 ${resp.status} ${statusReason(resp.status)}\r\n")
                    append("Content-Length: ${resp.body.toByteArray(StandardCharsets.UTF_8).size}\r\n")
                    append("Content-Type: ${resp.contentType}\r\n")
                    append("Access-Control-Allow-Origin: *\r\n\r\n")
                    append(resp.body)
                }
                val outBytes = out.toByteArray(StandardCharsets.UTF_8)
                // Surface the response on the Json protocol slot (downstream consumers)
                listener.accept(Protocol.Json, out.toByteArray(StandardCharsets.UTF_8))
            }
        }

        // Fanout channels
        scope.launch {
            listener.fanoutChannels { protocol, msg ->
                System.err.println("fanout: $protocol seq=${msg.sequenceId} ${msg.payload.size} bytes")
                if (protocol == Protocol.Http) {
                    val text = String(msg.payload, StandardCharsets.UTF_8)
                    if (text.startsWith("GET /api/stream ") && text.contains("Upgrade: websocket", ignoreCase = true)) {
                        val keyLine = text.lineSequence().find { it.startsWith("Sec-WebSocket-Key: ", ignoreCase = true) }
                        if (keyLine != null && msg.respond != null) {
                            val key = keyLine.substringAfter(":").trim()
                            val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                            val digest = java.security.MessageDigest.getInstance("SHA-1")
                            val acceptHash = java.util.Base64.getEncoder().encodeToString(digest.digest((key + magic).toByteArray()))

                            val response = buildString {
                                append("HTTP/1.1 101 Switching Protocols\r\n")
                                append("Upgrade: websocket\r\n")
                                append("Connection: Upgrade\r\n")
                                append("Sec-WebSocket-Accept: $acceptHash\r\n")
                                append("\r\n")
                            }
                            msg.respond.invoke(response.toByteArray(StandardCharsets.UTF_8))

                            scope.launch {
                                try {
                                    borg.trikeshed.forge.server.KanbanPushBus.patches.collect { patch ->
                                        val frame = borg.trikeshed.ws.WebSocketFrame.buildFrame(
                                            opcode = borg.trikeshed.ws.WebSocketFrame.OpCode.TEXT,
                                            payload = patch.toByteArray(StandardCharsets.UTF_8)
                                        )
                                        msg.respond.invoke(frame)
                                    }
                                } catch (e: Exception) {
                                    System.err.println("ws push disconnected: ${e.message}")
                                }
                            }
                        }
                    }
                }
                true // keep listening
            }
        }

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


            "/"           -> HttpResponse(200, "<html><body>Forge litebike listener — see /api/health</body></html>", "text/html; charset=utf-8")
            else -> if (path.startsWith("/api/donor")) { if (method == "POST") submitDonor(path) else HttpResponse(405, """{"error":"method_not_allowed"}""") } else { HttpResponse(404, """{"error":"not_found","path":"$path"}""") }
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

            // Push board update to connected WS clients
            borg.trikeshed.forge.server.KanbanPushBus.publish(boardJson())

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

    private fun submitDonor(path: String): HttpResponse {
        val query = path.substringAfter("?", "")
        val format = query.split("&").find { it.startsWith("format=") }?.substringAfter("format=") ?: "sqlite"
        val donorPathStr = Paths.get(System.getProperty("user.home"), ".hermes", "kanban.db").toString()
        return runCatching {
            val reduction = borg.trikeshed.forge.donor.HermesDonorTrace.ingestDonor("jim", format, donorPathStr)
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
