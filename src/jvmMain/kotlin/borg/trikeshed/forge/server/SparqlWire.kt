package borg.trikeshed.forge.server

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.rdf.SparqlSurface
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SparqlWire — Virtuoso's SPARQL endpoint on the kanban listener:
 *
 *   GET|POST  /sparql    SPARQL 1.1 protocol and SPARQL Update over the daemon's quad store ([SparqlSurface])
 *
 * A static extra route, as PostgrestWire: module claims are exact `/api/` paths. [live] is read on
 * each request — the fact plane's triples — and searched beside the store's own quads. Requests run
 * one at a time under [gate]: the store is not synchronised, and an update may not interleave a read.
 */
class SparqlWire(val surface: SparqlSurface, val live: (suspend () -> Cursor)? = null) {
    val gate = Mutex()

    suspend fun route(
        method: String,
        path: String,
        text: String,
        @Suppress("UNUSED_PARAMETER") respond: (suspend (ByteArray) -> Unit)?,
    ): WireHttpResponse? {
        if (path.substringBefore('?') != SparqlSurface.PATH) return null
        val rows = live?.invoke()
        val reply = gate.withLock { surface.handle(method, path, requestHeaders(text), requestBody(text), rows) }
        return WireHttpResponse(reply.status, reply.body, reply.contentType, headers = reply.headers - "Content-Type" - "Access-Control-Allow-Origin")
    }

    companion object {
        val ROUTES = listOf("GET" to "/sparql", "POST" to "/sparql")
    }
}
