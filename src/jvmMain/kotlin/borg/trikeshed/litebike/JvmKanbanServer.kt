@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.litebike

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.forge.persistence.CausalWal
import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.graph.causalGraphNode
import borg.trikeshed.kanban.ForgeKanbanIngest
import metrics.FlywheelMetrics
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.TraitSpace
import borg.trikeshed.context.nuid.Workgroup
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import borg.trikeshed.jules.JulesRestClient
import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
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
 * `/api/metrics`, `/api/jules/surface`, etc., and write back the JSON.
 *
 * The litebike taxonomy file (`borg.trikeshed.litebike.taxonomy.Taxonomy.kt`)
 * is the wire-stable identifier table — same numeric IDs as the Rust
 * side — and is the input that the ProtocolDetector uses to choose the
 * per-request channel.
 */
/** A fallthrough route: answers a request this server does not own, or returns null to decline. Streaming routes receive `respond`. */
typealias ExtraRoute = suspend (method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?) -> JvmKanbanServer.HttpResponse?

/**
 * A binary-safe route: receives the raw request bytes (head + body) and may answer with bytes.
 * Tried before the static assets and the Forge shell so a store-hosted app can own `/`.
 * Streaming entries (see `streamingPaths`) receive `respond` and write their own headers.
 */
typealias RawRoute = suspend (method: String, path: String, payload: ByteArray, respond: (suspend (ByteArray) -> Unit)?) -> JvmKanbanServer.HttpResponse?

