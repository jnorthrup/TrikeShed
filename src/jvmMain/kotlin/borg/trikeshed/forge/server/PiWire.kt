package borg.trikeshed.forge.server

import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.agent.modelmux.acp.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.*
import borg.trikeshed.pi.PiModelPort
import borg.trikeshed.pi.PiWorkspace
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Independent Pi sessions. Bearer authentication is required: the wire has no trusted peer address. */
class PiWire(
    scope: CoroutineScope,
    private val context: CoroutineContext,
    private val breadcrumbs: BreadcrumbElement,
    private val catalog: suspend () -> ModelMux?,
    private val tokenSha256: String?,
    private val workspace: PiWorkspace? = null,
    private val openPort: (String, BreadcrumbGeneration) -> Port = { model, generation ->
        val runtime = PiModelPort(model, generation, context + breadcrumbs, workspace, catalog)
        object : Port {
            override fun prompt(text: String): Map<String, Any?> = reifyStrict(runtime.prompt(text).canonical()) as Map<String, Any?>
            override fun close() = runtime.close()
        }
    },
) {
    interface Port : AutoCloseable { fun prompt(text: String): Map<String, Any?> }
    companion object {
        const val TOKEN_PROPERTY = "trikeshed.pi.tokenSha256"
        const val SESSION_LIMIT = 8
    }
    init { require(tokenSha256 == null || tokenSha256.matches(Regex("[0-9a-f]{64}"))) { "Pi token hash must be SHA256 hex" } }
    private val supervisor = SupervisorJob(requireNotNull(scope.coroutineContext[Job]))
    private val owner = CoroutineScope(scope.coroutineContext + supervisor + Dispatchers.IO)
    private val lock = Any()
    private var draining = false
    private class Session(val id: String, val model: String) {
        val input = Channel<String>(1)
        lateinit var job: Job
        var state = "starting"
        var turn = 0
        var result: Map<String, Any?>? = null
        var error: Map<String, String>? = null
        var released = false
        var contextRetained = false
        var latestTurnRetained = false
        var retainedMessages = 0
        var retainedPrefixHash: String? = null
    }
    private val sessions = linkedMapOf<String, Session>()

    suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val endpoint = path.substringBefore('?')
        if (method == "GET" && endpoint in _s["/pi", "/pi/"]) {
            val bytes = javaClass.classLoader.getResourceAsStream("web/pi.html")?.use { it.readBytes() }
                ?: return json(404, _m["error" j "asset_missing"])
            return JvmKanbanServer.HttpResponse(200, "", "text/html; charset=utf-8", bytes)
        }
        if (endpoint != "/api/pi" && !endpoint.startsWith("/api/pi/")) return null
        if (tokenSha256 == null) return json(503, _m["error" j "pi_access_unconfigured", "available" j false])
        if (!authenticated(text)) return json(401, _m["error" j "authentication_required"])
        if (!sameOrigin(text)) return json(403, _m["error" j "origin_not_allowed"])
        return try {
            when {
                method == "GET" && endpoint == "/api/pi/models" -> {
                    val models = withContext(context.minusKey(Job)) { catalog()?.listModels("chat") }
                    json(200, _m["available" j (models != null && models.size > 0), "models" j models?.view?.map {
                        _m["id" j it.id, "caps" j it.caps.view.toList()]
                    }.orEmpty()])
                }
                method == "POST" && endpoint == "/api/pi/session/start" -> start(request(text, _s["model"]))
                method == "POST" && endpoint == "/api/pi/session/prompt" -> prompt(request(text, _s["sessionId", "text"]))
                method == "POST" && endpoint == "/api/pi/session/close" -> close(request(text, _s["sessionId"]))
                method == "GET" && endpoint == "/api/pi/session/status" -> {
                    val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
                    val id = requireNotNull(query["sessionId"]) { "sessionId required" }
                    synchronized(lock) { sessions[id]?.let { json(200, snapshot(it)) } }
                        ?: json(404, _m["error" j "unknown_session"])
                }
                else -> json(405, _m["error" j "method_not_allowed"])
            }
        } catch (_: IllegalArgumentException) { json(400, _m["error" j "invalid_request"]) }
        catch (_: CancellationException) { throw CancellationException("Pi request cancelled") }
        catch (_: Throwable) { json(503, _m["error" j "pi_unavailable"]) }
    }

    private suspend fun start(request: Map<*, *>): JvmKanbanServer.HttpResponse {
        val model = requireNotNull(request["model"] as? String).also { require(it.isNotBlank() && it.length <= 128) }
        // Explicit platform-catalog selection only; no terminal/session/default-provider lookup.
        val mux = withContext(context.minusKey(Job)) { catalog() } ?: return json(503, _m["error" j "model_catalog_unavailable"])
        require(mux.listModels("chat").view.any { it.id == model }) { "model must be in platform catalog" }
        return synchronized(lock) {
            if (draining || !supervisor.isActive) return@synchronized json(503, _m["error" j "pi_draining"])
            // Bound live and terminal records together; callers can explicitly release closed records by close.
            if (sessions.size >= SESSION_LIMIT) return@synchronized json(409, _m["error" j "session_limit"])
            val session = Session("pi-" + UUID.randomUUID(), model)
            sessions[session.id] = session
            session.job = owner.launch { run(session) }
            json(202, snapshot(session))
        }
    }

    private fun prompt(request: Map<*, *>): JvmKanbanServer.HttpResponse {
        val id = requireNotNull(request["sessionId"] as? String)
        val text = requireNotNull(request["text"] as? String).also { require(it.isNotBlank() && it.length <= PiModelPort.PROMPT_CHARS) }
        return synchronized(lock) {
            val session = sessions[id] ?: return@synchronized json(404, _m["error" j "unknown_session"])
            if (draining || session.state != "ready") return@synchronized json(409, _m["error" j "session_not_ready", "state" j session.state])
            check(session.input.trySend(text).isSuccess)
            session.state = "running"; session.turn++; session.result = null; session.error = null; session.latestTurnRetained = false
            json(202, snapshot(session))
        }
    }

    private suspend fun close(request: Map<*, *>): JvmKanbanServer.HttpResponse {
        val id = requireNotNull(request["sessionId"] as? String)
        val terminal = synchronized(lock) {
            val session = sessions[id] ?: return json(404, _m["error" j "unknown_session"])
            if (session.state !in _s["closed", "failed"]) {
                session.state = "closing"
                session.input.close()
                return json(202, snapshot(session))
            }
            session
        }
        terminal.job.join() // Terminal publication precedes worker completion; do not rewrite it to closing.
        return synchronized(lock) {
            if (!terminal.released) return@synchronized json(409, _m["error" j "session_cleanup_incomplete", "state" j terminal.state])
            sessions.remove(id)
            json(200, snapshot(terminal))
        }
    }

    private suspend fun run(session: Session) {
        val generation = BreadcrumbGeneration(owner, session.id)
        var port: Port? = null
        var failed = false
        try {
            breadcrumbs.transact(session.id) { }
            synchronized(lock) { session.contextRetained = true }
            port = openPort(session.model, generation)
            synchronized(lock) { if (session.state != "closing") session.state = "ready" }
            for (prompt in session.input) {
                val turn = synchronized(lock) { session.turn }
                try {
                    breadcrumbs.transact(session.id, create = false) {
                        it.put(BreadcrumbId("pi-input-$turn"), "pi-input", prompt); it.state()
                    }
                } catch (failure: Throwable) { throw RetentionFailure(failure) }
                val result = port.prompt(prompt)
                synchronized(lock) { session.result = result }
                try {
                    val messages = result["messages"] as? List<*> ?: error("Pi messages missing")
                    val previous = session.retainedMessages
                    check(messages.size >= previous)
                    check(previous == 0 || ContentId.of(jsonOf(messages.take(previous)).encodeToByteArray()).hex == session.retainedPrefixHash)
                    val delta = jsonOf(_m["messages" j messages.drop(previous), "fromMessage" j previous, "buffered" j true])
                    require(delta.length <= PiModelPort.RESPONSE_CHARS) { "Pi turn exceeds retained transcript budget" }
                    breadcrumbs.transact(session.id, create = false) {
                        it.put(BreadcrumbId("pi-turn-$turn"), "pi-transcript", delta); it.state()
                    }
                    session.retainedMessages = messages.size
                    session.retainedPrefixHash = ContentId.of(jsonOf(messages).encodeToByteArray()).hex
                    synchronized(lock) { session.latestTurnRetained = true }
                } catch (failure: Throwable) { throw RetentionFailure(failure) }
                synchronized(lock) { if (session.state != "closing") session.state = "ready" }
            }
        } catch (failure: Throwable) {
            failed = true
            synchronized(lock) { session.state = "closing"; session.error = if (failure is RetentionFailure)
                _m["code" j "transcript_retention_failed", "detail" j "Pi context capacity or transcript retention failed; prior evidence retained"]
                else _m["code" j "session_failed", "detail" j "Pi session failed; admitted work is draining"] }
        } finally {
            session.input.close()
            withContext(NonCancellable) {
                // Host work can outlive a timed-out wrapper. Settle it before process close and runtime release.
                var settled = true
                try { generation.drain() } catch (_: Throwable) { failed = true; settled = false }
                if (settled) try { port?.close() } catch (_: Throwable) { failed = true; settled = false }
                // Contexts and bounded new-message transcript evidence remain indexed after controller close.
                synchronized(lock) {
                    session.released = settled
                    session.state = if (failed) "failed" else "closed"
                    if (failed && session.error == null) session.error = _m["code" j "session_failed", "detail" j "Pi session failed"]
                }
            }
        }
    }

    suspend fun drain() = withContext(NonCancellable) {
        val jobs = synchronized(lock) {
            draining = true
            sessions.values.map { session ->
                if (session.state !in _s["closed", "failed"]) { session.state = "closing"; session.input.close() }
                session.job
            }
        }
        jobs.joinAll()
        supervisor.complete(); supervisor.join()
        check(synchronized(lock) { sessions.values.all { it.released } }) { "Pi cleanup incomplete; session contexts retained" }
    }

    private class RetentionFailure(cause: Throwable) : IllegalStateException("Pi retention failed", cause)

    private fun snapshot(session: Session): Map<String, Any?> = _m[
        "sessionId" j session.id, "contextId" j session.id, "contextRetained" j session.contextRetained,
        "latestTurnRetained" j session.latestTurnRetained, "persistenceConfigured" j (breadcrumbs.store != null),
        "retention" j "Bounded indexed prompt and new-message evidence; no agent resume or indefinite retention guarantee",
        "workspaceFiles" j workspace?.paths?.toList().orEmpty(),
        "model" j session.model, "state" j session.state, "turnId" j session.turn,
        "result" j session.result, "error" j session.error]
    private fun json(status: Int, value: Map<String, Any?>) = JvmKanbanServer.HttpResponse(status, jsonOf(value))
    private fun request(text: String, keys: Set<String>): Map<*, *> {
        val body = text.substringAfter("\r\n\r\n", text.substringAfter("\n\n", text))
        require(body.length <= PiModelPort.PROMPT_CHARS + 4096)
        val value = reifyStrict(body) as? Map<*, *> ?: throw IllegalArgumentException("JSON object required")
        require(value.keys.all { it is String && it in keys } && value.keys == keys)
        return value
    }
    private fun headers(text: String): List<String> = text.substringBefore("\r\n\r\n").split("\r\n").drop(1)
    private fun authenticated(text: String): Boolean {
        val values = headers(text).filter { it.substringBefore(':').equals("Authorization", true) }
        if (values.size != 1) return false
        val value = values.single().substringAfter(':').trim()
        if (!value.startsWith("Bearer ") || value.length !in 24..2048) return false
        val actual = ContentId.of(value.removePrefix("Bearer ").encodeToByteArray()).hex
        return MessageDigest.isEqual(actual.encodeToByteArray(), requireNotNull(tokenSha256).encodeToByteArray())
    }
    private fun sameOrigin(text: String): Boolean = runCatching {
        val fields = headers(text)
        val origins = fields.filter { it.substringBefore(':').equals("Origin", true) }
        val sites = fields.filter { it.substringBefore(':').equals("Sec-Fetch-Site", true) }
        if (sites.any { it.substringAfter(':').trim() == "cross-site" }) return false
        if (origins.isEmpty()) return true // Non-browser clients still require the bearer.
        if (origins.size != 1) return false
        val hosts = fields.filter { it.substringBefore(':').equals("Host", true) }
        if (hosts.size != 1) return false
        val origin = URI(origins.single().substringAfter(':').trim())
        origin.scheme in _s["http", "https"] && origin.rawAuthority?.lowercase() == hosts.single().substringAfter(':').trim().lowercase() &&
            origin.rawUserInfo == null && origin.path.orEmpty().isEmpty() && origin.query == null && origin.fragment == null
    }.getOrDefault(false)
}
