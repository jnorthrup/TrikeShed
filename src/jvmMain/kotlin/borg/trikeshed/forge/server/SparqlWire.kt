package borg.trikeshed.forge.server

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.rdf.CypherSurface
import borg.trikeshed.rdf.SparqlSurface
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SparqlWire — Virtuoso's SPARQL endpoint on the kanban listener:
 *
 *   GET|POST  /sparql              SPARQL 1.1 protocol and SPARQL Update over the daemon's quad store ([SparqlSurface])
 *   POST      /db/{db}/tx/commit    Neo4j's HTTP API: Cypher MATCH statements over the same store ([CypherSurface])
 *
 * A static extra route, as PostgrestWire: module claims are exact `/api/` paths. [live] is read on
 * each request — the fact plane's triples — and searched beside the store's own quads. Requests run
 * one at a time under [gate]: the store is not synchronised, and an update may not interleave a read.
 */
class SparqlWire(val surface: SparqlSurface, val cypher: CypherSurface? = null, val live: (suspend () -> Cursor)? = null) {
    val gate = Mutex()

    suspend fun route(
        method: String,
        path: String,
        text: String,
        @Suppress("UNUSED_PARAMETER") respond: (suspend (ByteArray) -> Unit)?,
    ): WireHttpResponse? {
        val p = path.substringBefore('?')
        val reply = when {
            p == SparqlSurface.PATH -> { val rows = live?.invoke(); gate.withLock { surface.handle(method, path, requestHeaders(text), requestBody(text), rows) } }
            cypher != null && CypherSurface.PATH.matches(p) -> { val rows = live?.invoke(); gate.withLock { cypher.handle(method, path, requestHeaders(text), requestBody(text), rows) } }
            else -> return null
        }
        return WireHttpResponse(reply.status, reply.body, reply.contentType, headers = reply.headers - "Content-Type" - "Access-Control-Allow-Origin")
    }

    companion object {
        val ROUTES = listOf("GET" to "/sparql", "POST" to "/sparql", "POST" to "/db/{db}/tx/commit")
    }
}