class JvmKanbanServer(
    /** FlywheelDriver for live Jules surface and cycle report. Nullable so the server can start before the driver. */
    private val flywheelDriver: borg.trikeshed.jules.FlywheelDriver? = null,
    /** Extension seam: tried after the built-in routes and static assets, first non-null wins (BlackboardWire, VmWire, …). */
    private val extraRoutes: List<ExtraRoute> = emptyList(),
    /**
     * Paths an extra/raw route streams on (SSE, `_changes?feed=`): headers + body go straight to
     * `respond`, no Content-Length. An entry without '?' matches the path exactly; an entry with
     * '?' matches the path exactly and requires the query to contain the part after '?'.
     */
    private val streamingPaths: Set<String> = emptySet(),
    /** Binary-safe routes (CouchWire). Tried after `/api/…` built-ins, before static assets and the shell. */
    private val rawRoutes: List<RawRoute> = emptyList(),
    /** Listener batch/reassembly unit: request cap is `maxRequestBatch * 1024` bytes (64 KiB at the default). */
    private val maxRequestBatch: Int = 64,
) {
    /** SSE event stream path — collects FlywheelDriver.events as text/event-stream. SURFACE_TTL_MS bounds /api/jules/surface cache life. */
    private companion object {
        private const val SSE_PATH = "/api/jules/events"
        private const val SURFACE_TTL_MS = 10_000L
    }

    // Mutable driver slot so the daemon can swap in the driver after construction.
    private var _driver: borg.trikeshed.jules.FlywheelDriver? = flywheelDriver

    /** Detached scope for best-effort surface fetches: a timed-out fetch is abandoned, not awaited. */
    private val surfaceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default,
    )

    /**
     * Last computed /api/jules/surface body with its birth time. The GUI polls
     * continuously and each recompute can cost up to 8s of the single HTTP
     * worker's time; a short-lived cache keeps polls O(1) so requests never
     * queue behind each other. Sessions in the cached body are refreshed by
     * the flywheel cycle (30s cadence), so a 10s TTL never serves data staler
     * than the cycle itself.
     */
    @Volatile private var surfaceCache: Pair<Long, String>? = null

    /** Atomically swap the live driver reference. Called from OroborosDaemon after FlywheelDriver construction. */
    fun attachDriver(driver: borg.trikeshed.jules.FlywheelDriver) {
        _driver = driver
    }

    private val causalWal = CausalWal(File(".causal.wal"))
    private val graphIndex = CausalGraphNodeIndex()

    /** Wire-level HTTP response built by the HTTP worker. Serialized back through the listener as bytes on the same connection. */
    data class HttpResponse(
        val status: Int,
        val body: String,
        val contentType: String = "application/json; charset=utf-8",
        /** Binary payload; when set it is written instead of [body]. */
        val bytes: ByteArray? = null,
    ) {
        val payloadBytes: ByteArray get() = bytes ?: body.toByteArray(StandardCharsets.UTF_8)
    }

    /** Marker carrier passed between workers when a request must cross the listener boundary (e.g. submit → board projection). */
    data class HttpWorkItem(
        val requestBytes: ByteArray,
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

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
        runBlocking { JvmKanbanServer().run(port, donor) }
    }

    suspend fun run(port: Int, donorPath: String?) {
        replayCausalWal()
        val serverJob = SupervisorJob()
        val scope = CoroutineScope(serverJob + Dispatchers.Default)
        val listener = LitebikeListenerElement(parentJob = serverJob, maxBatch = maxRequestBatch).also { it.open() }
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

        // Register ALL protocols from the taxonomy before activate() —
        // activate() calls verifyRegistry() which requires every Protocol
        // entry to have a registered slot.
        Protocol.entries.forEach { listener.register(it) }
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
            serverJob.cancel()
        })

        if (donorPath != null && NioFiles.exists(Paths.get(donorPath))) {
            // Replay donor on startup; mirrors prior daemon behavior.
            try {
                val donor = Paths.get(donorPath)
                val ingestPath = if (borg.trikeshed.kanban.ingestRoute(donor.fileName.toString()) != borg.trikeshed.kanban.IngestRoute.Text) {
                    // Non-markdown donor (PDF/DOCX/image) — extract text via Tika
                    // (tika4all tweaked config: Tesseract OCR + ffmpeg preprocessing).
                    val md = borg.trikeshed.kanban.JvmTikaIngestAdapter.extractToMarkdown(donor)
                    val tmp = NioFiles.createTempFile("tika-donor", ".md")
                    NioFiles.writeString(tmp, md)
                    tmp.toString()
                } else {
                    donorPath
                }
                ForgeKanbanIngest.persistMarkdown("jim", ingestPath)
                System.err.println("donor replayed: $donorPath")
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                System.err.println("donor replay failed: ${t.message}")
            }
        }

        // The HTTP worker consumes the httpSlot. Each accepted byte
        // stream is a request; we route on the request path.
        val httpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // The HTTP handler logic is handled by the Protocol.Http slot.
        httpScope.launch {
            val httpSlot = listener.register(Protocol.Http)
            while (true) {
                val msg = httpSlot.consume()
                val payload = msg.payload

                val wireNuid = nuid(Capability.Wireproto("http"), Nonce.RandomBytes(), Subnet.lanLocalhost)
                fanout.dispatch(wireNuid, payload)

                // SSE — intercept before routeHttp to stream events without
                // Content-Length (SSE has no end, client closes on disconnect).
                val text = String(payload, StandardCharsets.UTF_8)
                val reqLine = text.lineSequence().firstOrNull() ?: ""
                val reqParts = reqLine.split(' ')
                val reqPath = reqParts.getOrNull(1) ?: "/"
                val isSse = reqPath == SSE_PATH &&
                    (reqParts.getOrNull(0) == "GET") &&
                    (text.lineSequence().any { it.startsWith("Accept:") && it.contains("text/event-stream") } ||
                        text.contains("Accept: */*"))

                val isExtraStream = reqParts.getOrNull(0) == "GET" && isStreamingRequest(reqPath)
                if (isExtraStream) {
                    // Extension streams (/blackboard/facts, /api/vm/events, _changes?feed=): the route writes its own headers and events.
                    val respond: suspend (ByteArray) -> Unit = { bytes -> msg.respond?.invoke(bytes) }
                    scope.launch {
                        try {
                            var handled = false
                            for (route in rawRoutes) { route("GET", reqPath, payload, respond) ?: continue; handled = true; break }
                            if (!handled) for (route in extraRoutes) { route("GET", reqPath, text, respond) ?: continue; break }
                        } catch (_: kotlinx.coroutines.CancellationException) {
                        } catch (t: Throwable) {
                            System.err.println("extra stream error: ${t.message}")
                        }
                    }
                    continue
                }

                if (isSse) {
                    val driver = _driver
                    if (driver == null) {
                        val err = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n"
                        msg.respond?.invoke(err.toByteArray(StandardCharsets.UTF_8))
                    } else {
                        // Send SSE headers immediately — no Content-Length, connection: keep-alive
                        val headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: keep-alive\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n"
                        msg.respond?.invoke(headers.toByteArray(StandardCharsets.UTF_8))

                        // Stream events until client disconnects
                        val driver0 = driver
                        scope.launch {
                            try {
                                driver0.events.collect { event ->
                                    val data = "data: ${JsonSupport.stringify(event)}\r\n\r\n"
                                    try {
                                        msg.respond?.invoke(data.toByteArray(StandardCharsets.UTF_8))
                                    } catch (_: Throwable) {
                                        // Connection dropped — normal SSE termination
                                        throw kotlinx.coroutines.CancellationException("SSE client disconnected")
                                    }
                                }
                            } catch (_: kotlinx.coroutines.CancellationException) {
                                // Client disconnected — expected
                            } catch (t: Throwable) {
                                System.err.println("SSE stream error: ${t.message}")
                            }
                        }
                    }
                    // Consume but do NOT call routeHttp for SSE
                    continue
                }

                // Each connection is handled in its own coroutine: the slot must
                // re-arm immediately so an idle keep-alive connection (browsers
                // hold them open) cannot serialize — and so freeze — every other
                // client behind it. The single-consumer slot stays the only
                // reader; handling is forked per message.
                httpScope.launch {
                    val resp = routeHttp(payload)
                    val payloadOut = resp.payloadBytes
                    val head = buildString {
                        append("HTTP/1.1 ${resp.status} ${statusReason(resp.status)}\r\n")
                        append("Content-Length: ${payloadOut.size}\r\n")
                        append("Content-Type: ${resp.contentType}\r\n")
                        append("Access-Control-Allow-Origin: *\r\n\r\n")
                    }.toByteArray(StandardCharsets.UTF_8)
                    val outBytes = head + payloadOut
                    // Write back through the listener's response callback
                    runCatching { msg.respond?.invoke(outBytes) }
                }
            }
        }

        // NOTE: no fanoutChannels consumer here. fanoutChannels() competes with
        // the httpSlot worker for the same per-protocol Channel — a Channel hands
        // each message to exactly ONE receiver, so a second consumer silently
        // stole every other HTTP request (GUI freeze: polls randomly unanswered).
        // Inbound-traffic observability is the LitebikeFanoutEvent stream.

        System.err.println("trikeshed-kanban: listening on :$port  donor=${donorPath ?: "<none>"}")
        System.err.println("Endpoints (CCEK): GET / (Forge PWA) /api/health /api/cap /api/board /api/metrics /api/jules/surface /api/jules/events POST /api/submit /api/donor /api/invoke")

        // Bind happens here — only place outside the worker scope that
        // opens a socket. The adapter resumes this coroutine on close.
        try {
            JvmLitebikeBindAdapter.bindAndServe(listener, port = port, connections = connections)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                listener.close()
                fanout.close()
            }
        }
    }

    // ── routes (single worker, hand-rolled) ──────────────────────────────

    private fun isStreamingRequest(reqPath: String): Boolean {
        val p = reqPath.substringBefore('?')
        val q = reqPath.substringAfter('?', "")
        return streamingPaths.any { entry ->
            if ('?' in entry) p == entry.substringBefore('?') && q.contains(entry.substringAfter('?')) else p == entry
        }
    }

    internal suspend fun routeHttp(payload: ByteArray): HttpResponse {
        val text = String(payload, StandardCharsets.UTF_8)
        val firstLine = text.lineSequence().firstOrNull() ?: ""
        val parts = firstLine.split(' ')
        val method = parts.getOrNull(0) ?: "GET"
        val path = parts.getOrNull(1) ?: "/"
        // Store-hosted app first: a raw route (CouchWire) that owns `/` or an asset wins over the
        // classpath shell, exactly as a CouchApp vhost would. `/api/…` built-ins stay authoritative.
        if (!path.startsWith("/api/") || path.startsWith("/api/v0/")) {
            rawRoutes.firstNotNullOfOrNull { it(method, path, payload, null) }?.let { return it }
        }
        return when (path.substringBefore('?')) {
            "/api/health" -> HttpResponse(200, """{"ok":true,"server":"kanban","now":${System.currentTimeMillis()}}""")
            "/api/cap"    -> HttpResponse(200, """{"protocols":["Http","Json","Socks5","Tls","Bonjour","Upnp"],"capabilities":["Process@local","Cas@local","Wireproto@lan.localhost"]}""")
            "/api/board"  -> HttpResponse(200, boardJson())
            "/api/metrics" -> {
                // Prometheus text-format metrics scrape endpoint.
                // Also supports JSON ?format=json query param.
                val acceptJson = text.contains("format=json", ignoreCase = true)
                if (acceptJson) {
                    val json = runCatching { JsonSupport.stringify(FlywheelMetrics.toJsonMap()) }
                        .getOrElse { """{"error":"metrics_unavailable"}""" }
                    HttpResponse(200, json)
                } else {
                    val prom = runCatching { FlywheelMetrics.toPrometheusFormat() }
                        .getOrElse { "# ERROR: metrics unavailable\n" }
                    HttpResponse(200, prom, "text/plain; version=0.0.4; charset=utf-8")
                }
            }
            "/api/jules/surface" -> {
                // Live Jules blackboard surface from the flywheel driver.
                val driver = _driver
                if (driver == null) {
                    HttpResponse(503, """{"error":"driver_not_ready","message":"flywheel driver not yet attached"}""")
                } else {
                    // Serve from cache while fresh: the GUI polls continuously and every
                    // recompute can cost seconds of the single HTTP worker's time, making
                    // polls queue behind each other (GUI freezes on its last frame).
                    val now = System.currentTimeMillis()
                    val cached = surfaceCache
                    val body: String
                    if (cached != null && now - cached.first < SURFACE_TTL_MS) {
                        body = cached.second
                    } else {
                        val report = driver.lastReactiveReport
                        val surface = runCatching {
                            val sessions = driver.activeSessions
                            // Sessions come from the driver's in-memory projection (no reactor, no
                            // fetch) — that is what the GUI counts. Activity timelines are NOT
                            // fetched here: the shared HTX reactor is held by the flywheel cycle,
                            // every timed-out fetch still occupies the reactor's queue (cancel
                            // cannot evict it), so per-request fetches self-poison the reactor and
                            // wedge the single HTTP worker (GUI frozen on its last frame). The
                            // cycle persists timelines to the WAL; the surface stays sessions-only.
                            val activitiesBySession: Map<String, List<JulesRestClient.ActivityInfo>> = emptyMap()
                            // Evict expired entries and emit TTL eviction events to SSE clients.
                            val evicted = borg.trikeshed.jules.ui.JulesBlackboardAdapter.evictExpired()
                            driver.emitTtlEvicted(evicted)
                            val (_, surf, _) = borg.trikeshed.jules.ui.JulesBlackboardAdapter.projectFullSurface(
                                sessions = sessions,
                                activitiesBySession = activitiesBySession,
                            )
                            JsonSupport.stringify(surf)
                        }.getOrElse { ex ->
                            """{"error":"surface_projection_failed","reason":"${ex.message?.take(200)}"}"""
                        }
                        val phaseLatenciesJson = report?.phaseLatencies?.joinToString(",", "[", "]") { (phase, ms) ->
                            """["$phase",$ms]"""
                        } ?: "[]"
                        val auditSignalsJson = report?.auditSignals?.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" } ?: "[]"
                        val reportJson = if (report != null) {
                            ""","cycle":{"cycleMs":${report.cycleMs},"phase":"${report.phase.name}","answered":${report.answered},"dispatched":${report.dispatched},"alive":${report.alive},"available":${report.available},"settled":${report.settled},"archived":${report.archived},"phaseLatencies":$phaseLatenciesJson,"auditSignals":$auditSignalsJson}"""
                        } else {
                            ""
                        }
                        val metricsMap = FlywheelMetrics.toJsonMap()
                        val cyclesPerSecond = metricsMap["cyclesPerSecond"]
                        val cyclesAt100PerDay = metricsMap["cyclesAt100PerDay"]
                        val activeSlots = FlywheelMetrics.activeSlots
                        val targetSlots = FlywheelMetrics.TARGET_SLOTS
                        body = """{"surface":$surface$reportJson,"throughput":{"cyclesPerSecond":$cyclesPerSecond,"cyclesAt100PerDay":$cyclesAt100PerDay},"slots":{"cap":$targetSlots,"alive":$activeSlots,"available":${(targetSlots - activeSlots).coerceAtLeast(0)}}}"""
                        surfaceCache = now to body
                    }
                    HttpResponse(200, body)
                }
            }
            "/api/submit" -> if (method == "POST") submit(text) else HttpResponse(405, """{"error":"method_not_allowed"}""")
            "/api/donor"  -> if (method == "POST") submit(text) else HttpResponse(405, """{"error":"method_not_allowed"}""")
            "/api/invoke" -> if (method == "POST") invoke(text) else HttpResponse(405, """{"error":"method_not_allowed"}""")
            "/", "/index.html" -> HttpResponse(200, forgeShellHtml(), "text/html; charset=utf-8")
            else -> staticAsset(path)
                ?: extraRoutes.firstNotNullOfOrNull { it(method, path, text, null) }
                ?: HttpResponse(404, """{"error":"not_found","path":"$path"}""")
        }
    }

    // ── Forge PWA: the shell and its static assets ───────────────────────

    /** The seed-baked shell: ForgeApp renders the web template from commonMain; we only serve it. */
    private fun forgeShellHtml(): String = runCatching {
        borg.trikeshed.forge.ForgeApp.renderHtml(userId = "jim")
    }.getOrElse { ex ->
        "<html><body><h1>Forge shell failed to render</h1><pre>${ex.message}</pre><p>see /api/health</p></body></html>"
    }

    /** Static PWA assets straight from `src/commonMain/resources/web/` on the classpath. Paths are fixed — no traversal. */
    private val staticAssets: Map<String, Pair<String, String>> = mapOf(
        "/styles.css" to ("web/styles.css" to "text/css; charset=utf-8"),
        "/script.js" to ("web/script.js" to "application/javascript; charset=utf-8"),
        "/sw.js" to ("web/sw.js" to "application/javascript; charset=utf-8"),
        "/manifest.webmanifest" to ("web/manifest.webmanifest" to "application/manifest+json; charset=utf-8"),
        "/icons/forge-icon.svg" to ("web/icons/forge-icon.svg" to "image/svg+xml"),
        "/icons/forge-icon-maskable.svg" to ("web/icons/forge-icon-maskable.svg" to "image/svg+xml"),
    )

    private fun staticAsset(path: String): HttpResponse? {
        val (resource, contentType) = staticAssets[path.substringBefore('?')] ?: return null
        val bytes = JvmKanbanServer::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: return HttpResponse(404, """{"error":"asset_missing","resource":"$resource"}""")
        return HttpResponse(200, String(bytes, StandardCharsets.UTF_8), contentType)
    }

    /** Monotonic ingress sequence for accepted command batches — the watermark the browser echoes back. */
    private val invokeSequence = AtomicLong(0)

    /**
     * Reactor ingress for the browser command queue (and the service worker's offline replay):
     * `{ userId, commands:[{type, kind, jobId, idempotencyKey, …}] }`. Each batch is accepted under one
     * sequence number; the accepted count and sequence are the receipt. Commands are currently
     * acknowledged, not yet lowered to JobCommand (see docs/forge-ui-gap-analysis.md item 7).
     */
    private fun invoke(body: String): HttpResponse {
        val payload = body.substringAfter("\r\n\r\n", "").ifEmpty { body.substringAfter("\n\n", "") }
        if (payload.isBlank()) return HttpResponse(400, """{"error":"empty_body"}""")
        val parsed = runCatching { JsonSupport.parse(payload) }.getOrNull()
            ?: return HttpResponse(400, """{"error":"bad_json"}""")
        val commands: List<*> = when (parsed) {
            is Map<*, *> -> (parsed["commands"] as? List<*>) ?: listOf(parsed)
            is List<*> -> parsed
            else -> emptyList<Any?>()
        }
        val seq = invokeSequence.incrementAndGet()
        val keys = commands.mapNotNull { (it as? Map<*, *>)?.get("idempotencyKey") as? String }
        return HttpResponse(
            202,
            JsonSupport.stringify(
                linkedMapOf(
                    "ok" to true,
                    "accepted" to commands.size,
                    "sequence" to seq,
                    "idempotencyKeys" to keys,
                )
            ),
        )
    }

    private fun boardJson(): String = runCatching {
        val reduction = ForgeKanbanIngest.loadProjection("jim")
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

    private suspend fun submit(body: String): HttpResponse {
        val payload = body.substringAfter("\r\n\r\n", "").ifEmpty {
            // Some clients use \n separators; tolerate that.
            body.substringAfter("\n\n", "")
        }
        if (payload.isBlank()) return HttpResponse(400, """{"error":"empty_body"}""")
        return runCatching {
            val tmp = "/tmp/hi"
            writeStringJvm(tmp, payload)
            val reduction = ForgeKanbanIngest.persistMarkdown("jim", tmp)
            reduction.causalNodes.forEach { node ->
                causalWal.append(node.causalKey, JsonSupport.stringify(node.toWalMap()).encodeToByteArray())
                graphIndex.addOrGet(node)
            }
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
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            HttpResponse(500, """{"error":"submit_failed","reason":"${it.message}"}""")
        }
    }

    private fun statusReason(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun replayCausalWal() {
        causalWal.replay().forEach { (_, bytes) ->
            runCatching {
                val map = JsonSupport.parseMap(bytes.decodeToString())
                graphIndex.addOrGet(map.toCausalNode())
            }.onFailure { error ->
                System.err.println("causal WAL replay skipped corrupt record: ${error.message}")
            }
        }
        System.err.println("Replayed ${graphIndex.size} nodes into CausalGraphNodeIndex")
    }
}

private fun CausalGraphNode.toWalMap(): Map<String, Any?> = linkedMapOf(
    "nodeId" to nodeId,
    "opId" to opId,
    "opVersion" to opVersion,
    "parentNodeIds" to parentNodeIds,
    "inputFingerprint" to inputFingerprint,
    "blackboardId" to blackboard.id,
    "causalClock" to causalClock,
    "topoOrdinal" to topoOrdinal,
    "outputHash" to outputHash,
)

private fun Map<String, Any?>.toCausalNode(): CausalGraphNode = causalGraphNode(
    nodeId = requireNotNull(this["nodeId"] as? String),
    opId = requireNotNull(this["opId"] as? String),
    opVersion = requireNotNull(this["opVersion"] as? String),
    parentNodeIds = (this["parentNodeIds"] as? List<*>)?.map(Any?::toString).orEmpty(),
    inputFingerprint = requireNotNull(this["inputFingerprint"] as? String),
    blackboard = blackboardContext(id = requireNotNull(this["blackboardId"] as? String)),
    causalClock = (this["causalClock"] as? Number)?.toLong() ?: error("missing causalClock"),
    topoOrdinal = (this["topoOrdinal"] as? Number)?.toInt() ?: error("missing topoOrdinal"),
    outputHash = this["outputHash"] as? String,
)

private fun traitSpaceOf(vararg capabilities: Capability): TraitSpace = TraitSpace {
    capabilities.size j { index -> capabilities[index] }
}

private fun writeStringJvm(path: String, text: String) {
    val p = Paths.get(path)
    if (p.parent != null) NioFiles.createDirectories(p.parent)
    NioFiles.writeString(
        p, text,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
    )
}
