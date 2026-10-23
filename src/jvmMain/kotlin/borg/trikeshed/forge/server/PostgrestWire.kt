package borg.trikeshed.forge.server

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchTables
import borg.trikeshed.cursor.query.Catalog
import borg.trikeshed.cursor.query.CatalogTable
import borg.trikeshed.cursor.query.PostgrestSurface
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.parse.reify

/**
 * PostgrestWire — Supabase's data API on the kanban listener, over SQL tables kept as documents in
 * the primary Couch database (`tables/<table>/<id>`, see [CouchTables]):
 *
 *   GET|HEAD|POST|PATCH|DELETE|OPTIONS  /rest/v1/<table>     PostgREST ([PostgrestSurface])
 *   POST|GET                            /rest/v1/rpc/<fn>    match_documents over vector columns
 *   GET                                 /rest/v1/            OpenAPI 2.0
 *   POST                                /pg/query            pg-meta {"query": "..."}: SELECT, CREATE/DROP TABLE
 *
 * A static extra route, as PatchWire's `/v1/` family: module claims are exact `/api/` paths and
 * PostgREST's are a prefix family. The listener writes Content-Type and the CORS origin itself.
 */
class PostgrestWire(couch: Couch, extra: () -> Iterable<CatalogTable> = { emptyList() }) {
    val tables = CouchTables(couch)
    val surface = PostgrestSurface(Catalog(listOf({ tables.tables() }, extra)), tables)

    suspend fun route(
        method: String,
        path: String,
        text: String,
        @Suppress("UNUSED_PARAMETER") respond: (suspend (ByteArray) -> Unit)?,
    ): WireHttpResponse? {
        val p = path.substringBefore('?')
        val reply = when {
            p == "/rest/v1" || p.startsWith("/rest/v1/") -> surface.handle(method, path, requestHeaders(text), requestBody(text))
            p == "/pg/query" && method == "POST" ->
                surface.sql((runCatching { reify(requestBody(text)) }.getOrNull() as? Map<*, *>)?.get("query")?.toString().orEmpty())
            else -> return null
        }
        return WireHttpResponse(reply.status, reply.body, reply.contentType, headers = reply.headers - "Content-Type" - "Access-Control-Allow-Origin")
    }

}

/** The header lines of a raw request, after the request line. */
fun requestHeaders(text: String): Map<String, String> =
    text.substringBefore("\r\n\r\n").split("\r\n").drop(1).mapNotNull { line ->
        line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() }
    }.toMap()

/** The body of a raw request: everything after the blank line. */
fun requestBody(text: String): String = text.substringAfter("\r\n\r\n", "")
