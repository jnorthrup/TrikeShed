package borg.trikeshed.rdf

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.lib.*

/**
 * The SPARQL 1.1 Protocol as Virtuoso serves it at `/sparql`, over a [QuadStore]; commonMain, no
 * sockets — the daemon's wire hands `(method, path, headers, body)` in.
 *
 *   GET  /sparql?query=…&default-graph-uri=…&named-graph-uri=…[&format=json|csv|tsv]
 *   POST /sparql   application/x-www-form-urlencoded (query= | update=), application/sparql-query,
 *                  application/sparql-update
 *
 * Results negotiate `format=`/`output=` first, then `Accept`: `application/sparql-results+json`
 * (the default), `text/csv`, `text/tab-separated-values`. An update (`INSERT DATA`, `DELETE DATA`)
 * writes the store and answers the counts. A refused query is `400 text/plain` in Virtuoso's
 * `37000 Error SP030` wording. [live] rows (a fact plane read on each request) are searched beside
 * the store's own.
 */
class SparqlSurface(val store: QuadStore, val compiler: ExprCompiler = ClosureExprCompiler) {
    fun handle(method: String, rawPath: String, headers: Map<String, String>, body: String, live: Cursor? = null): PostgrestReply {
        val h = headers.entries.associate { it.key.lowercase() to it.value.trim() }
        val params = decodeQuery(rawPath.substringAfter('?', "")).toMutableList()
        val contentType = h["content-type"].orEmpty().lowercase()
        var query: String? = null
        var update: String? = null
        when (method) {
            "GET" -> Unit
            "POST" -> when {
                contentType.startsWith("application/sparql-query") -> query = body
                contentType.startsWith("application/sparql-update") -> update = body
                else -> params += decodeQuery(body)
            }
            "OPTIONS" -> return PostgrestReply(200, "", mapOf("Allow" to "OPTIONS, GET, POST"))
            else -> return PostgrestReply(405, "$method is not allowed on $PATH; use GET or POST", mapOf("Content-Type" to "text/plain; charset=utf-8", "Allow" to "GET, POST"))
        }
        query = query ?: params.lastOrNull { it.first == "query" }?.second
        update = update ?: params.lastOrNull { it.first == "update" }?.second
        if (query != null && update != null) return refuse(400, "a request carries a query or an update, not both")
        if (update != null && method != "POST") return refuse(400, "an update is sent by POST")
        val defaults = params.filter { it.first == "default-graph-uri" && it.second.isNotBlank() }.map { it.second }
        val named = params.filter { it.first == "named-graph-uri" && it.second.isNotBlank() }.map { it.second }
        val format = params.lastOrNull { it.first == "format" || it.first == "output" }?.second
        return when {
            update != null -> apply(update)
            query != null -> answer(query, defaults, named, format, h["accept"].orEmpty(), live)
            else -> refuse(400, "missing query parameter")
        }
    }

    fun answer(text: String, defaults: List<String>, named: List<String>, format: String?, accept: String, live: Cursor? = null): PostgrestReply {
        val query = try { SparqlQuery.parse(text, defaults, named) } catch (e: SparqlError) { return refuse(400, "Virtuoso 37000 Error SP030: SPARQL compiler, ${e.message}") }
        val media = (if (query.graphResult) graphMediaType(format, accept) else mediaType(format, accept))
            ?: return refuse(406, "none of these media types are available: ${format ?: accept}")
        // The plan and its rows are produced here, so a planner's or evaluator's refusal is this reply, never a dropped request.
        val body = try {
            val source = quads(live)
            val rows = query.plan(source, compiler = compiler)
            when {
                query.graphResult -> query.triples(rows, source).let { if (media == SparqlResults.NTRIPLES) SparqlResults.nTriples(it) else SparqlResults.turtle(it) }
                query.ask -> when (media) {
                    SparqlResults.JSON -> SparqlResults.askJson(rows.size > 0)
                    else -> (rows.size > 0).toString()
                }
                media == SparqlResults.CSV -> SparqlResults.csv(rows, query.variables)
                media == SparqlResults.TSV -> SparqlResults.tsv(rows, query.variables)
                else -> SparqlResults.json(rows, query.variables)
            }
        } catch (e: Exception) {
            return refuse(400, "Virtuoso 37000 Error SP031: SPARQL compiler: ${e.message ?: e::class.simpleName}")
        }
        return PostgrestReply(200, body, mapOf("Content-Type" to "$media; charset=utf-8"))
    }

    /** `INSERT DATA` / `DELETE DATA` against the store; the counts come back as JSON. */
    fun apply(text: String): PostgrestReply {
        val update = try { SparqlUpdate.parse(text) } catch (e: SparqlError) { return refuse(400, "Virtuoso 37000 Error SP030: SPARQL compiler, ${e.message}") }
        val (inserted, deleted) = update.apply(store)
        return PostgrestReply(200, "{\"inserted\":$inserted,\"deleted\":$deleted,\"size\":${store.size}}")
    }

    /** The store's quads, with [live] rows beside them when a fact plane is read on the request. */
    fun quads(live: Cursor?): Cursor = if (live == null || live.size == 0) store.scan() else combine(store.scan(), live)

    /** The result media type for `format=`/`output=` (first) or `Accept`; null when neither names one served here. */
    fun mediaType(format: String?, accept: String): String? {
        if (format != null) return when (format.lowercase().substringBefore(';').trim().replace(' ', '+')) {
            "json", "application/sparql-results+json", "application/json" -> SparqlResults.JSON
            "csv", "text/csv" -> SparqlResults.CSV
            "tsv", "text/tab-separated-values" -> SparqlResults.TSV
            else -> null
        }
        val a = accept.lowercase()
        return when {
            SparqlResults.JSON in a || "application/json" in a -> SparqlResults.JSON
            SparqlResults.CSV in a -> SparqlResults.CSV
            SparqlResults.TSV in a -> SparqlResults.TSV
            a.isBlank() || "*/*" in a -> SparqlResults.JSON
            "text/*" in a -> SparqlResults.CSV
            else -> null
        }
    }

    /** The media type of a CONSTRUCT or DESCRIBE answer: Turtle unless N-Triples is asked for; null for anything else named. */
    fun graphMediaType(format: String?, accept: String): String? {
        if (format != null) return when (format.lowercase().substringBefore(';').trim().replace(' ', '+')) {
            "turtle", "ttl", "text/turtle" -> SparqlResults.TURTLE
            "ntriples", "nt", "n-triples", "application/n-triples", "text/plain" -> SparqlResults.NTRIPLES
            else -> null
        }
        val a = accept.lowercase()
        return when {
            SparqlResults.NTRIPLES in a || "text/plain" in a -> SparqlResults.NTRIPLES
            SparqlResults.TURTLE in a || a.isBlank() || "*/*" in a || "text/*" in a -> SparqlResults.TURTLE
            else -> null
        }
    }

    fun refuse(status: Int, message: String): PostgrestReply = PostgrestReply(status, message, mapOf("Content-Type" to "text/plain; charset=utf-8"))

    companion object {
        const val PATH = "/sparql"
    }
}
