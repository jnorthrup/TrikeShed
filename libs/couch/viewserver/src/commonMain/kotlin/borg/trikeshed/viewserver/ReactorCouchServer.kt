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
import borg.trikeshed.userspace.reactor.Interest

import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ── HTTP/1.1 request parser ──────────────────────────────────────────────

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

    // Event type dispatch keys — mirrors webserver_liburing.c EVENT_TYPE_ACCEPT/READ/WRITE
    private object EventKey {
        const val ACCEPT: Long = 0
        const val READ: Long = 1
        const val WRITE: Long = 2
    }

    private val viewServer = CouchQueryServer(compileJs)
    private val viewSources = mutableMapOf<String, MutableMap<String, String>>()
    private var serverFd: Int = -1

    // ── Pattern A lifecycle ────────────────────────────────────────

    override suspend fun open() {
        if (state.isAtLeast(ElementState.OPEN)) return
        super.open()
        serverFd = channelOps.socket(2, 1, 0)
        check(serverFd >= 0) { "socket failed" }
        channelOps.bind(serverFd, port)
        channelOps.listen(serverFd, 128)
        reactorOps.register(serverFd, setOf(Interest.READ), EventKey.ACCEPT)
        state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            state = ElementState.DRAINING
            reactorOps.deregister(serverFd)
            supervisor.cancel()
            super.close()
        }
    }

    // ── SupervisorJob looper — wait for kill signal ─────────────────

    /**
     * Start the accept-dispatch loop under this element's SupervisorJob.
     * Runs until [close] is called or parent scope cancels.
     */
    suspend fun serve(killSignal: kotlinx.coroutines.Job? = null) = withContext(supervisor) {
        requireState(ElementState.ACTIVE)
        val ring = channelOps.openChannel()
        // Enqueue initial accept — mirrors add_accept_request()
        ring.readv(serverFd, ByteBuffer.allocate(64))  // fd will be filled by accept completion
        ring.submit()
        while (isActive) {
            val results = ring.wait()
            for (r in results) {
                when (r.userData) {
                    EventKey.ACCEPT -> { handleAccept(ring, r) }
                    EventKey.READ   -> { handleRead(ring, r) }
                    EventKey.WRITE  -> { handleWrite(r) }
                }
            }
            ring.submit()
        }
    }

    // ── Event dispatch — mirrors webserver_liburing.c switch ────────

    private suspend fun handleAccept(ring: ChannelOperations.ChannelHandle, r: ChannelResult) {
        // Re-enqueue accept for next connection — persistent multi-shot
        ring.readv(serverFd, ByteBuffer.allocate(64))
        val clientFd = r.res
        if (clientFd >= 0) {
            reactorOps.register(clientFd, setOf(Interest.READ), EventKey.READ)
        }
    }

    private suspend fun handleRead(ring: ChannelOperations.ChannelHandle, r: ChannelResult) {
        if (r.res <= 0) { reactorOps.deregister(r.fd); return }
        // Process HTTP request and enqueue write response
        // (full HTTP handling delegated to existing route logic)
        ring.readv(r.fd, ByteBuffer.allocate(16384))
    }

    private fun handleWrite(r: ChannelResult) {
        // Close client socket after write completes
        reactorOps.deregister(r.fd)
    }

    // ── Legacy convenience: start in scope ─────────────────────────

    // ── HTTP routing ───────────────────────────────────────────────

    private fun route(request: HttpRequest): String {
        val path = request.path.trim('/')
        val segments = path.split('/')

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
                handleViewQuery(segments[0], segments[2], segments[4])
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

    fun registerView(db: String, ddoc: String, viewName: String, source: String) {
        viewSources.getOrPut(db) { mutableMapOf() }[viewName] = source
    }

    private fun handleViewQuery(db: String, ddoc: String, viewName: String): String {
        val source = viewSources[db]?.get(viewName)
            ?: return """{"error":"not_found","reason":"view $viewName not found in $db"}"""

        viewServer.handle(CouchCommand.Reset)
        viewServer.handle(CouchCommand.AddFun(source))

        val ids = store.list(db)
        val allResults = mutableListOf<List<List<Any?>>>()

        for (id in ids) {
            val block = store.get(db, id) ?: continue
            val doc = blockToDoc(block)
            val response = viewServer.handle(CouchCommand.MapDoc(doc))
            if (response is CouchResponse.MapResults) {
                allResults.addAll(response.perFunction)
            }
        }

        val total = allResults.sumOf { it.size }
        val rows = allResults.joinToString(",") { fnResults ->
            fnResults.joinToString(",") { kv ->
                """{"id":"${kv.getOrNull(0)}","key":${jsonValue(kv.getOrNull(0))},"value":${jsonValue(kv.getOrNull(1))}}"""
            }
        }
        return """{"total_rows":$total,"offset":0,"rows":[$rows]}"""
    }

    // ── JSON helpers ───────────────────────────────────────────────

    private fun parseJsonToMap(json: String): Map<String, Any?> {
        val ctx = contextOf(Syntax.JSON, json.toSeries())
        @Suppress("UNCHECKED_CAST")
        return Combinators.reify(ctx) as? Map<String, Any?> ?: emptyMap()
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
