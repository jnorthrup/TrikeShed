package borg.trikeshed.viewserver

import borg.trikeshed.couch.CouchElement
import borg.trikeshed.couch.handle.CollectionHandle
import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.tablespace.BlockStore
import borg.trikeshed.miniduck.tablespace.NioBlockWal
import borg.trikeshed.parse.confix.Combinators
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.contextOf
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ChannelResult
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations

import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ── HTTP/1.1 request parser ──────────────────────────────────────────────

/** Percent-decode a URL query parameter value (handles multi-byte UTF-8). */
private fun urlDecode(value: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < value.length) {
        when (val ch = value[i]) {
            '+' -> { bytes.add(' '.code.toByte()); i++ }
            '%' -> if (i + 2 < value.length) {
                val b = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) { bytes.add(b.toByte()); i += 3 }
                else { bytes.addAll(ch.toString().encodeToByteArray().asList()); i++ }
            } else { bytes.addAll(ch.toString().encodeToByteArray().asList()); i++ }
            else -> { bytes.addAll(ch.toString().encodeToByteArray().asList()); i++ }
        }
    }
    return bytes.toByteArray().decodeToString()
}

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

private fun parseHttpRequest(raw: String): HttpRequest? {
    val parts = raw.split("\r\n\r\n", limit = 2)
    val headerSection = parts[0]
    val body = parts.getOrElse(1) { "" }
    val lines = headerSection.split("\r\n")
    if (lines.isEmpty()) return null
    val requestLine = lines[0].split(" ")
    if (requestLine.size < 2) return null
    val method = requestLine[0]
    val path = requestLine[1]
    val headers = mutableMapOf<String, String>()
    for (i in 1 until lines.size) {
        val colon = lines[i].indexOf(':')
        if (colon > 0) headers[lines[i].substring(0, colon).trim().lowercase()] =
            lines[i].substring(colon + 1).trim()
    }
    return HttpRequest(method, path, headers, body)
}

// ── Reactor-hosted CouchDB HTTP server ────────────────────────────────────

/**
 * Reactor-driven CouchDB-compatible HTTP server.
 *
 * Ties together:
 *   - [ChannelRunner] — reactor event loop (io_uring / kqueue / poll)
 *   - [ChannelOperations] — socket/bind/listen/accept/read/write
 *   - [ReactorOperations] — event multiplexing
 *   - [CouchElement] — collection lifecycle
 *   - [BlockStore] — persistent document storage (NioBlockStore)
 *   - [NioBlockWal] — write-ahead log for durability
 *   - [CouchQueryServer] — view engine (map/reduce)
 *
 * ## CouchDB REST API (subset implemented)
 *
 *   GET    /{db}                  → list all docs
 *   GET    /{db}/{docId}          → get document
 *   PUT    /{db}/{docId}          → create/update document
 *   DELETE /{db}/{docId}          → delete document
 *   POST   /{db}/_bulk_docs       → bulk insert
 *   GET    /{db}/_design/{ddoc}/_view/{view} → execute view
 *
 * CCEK: register in the coroutine context via [Key].
 */
