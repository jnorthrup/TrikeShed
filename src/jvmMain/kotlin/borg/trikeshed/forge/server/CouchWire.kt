package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.couch.replicate.CouchReplicator
import borg.trikeshed.couch.replicate.ReplicationReport
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.utils.rfxhttp.CouchHttpSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * CouchWire — mounts one [CouchDatabase] on the daemon's HTTP tier ([JvmKanbanServer] raw-route
 * seam), so the Couch 1.6/1.7 surface, the store-hosted PWA and the CAS lanes all answer on the
 * same port as `/api/…`. The HTTP tier lives in the reactor; there is no second server.
 *
 * Adds to [CouchWireRouter]:
 *   GET  /{db}/_changes?feed=continuous|longpoll[&since&include_docs&heartbeat]  — streamed
 *   POST /{db}/_replicate {source, target, continuous?, interval_ms?, cancel?}     — 1.x replicator
 *   GET  /{db}/_replicate                                                          — live jobs
 *
 * `source`/`target` are the local database name or a peer URL (`http://host:port/db`). Pull is the
 * preferred m2m direction (the laptop pulls the install); push is bounded by the listener's
 * request cap. Continuous replication is a polling loop on [scope] at `interval_ms`.
 */
class CouchWire(
    val router: CouchWireRouter,
    private val replicator: CouchReplicator?,
    private val scope: CoroutineScope,
    private val defaultHeartbeatMs: Long = 15_000,
) {
    val db: CouchDatabase get() = router.db

    private class Continuous(@Volatile var job: Job, val spec: Map<String, Any?>, @Volatile var last: ReplicationReport?)
    private val continuous = ConcurrentHashMap<String, Continuous>()

    companion object {
        fun streamingPaths(dbName: String): Set<String> =
            setOf("/$dbName/_changes?feed=continuous", "/$dbName/_changes?feed=longpoll")

        fun bodyOf(payload: ByteArray): ByteArray {
            val sep = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
            var i = 0
            outer@ while (i <= payload.size - sep.size) {
                for (k in sep.indices) if (payload[i + k] != sep[k]) { i++; continue@outer }
                return payload.copyOfRange(i + sep.size, payload.size)
            }
            return ByteArray(0)
        }
    }

    /** [borg.trikeshed.litebike.RawRoute] entry point. */
    suspend fun route(method: String, path: String, payload: ByteArray, respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        val query = CouchHttpSurface.parseQuery(path.substringAfter('?', ""))

        if (p == "/${db.name}/_replicate" || p == "/_replicate") return replicate(method, bodyOf(payload))

        // The ddoc vhost (gh-pages PWA hoisted out of store attachments) does NOT
        // ride the app port: it shadowed `/`, `/sw.js`, and any page docs/ carries
        // with the stale PUBLIC build. Only the db surface is couch's here —
        // everything else falls through to the operator pages on the same listener.
        if (p != "/${db.name}" && !p.startsWith("/${db.name}/")) return null

        if (respond != null) {
            if (p == "/${db.name}/_changes" && method == "GET") { streamChanges(query, respond); return JvmKanbanServer.HttpResponse(200, "") }
            // A streaming slot we do not own: answer as a normal reply through `respond`.
            val reply = router.handle(method, path, bodyOf(payload)) ?: return null
            val head = "HTTP/1.1 ${reply.status} OK\r\nContent-Type: ${reply.contentType}\r\nContent-Length: ${reply.bytes.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
            respond(head.toByteArray(StandardCharsets.UTF_8) + reply.bytes)
            return JvmKanbanServer.HttpResponse(reply.status, "")
        }

        val reply = router.handle(method, path, bodyOf(payload)) ?: return null
        return JvmKanbanServer.HttpResponse(reply.status, "", reply.contentType, reply.bytes)
    }

    // ── _changes feeds ────────────────────────────────────────────

    private suspend fun streamChanges(query: Map<String, String>, respond: suspend (ByteArray) -> Unit) {
        val feed = query["feed"] ?: "continuous"
        var since = query["since"]?.toLongOrNull() ?: 0L
        val includeDocs = query["include_docs"] == "true"
        val heartbeat = query["heartbeat"]?.toLongOrNull()?.takeIf { it > 0 } ?: defaultHeartbeatMs
        val limit = query["limit"]?.toIntOrNull() ?: Int.MAX_VALUE
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-cache\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        respond(head.toByteArray(StandardCharsets.UTF_8))
        val (signal, cancel) = db.commitSignal()
        try {
            if (feed == "longpoll") {
                while (true) {
                    val frames = db.framesSince(since).take(limit)
                    if (frames.isNotEmpty()) {
                        val last = frames.last().sequence + 1
                        respond(JsonSupport.stringify(mapOf("results" to frames.map { db.changeRow(it, includeDocs) }, "last_seq" to last)).toByteArray(StandardCharsets.UTF_8))
                        return
                    }
                    if (!awaitOrHeartbeat(signal, heartbeat, respond)) return
                }
            }
            var sent = 0
            while (true) {
                val frames = db.framesSince(since)
                for (f in frames) {
                    respond((JsonSupport.stringify(db.changeRow(f, includeDocs)) + "\n").toByteArray(StandardCharsets.UTF_8))
                    since = f.sequence + 1
                    if (++sent >= limit) {
                        respond(JsonSupport.stringify(mapOf("last_seq" to since)).toByteArray(StandardCharsets.UTF_8)); return
                    }
                }
                if (!awaitOrHeartbeat(signal, heartbeat, respond)) return
            }
        } catch (_: Throwable) {
            // client went away — the normal end of a feed
        } finally {
            cancel()
        }
    }

    /** Wait for a commit or send a heartbeat newline; false when the client is gone. */
    private suspend fun awaitOrHeartbeat(signal: kotlinx.coroutines.channels.Channel<Unit>, heartbeat: Long, respond: suspend (ByteArray) -> Unit): Boolean {
        val woke = select<Boolean> {
            signal.onReceive { true }
            onTimeout(heartbeat) { false }
        }
        if (!woke) {
            try { respond("\n".toByteArray(StandardCharsets.UTF_8)) } catch (_: Throwable) { return false }
        }
        return true
    }

    // ── _replicate ────────────────────────────────────────────────

    private fun json(status: Int, v: Any?) = JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(v))

    private suspend fun replicate(method: String, body: ByteArray): JvmKanbanServer.HttpResponse {
        if (method == "GET") return json(200, mapOf("jobs" to continuous.map { (id, c) -> mapOf("id" to id, "spec" to c.spec, "active" to c.job.isActive, "last" to c.last?.toMap()) }))
        if (method != "POST") return json(405, mapOf("error" to "method_not_allowed"))
        val r = replicator ?: return json(501, mapOf("error" to "not_implemented", "reason" to "no HTX client bound for replication"))
        @Suppress("UNCHECKED_CAST")
        val spec = runCatching { JsonSupport.parse(body.decodeToString()) as? Map<String, Any?> }.getOrNull()
            ?: return json(400, mapOf("error" to "bad_request", "reason" to "JSON body required"))
        val source = spec["source"] as? String ?: return json(400, mapOf("error" to "bad_request", "reason" to "source required"))
        val target = spec["target"] as? String ?: return json(400, mapOf("error" to "bad_request", "reason" to "target required"))
        val isLocal = { s: String -> s == db.name || s == "/${db.name}" }
        val isUrl = { s: String -> s.startsWith("http://") || s.startsWith("https://") }
        val direction = when {
            isUrl(source) && isLocal(target) -> "pull"
            isLocal(source) && isUrl(target) -> "push"
            else -> return json(400, mapOf("error" to "bad_request", "reason" to "one side must be '${db.name}', the other a peer URL"))
        }
        val peer = if (direction == "pull") source else target
        val id = CouchReplicator.replicationId(direction, source, target)
        if (spec["cancel"] == true) {
            val c = continuous.remove(id) ?: return json(404, mapOf("error" to "not_found", "reason" to "no such continuous replication"))
            c.job.cancel(); return json(200, mapOf("ok" to true, "_local_id" to id, "cancelled" to true))
        }
        val since = (spec["since"] as? Number)?.toLong()
        val run: suspend () -> ReplicationReport = { if (direction == "pull") r.pull(peer, since) else r.push(peer, since) }
        if (spec["continuous"] == true) {
            val interval = (spec["interval_ms"] as? Number)?.toLong()?.coerceAtLeast(250) ?: 5_000L
            continuous[id]?.job?.cancel()
            val holder = Continuous(Job(), spec, null)
            holder.job = scope.launch {
                while (true) {
                    holder.last = runCatching { run() }.getOrElse { ReplicationReport(direction, peer, 0, 0, 0, 0, 0, -1) }
                    delay(interval)
                }
            }
            continuous[id] = holder
            return json(202, mapOf("ok" to true, "_local_id" to id, "direction" to direction, "peer" to peer, "interval_ms" to interval))
        }
        if (spec["async"] == true) {
            // Big teleports outlive an HTTP client's patience: hand back the id now, run in the
            // reactor, read the outcome later via GET _replicate.
            val holder = Continuous(Job(), spec, null)
            holder.job = scope.launch { holder.last = runCatching { run() }.getOrElse { ReplicationReport(direction, peer, 0, 0, 0, 0, 0, -1) } }
            continuous[id] = holder
            return json(202, mapOf("ok" to true, "_local_id" to id, "direction" to direction, "peer" to peer, "async" to true))
        }
        val report = runCatching { run() }.getOrElse { return json(502, mapOf("error" to "replication_failed", "reason" to (it.message ?: it.toString()))) }
        return json(200, report.toMap() + ("_local_id" to id))
    }
}
