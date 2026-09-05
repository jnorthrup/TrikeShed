package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class BlackboardWire(val blackboard: ConfixBlackboard, scope: CoroutineScope) {
    companion object {
        val ROUTES: List<Pair<String, String>> = listOf("GET" to "/blackboard", "GET" to "/blackboard/facts", "POST" to "/blackboard/assert", "GET" to "/blackboard/sites", "GET" to "/blackboard/board", "GET" to "/blackboard/sheet")
        /** Paths the HTTP server must hand to [route] raw (SSE lives on them). */
        val STREAMING: Set<String> = setOf("/blackboard/facts")
    }

    private val assertChannel = Channel<String>(64)
    internal val pointcutDefinitions = borg.trikeshed.cursor.PointcutDefinitionWriter(blackboard, scope)
    private val epoch = java.util.UUID.randomUUID().toString()

    init {
        scope.launch {
            for (payload in assertChannel) {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val map = JsonSupport.parse(payload) as? Map<String, Any?>
                    map?.forEach { (k, v) ->
                        // H5: definitions are not theater — route the assert-funnel key through
                        // the production writer so enabled=false suppresses the runtime site.
                        if (pointcutDefinitions.applyFunnelDoc(k, v) == null) {
                            blackboard.put(k, v, "ide")
                        }
                    }
                }
            }
        }

    }

    suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)? = null): JvmKanbanServer.HttpResponse? {
        // R7: one consolidated blackboard page. Resource I/O stays off the reactor thread.
        if (method == "GET" && (path == "/blackboard" || path == "/blackboard/")) {
            return withContext(Dispatchers.IO) {
                // One page, one landscape: /blackboard and /graal serve the SAME console
                // document — the blackboard is the console's O panel, not a sibling page.
                val html = BlackboardWire::class.java.classLoader
                    .getResourceAsStream("web/harness.html")
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    ?: return@withContext JvmKanbanServer.HttpResponse(404, "blackboard page not found", "text/plain; charset=utf-8")
                JvmKanbanServer.HttpResponse(200, html, "text/html; charset=utf-8")
            }
        }
        if (method == "GET" && path.substringBefore('?') == "/blackboard/facts") {
            val query = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
            var after = query["since"]?.toLongOrNull() ?: 0L
            val clientEpoch = query["epoch"]
            respond?.invoke(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
            blackboard.changes.onStart { emit(blackboard.state) }.takeWhile {
                val replay = blackboard.replay(after)
                if (replay.reset || (clientEpoch != null && clientEpoch != epoch)) {
                    val reason = if (clientEpoch != null && clientEpoch != epoch) "epoch_changed" else "replay_gap"
                    val data = JsonSupport.stringify(mapOf("reason" to reason, "epoch" to epoch, "revision" to replay.snapshot.revision))
                    respond?.invoke("event: reset\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8))
                    false
                } else {
                    for (change in replay.changes) {
                        val data = JsonSupport.stringify(mapOf(
                            "seq" to change.revision, "revision" to change.revision, "epoch" to epoch,
                            "key" to change.key, "value" to change.value, "deleted" to change.deleted,
                            "actor" to change.provenance.language, "atMs" to change.provenance.timestamp,
                        ))
                        respond?.invoke("id: ${change.revision}\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8))
                        after = change.revision
                    }
                    true
                }
            }.collect {}
            return null
        }

        if (method == "GET" && path.substringBefore('?') == "/blackboard/board") {
            val snapshot = blackboard.snapshot()
            return JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(mapOf(
                "keys" to snapshot.values.size, "board" to snapshot.values,
                "seq" to snapshot.revision, "revision" to snapshot.revision, "epoch" to epoch,
                "provenance" to snapshot.provenance.mapValues { (_, p) -> mapOf(
                    "actor" to p.language, "atMs" to p.timestamp, "revision" to p.revision,
                ) },
            )))
        }

        // The existing CursorSheet family, with request-wide traversal and payload budgets.
        if (method == "GET" && path.substringBefore('?') == "/blackboard/sheet") {
            val q = borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))
            val key = q["key"]
            val prefix = q["prefix"]?.trimEnd('/')
            val max = q["max"]?.toIntOrNull()?.coerceIn(1, 256) ?: 256
            val rowLimit = q["rows"]?.toIntOrNull()?.coerceIn(1, 1024) ?: 512
            val snapshot = blackboard.snapshot()
            if (q["revision"]?.toLongOrNull()?.let { it != snapshot.revision } == true)
                return JvmKanbanServer.HttpResponse(409, """{"error":"snapshot_changed"}""")
            val started = kotlin.time.TimeSource.Monotonic.markNow()
            var rowsLeft = rowLimit
            var charsLeft = 65536
            var nextKey: String? = null
            var limited: String? = null
            fun familyOf(id: String, title: String, subject: Any?, parent: String?, budget: Int): List<borg.trikeshed.forge.sheet.SheetSeed> {
                val shown = if (subject is Map<*, *> || subject is List<*>) subject else mapOf("value" to subject)
                val issue = borg.trikeshed.parse.json.ValueBudget(maxChars = charsLeft.coerceAtLeast(0), maxNodes = rowsLeft.coerceAtLeast(1) * 8).violation(shown)
                if (issue != null) {
                    limited = issue
                    return listOf(borg.trikeshed.forge.sheet.SheetSeed(id, title, emptyList(), emptyList(), parent, true, issue))
                }
                val json = JsonSupport.stringify(shown)
                charsLeft -= json.length + id.length + title.length
                val confix = borg.trikeshed.parse.confix.confixDoc(json)
                val family = borg.trikeshed.forge.sheet.confixSheets(id, title, confix,
                    maxSheets = budget, maxRows = rowsLeft.coerceAtLeast(0), maxChars = charsLeft.coerceAtLeast(0),
                    maxMillis = (50 - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
                rowsLeft -= family.sumOf { it.rows.size }
                if (family.any { it.truncated }) limited = family.firstOrNull { it.truncated }?.limit
                return if (parent == null) family else family.mapIndexed { i, sheet -> if (i == 0) sheet.copy(parent = parent) else sheet }
            }
            val family = when {
                !key.isNullOrEmpty() -> {
                    if (!snapshot.values.containsKey(key))
                        return JvmKanbanServer.HttpResponse(404, """{"error":"no_such_fact"}""")
                    familyOf(key, key, snapshot.values[key], null, max)
                }
                !prefix.isNullOrEmpty() -> {
                    val rows = ArrayList<List<Any?>>()
                    val children = ArrayList<borg.trikeshed.forge.sheet.SheetSeed>()
                    val after = q["after"]
                    var skipping = after != null
                    var scanned = 0
                    var lastKey: String? = after
                    for ((k, value) in snapshot.values) {
                        if (skipping) { if (k == after) skipping = false; continue }
                        if (++scanned > 8192 || started.elapsedNow().inWholeMilliseconds > 50 ||
                            rowsLeft <= 0 || charsLeft <= 0 || children.size >= max - 1) {
                            nextKey = lastKey; limited = limited ?: "projection_limit"; break
                        }
                        lastKey = k
                        if (!k.startsWith("$prefix/")) continue
                        rowsLeft--
                        val short = k.removePrefix("$prefix/")
                        val child = familyOf(k, short, value, prefix, max - 1 - children.size)
                        children.addAll(child)
                        rows.add(listOf(short, borg.trikeshed.forge.sheet.SheetRef(k)))
                    }
                    val columns = listOf(borg.trikeshed.forge.sheet.SheetColumn("key", "IoString"), borg.trikeshed.forge.sheet.SheetColumn("value", "Any"))
                    listOf(borg.trikeshed.forge.sheet.SheetSeed(prefix, prefix, columns, rows, truncated = limited != null, limit = limited)) + children
                }
                else -> return JvmKanbanServer.HttpResponse(400, """{"error":"key or prefix required"}""")
            }
            val data = family.mapIndexed { i, sheet -> sheet.toMap() + if (i == 0) mapOf("boardRevision" to snapshot.revision, "nextKey" to nextKey) else emptyMap() }
            val issue = borg.trikeshed.parse.json.ValueBudget(maxNodes = 32768, maxChars = 131072).violation(data)
            if (issue != null) return JvmKanbanServer.HttpResponse(413, JsonSupport.stringify(mapOf("error" to issue)))
            val body = JsonSupport.stringify(data)
            if (body.toByteArray(StandardCharsets.UTF_8).size > 1_048_576)
                return JvmKanbanServer.HttpResponse(413, """{"error":"payload_limit"}""")
            return JvmKanbanServer.HttpResponse(200, body)
        }

        if (method == "POST" && path == "/blackboard/assert") {
            val payload = text.substringAfter("\r\n\r\n", "").ifEmpty {
                text.substringAfter("\n\n", "")
            }
            if (payload.isBlank()) return JvmKanbanServer.HttpResponse(400, "{\"error\":\"empty_body\"}")
            if (payload.length > 1_048_576) return JvmKanbanServer.HttpResponse(413, "{\"error\":\"payload_limit\"}")
            assertChannel.send(payload)
            return JvmKanbanServer.HttpResponse(200, "{\"ok\":true}")
        }
        
        if (method == "GET" && path.startsWith("/blackboard/sites")) {
            val query = path.substringAfter("?")
            val owner = query.split("&").find { it.startsWith("owner=") }?.substringAfter("owner=") ?: ""
            val prefix = if (owner.isNotEmpty()) "pointcut/${owner}/" else "pointcut//"
            val keys = blackboard.keys().filter { it.startsWith(prefix) }
            return JvmKanbanServer.HttpResponse(200, JsonSupport.stringify(keys))
        }

        return null
    }
}
