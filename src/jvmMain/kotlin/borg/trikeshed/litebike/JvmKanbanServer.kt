@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.litebike

import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.context.nuid.Workgroup
import borg.trikeshed.context.nuid.TraitSpace
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files as NioFiles
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
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
 * is one worker that subscribed to the `Protocol.Http` slot on the
 * listener. Its job: parse the request line + headers, route to
 * `/api/health`, `/api/cap`, `/api/board`, `/api/submit`, etc., and
 * write back the JSON.
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
        val listener = LitebikeListenerElement(parentJob = null).also { it.open() }
        val fanout = NuidFanoutElement(parentJob = null).also { it.open() }
        fanout.register(Workgroup("process", Subnet.process, TraitSpace { 1 j { Capability.ProcessAll } }))
        fanout.register(Workgroup("cas", Subnet.process, TraitSpace { 1 j { Capability.CasAll } }))
        fanout.register(Workgroup("wireproto", Subnet.process, TraitSpace { 1 j { Capability.WireprotoAll } }))
        // Register Http + Json + Socks5 + Tls + Bonjour + Upnp.
        // IDs are TrikeShed-local conventions (Taxonomy.kt), not FFI-stable.
        val httpSlot  = listener.register(Protocol.Http)
        val jsonSlot  = listener.register(Protocol.Json)
        listener.register(Protocol.Socks5)
        listener.register(Protocol.Tls)
        val bonjourSlot = listener.register(Protocol.Bonjour)
        val upnpSlot   = listener.register(Protocol.Upnp)

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

        // The HTTP worker consumes the httpSlot. Each accepted byte
        // stream is a request; we route on the request path.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // The HTTP handler logic is handled by the wireproto workgroup.
        scope.launch {
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
                true // keep listening
            }
        }

        System.err.println("trikeshed-kanban: listening on :$port  donor=${donorPath ?: "<none>"}")
        System.err.println("Endpoints (CCEK): GET /api/health /api/cap /api/board POST /api/submit /api/donor")

        // Bind happens here — only place outside the worker scope that
        // opens a socket. The adapter resumes this coroutine on close.
        JvmLitebikeBindAdapter.bindAndServe(listener, port = port)
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
            "/api/cap"    -> HttpResponse(200, """{"protocols":["Http","Json","Socks5","Tls"]}""")
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
}

private fun writeStringJvm(path: String, text: String) {
    val p = Paths.get(path)
    if (p.parent != null) NioFiles.createDirectories(p.parent)
    NioFiles.writeString(
        p, text,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
    )
}