class ReactorCouchServer(
    private val channelOps: ChannelOperations,
    private val reactorOps: ReactorOperations,
    private val couch: CouchElement,
    private val store: BlockStore,
    private val wal: NioBlockWal,
    private val compileJs: (String) -> CompiledFunction,
    private val port: Int = 5984,
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<ReactorCouchServer>()
    override val key: AsyncContextKey<ReactorCouchServer> get() = Key

    // Event type dispatch tokens — encoded into userData so each SQE is identifiable.
    // Encoding: (eventType shl 32) | (fd and 0xFFFFFFFFL)
    private object EventKey {
        const val ACCEPT: Long = 0L
        const val READ: Long   = 1L
        const val WRITE: Long  = 2L

        fun encode(type: Long, fd: Int): Long = (type shl 32) or (fd.toLong() and 0xFFFFFFFFL)
        fun type(ud: Long): Long = ud ushr 32
        fun fd(ud: Long): Int = (ud and 0xFFFFFFFFL).toInt()
    }

    private val viewServer = CouchQueryServer(compileJs)
    /** db → viewName → (mapSource, reduceSource?) */
    private val viewSources = mutableMapOf<String, MutableMap<String, Pair<String, String?>>>()
    private var serverFd: Int = -1

    /**
     * Per-connection read accumulation: clientFd → bytes received so far.
     * HTTP requests may arrive in fragments; we buffer until we see the full header block.
     */
    private val readAccum = mutableMapOf<Int, MutableList<Byte>>()

    /** Read buffers currently in-flight (one per client fd). */
    private val pendingReadBuffers = mutableMapOf<Int, ByteBuffer>()

    // ── Pattern A lifecycle ────────────────────────────────────────

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        serverFd = channelOps.socket(2, 1, 0)
        check(serverFd >= 0) { "socket failed" }
        channelOps.bind(serverFd, port)
        channelOps.listen(serverFd, 128)
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            supervisor.cancel()
            super.close()
        }
    }

    // ── SupervisorJob looper ────────────────────────────────────────

    /**
     * Start the accept-dispatch loop.
     * Runs until [close] is called or parent scope cancels.
     */
    suspend fun serve(killSignal: kotlinx.coroutines.Job? = null) = withContext(supervisor) {
        requireState(ElementState.ACTIVE)
        val ring = channelOps.openChannel()
        // Arm the first accept SQE.
        ring.prepAccept(serverFd, EventKey.encode(EventKey.ACCEPT, serverFd))
        ring.submit()
        while (isActive) {
            val results = ring.wait()
            for (r in results) {
                when (EventKey.type(r.userData)) {
                    EventKey.ACCEPT -> handleAccept(ring, r)
                    EventKey.READ   -> handleRead(ring, r)
                    EventKey.WRITE  -> handleWrite(r)
                }
            }
            ring.submit()
        }
    }

    // ── Event dispatch ──────────────────────────────────────────────

    private fun handleAccept(ring: ChannelOperations.ChannelHandle, r: ChannelResult) {
        // Re-arm accept for the next connection (persistent multi-shot pattern).
        ring.prepAccept(serverFd, EventKey.encode(EventKey.ACCEPT, serverFd))
        val clientFd = r.res
        if (clientFd >= 0) {
            // Enqueue the first read for this connection.
            val buf = ByteBuffer.allocate(16384)
            pendingReadBuffers[clientFd] = buf
            ring.readv(clientFd, buf, EventKey.encode(EventKey.READ, clientFd))
        }
    }

    private fun handleRead(ring: ChannelOperations.ChannelHandle, r: ChannelResult) {
        val clientFd = EventKey.fd(r.userData)
        if (r.res <= 0) {
            // EOF or error — clean up.
            pendingReadBuffers.remove(clientFd)
            readAccum.remove(clientFd)
            channelOps.close(clientFd)
            return
        }
        val buf = pendingReadBuffers.remove(clientFd) ?: return
        val chunk = buf.array().copyOfRange(0, r.res)
        val accum = readAccum.getOrPut(clientFd) { mutableListOf() }
        accum.addAll(chunk.asList())

        val raw = accum.toByteArray().decodeToString()
        if (!raw.contains("\r\n\r\n")) {
            // Partial request — re-arm read and wait for more data.
            val nextBuf = ByteBuffer.allocate(16384)
            pendingReadBuffers[clientFd] = nextBuf
            ring.readv(clientFd, nextBuf, EventKey.encode(EventKey.READ, clientFd))
            return
        }
        readAccum.remove(clientFd)

        val response = parseHttpRequest(raw)?.let { route(it) }
            ?: "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n"
        val responseBytes = response.encodeToByteArray()
        val writeBuf = ByteBuffer.wrap(responseBytes)
        ring.writev(clientFd, writeBuf, EventKey.encode(EventKey.WRITE, clientFd))
    }

    private fun handleWrite(r: ChannelResult) {
        val clientFd = EventKey.fd(r.userData)
        channelOps.close(clientFd)
    }

    // ── Legacy convenience: start in scope ─────────────────────────

    // ── HTTP routing ───────────────────────────────────────────────

    private fun route(request: HttpRequest): String {
        val (pathPart, queryPart) = request.path.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val path = pathPart.trim('/')
        val segments = path.split('/')
        val queryParams = parseQueryParams(queryPart)

        return when {
            // GET /{db} — list all docs
            segments.size == 1 && request.method == "GET" -> handleListDb(segments[0])
            // GET /{db}/{docId} — get document
            segments.size == 2 && request.method == "GET" -> handleGetDoc(segments[0], segments[1])
            // PUT /{db}/{docId} — create/update document
            segments.size == 2 && request.method == "PUT" -> handlePutDoc(segments[0], segments[1], request.body)
            // DELETE /{db}/{docId}
            segments.size == 2 && request.method == "DELETE" -> handleDeleteDoc(segments[0], segments[1])
            // POST /{db}/_bulk_docs
            segments.size == 3 && segments[1] == "_bulk_docs" && request.method == "POST" ->
                handleBulkDocs(segments[0], request.body)
            // GET /{db}/_design/{ddoc}/_view/{view}
            segments.size == 5 && segments[1] == "_design" && segments[3] == "_view" ->
                handleViewQuery(segments[0], segments[2], segments[4], queryParams)
            else -> """{"error":"not_found","reason":"unknown endpoint ${request.method} $path"}"""
        }
    }

    // ── Document CRUD ──────────────────────────────────────────────

    private fun handleGetDoc(db: String, docId: String): String {
        val block = store.get(db, docId) ?: return """{"error":"not_found","reason":"missing"}"""
        return blockToJson(block)
    }

    private fun handlePutDoc(db: String, docId: String, body: String): String {
        val doc = parseJsonToMap(body)
        val block = docToBlock(doc)
        store.putWithId(db, docId, block)
        wal.appendPut(db, docId, block)
        return """{"ok":true,"id":"$docId","rev":"1-abc"}"""
    }

    private fun handleDeleteDoc(db: String, docId: String): String {
        store.remove(db, docId)
        wal.appendRemove(db, docId)
        return """{"ok":true,"id":"$docId"}"""
    }

    private fun handleListDb(db: String): String {
        val ids = store.list(db)
        val rows = ids.joinToString(",") { """{"id":"$it","key":"$it","value":{"rev":"1-abc"}}""" }
        return """{"total_rows":${ids.size},"offset":0,"rows":[$rows]}"""
    }

    private fun handleBulkDocs(db: String, body: String): String {
        val parsed = parseJsonToMap(body)
        @Suppress("UNCHECKED_CAST")
        val docs = parsed["docs"] as? List<Map<String, Any?>> ?: return """{"error":"bad_request","reason":"missing docs"}"""
        for (doc in docs) {
            val id = doc["_id"] as? String ?: continue
            val block = docToBlock(doc)
            store.putWithId(db, id, block)
            wal.appendPut(db, id, block)
        }
        return """{"ok":true}"""
    }

    // ── View query ─────────────────────────────────────────────────

    fun registerView(db: String, ddoc: String, viewName: String, mapSource: String, reduceSource: String? = null) {
        viewSources.getOrPut(db) { mutableMapOf() }[viewName] = mapSource to reduceSource
    }

    /** Register all cascade kline views for in-process query via [KlineDesignDoc]. */
    fun registerKlineViews(db: String = KlineDesignDoc.DEFAULT_DB) {
        KlineDesignDoc.registerWith(this, db)
    }

    private fun handleViewQuery(db: String, ddoc: String, viewName: String, queryParams: Map<String, String> = emptyMap()): String {
        val entry = viewSources[db]?.get(viewName)
            ?: return """{"error":"not_found","reason":"view $viewName not found in $db"}"""
        val (mapSource, reduceSource) = entry

        // ── Query parameters ───────────────────────────────────────
        val doReduce = queryParams["reduce"]?.lowercase() != "false" && reduceSource != null
        val group = queryParams["group"]?.lowercase() == "true"
        val groupLevel = queryParams["group_level"]?.toIntOrNull()
        val limit = queryParams["limit"]?.toIntOrNull()
        val skip = queryParams["skip"]?.toIntOrNull() ?: 0
        val descending = queryParams["descending"]?.lowercase() == "true"
        val keyFilter = queryParams["key"]?.let { parseJsonValue(urlDecode(it)) }
        val startKey = queryParams["startkey"]?.let { parseJsonValue(urlDecode(it)) }
        val endKey = queryParams["endkey"]?.let { parseJsonValue(urlDecode(it)) }

        // ── Map phase ──────────────────────────────────────────────
        viewServer.handle(CouchCommand.Reset)
        viewServer.handle(CouchCommand.AddFun(mapSource))

        data class Row(val id: String, val key: Any?, val value: Any?)
        val rows = mutableListOf<Row>()
        for (id in store.list(db)) {
            val block = store.get(db, id) ?: continue
            val doc = blockToDoc(block)
            val response = viewServer.handle(CouchCommand.MapDoc(doc))
            if (response is CouchResponse.MapResults) {
                for (kv in response.perFunction.firstOrNull() ?: emptyList()) {
                    rows.add(Row(id, kv.getOrNull(0), kv.getOrNull(1)))
                }
            }
        }

        // ── Filter ────────────────────────────────────────────────
        val filtered = rows.filter { row ->
            when {
                keyFilter != null -> compareKeys(row.key, keyFilter) == 0
                startKey != null || endKey != null -> {
                    val afterStart = startKey == null || compareKeys(row.key, startKey) >= 0
                    val beforeEnd = endKey == null || compareKeys(row.key, endKey) <= 0
                    afterStart && beforeEnd
                }
                else -> true
            }
        }

        // ── Sort ──────────────────────────────────────────────────
        val sorted = if (descending)
            filtered.sortedWith { a, b -> compareKeys(b.key, a.key) }
        else
            filtered.sortedWith { a, b -> compareKeys(a.key, b.key) }

        val paged = sorted.drop(skip)

        if (!doReduce) {
            val limited = if (limit != null) paged.take(limit) else paged
            val rowJson = limited.joinToString(",") { row ->
                """{"id":"${row.id}","key":${jsonValue(row.key)},"value":${jsonValue(row.value)}}"""
            }
            return """{"total_rows":${filtered.size},"offset":$skip,"rows":[$rowJson]}"""
        }

        // ── Reduce phase ───────────────────────────────────────────
        // reduceFn non-null: doReduce = true implies reduceSource != null
        val reduceFn = reduceSource
        viewServer.handle(CouchCommand.Reset)
        viewServer.handle(CouchCommand.AddFun(reduceFn))

        if (group || groupLevel != null) {
            val level = groupLevel ?: Int.MAX_VALUE
            val grouped = LinkedHashMap<Any?, MutableList<Any?>>()
            for (row in paged) {
                val gk = keyPrefix(row.key, level)
                grouped.getOrPut(gk) { mutableListOf() }.add(row.value)
            }
            var groupedEntries = grouped.entries.toList()
            if (descending) groupedEntries = groupedEntries.reversed()
            val limitedGroups = if (limit != null) groupedEntries.take(limit) else groupedEntries

            val rowJson = limitedGroups.joinToString(",") { (gk, values) ->
                val r = viewServer.handle(CouchCommand.Reduce(listOf(reduceFn), values))
                val result = if (r is CouchResponse.ReduceResult) r.value else null
                """{"key":${jsonValue(gk)},"value":${jsonValue(result)}}"""
            }
            return """{"rows":[$rowJson]}"""
        } else {
            val values = paged.map { it.value }
            val r = viewServer.handle(CouchCommand.Reduce(listOf(reduceFn), values))
            val result = if (r is CouchResponse.ReduceResult) r.value else null
            return """{"rows":[{"key":null,"value":${jsonValue(result)}}]}"""
        }
    }

    // ── JSON helpers ───────────────────────────────────────────────

    private fun parseJsonToMap(json: String): Map<String, Any?> {
        val ctx = contextOf(Syntax.JSON, json.toSeries())
        @Suppress("UNCHECKED_CAST")
        return Combinators.reify(ctx) as? Map<String, Any?> ?: emptyMap()
    }

    private fun parseJsonValue(json: String): Any? {
        val ctx = contextOf(Syntax.JSON, json.toSeries())
        return Combinators.reify(ctx)
    }

    // ── Query param helpers ────────────────────────────────────────

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (pair.isBlank()) null
            else if (eq < 0) urlDecode(pair) to ""
            else urlDecode(pair.substring(0, eq)) to urlDecode(pair.substring(eq + 1))
        }.toMap()
    }

    /** CouchDB collation rank: null=0 false=1 true=2 Number=3 String=4 Array=5 Object=6 */
    private fun collationRank(v: Any?): Int = when (v) {
        null -> 0
        is Boolean -> if (v) 2 else 1
        is Number -> 3
        is String -> 4
        is List<*> -> 5
        is Map<*, *> -> 6
        else -> 4
    }

    private fun compareKeys(a: Any?, b: Any?): Int {
        val ra = collationRank(a); val rb = collationRank(b)
        if (ra != rb) return ra.compareTo(rb)
        return when {
            a == null -> 0
            a is Boolean && b is Boolean -> a.compareTo(b)
            a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
            a is String && b is String -> a.compareTo(b)
            a is List<*> && b is List<*> -> {
                val minLen = minOf(a.size, b.size)
                for (i in 0 until minLen) {
                    val c = compareKeys(a[i], b[i]); if (c != 0) return c
                }
                a.size.compareTo(b.size)
            }
            else -> jsonValue(a).compareTo(jsonValue(b))
        }
    }

    private fun keyPrefix(key: Any?, level: Int): Any? = when {
        key is List<*> && level < key.size -> key.take(level)
        else -> key
    }

    // ── Document ↔ JSON ↔ BlockStore helpers ──────────────────────

    private fun docToBlock(doc: Map<String, Any?>): BlockRowVec {
        // Use MiniDuckBlockCodec round-trip: Map → JSON → BlockRowVec
        val json = mapToJson(doc)
        return borg.trikeshed.miniduck.MiniDuckBlockCodec.decode(json)
    }

    private fun blockToDoc(block: BlockRowVec): Map<String, Any?> {
        val json = borg.trikeshed.miniduck.MiniDuckBlockCodec.encode(block)
        return parseJsonToMap(json)
    }

    private fun blockToJson(block: BlockRowVec): String {
        return borg.trikeshed.miniduck.MiniDuckBlockCodec.encode(block)
    }

    private fun mapToJson(map: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        map.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(",")
            sb.append("\"$k\":${jsonValue(v)}")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun jsonValue(v: Any?): String = JsonSerializer.serializeValue(v)

    private fun String.toSeries(): Series<Char> {
        val n = length
        return n j { i: Int -> this[i] }
    }
}
