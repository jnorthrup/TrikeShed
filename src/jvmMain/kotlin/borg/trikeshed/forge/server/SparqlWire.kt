package borg.trikeshed.forge.server

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.ReifiedSplitSeries2
import borg.trikeshed.cursor.query.*
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.lcnc.SparqlGraph
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.kursive.sql.scalarText
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.rdf.CypherSurface
import borg.trikeshed.rdf.SparqlQuery
import borg.trikeshed.rdf.SparqlSource
import borg.trikeshed.rdf.SparqlSurface
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SparqlWire — Virtuoso's SPARQL endpoint on the kanban listener:
 *
 *   GET|POST  /sparql              SPARQL 1.1 protocol and SPARQL Update over the daemon's quad store ([SparqlSurface])
 *   POST      /sparql/visual       Read-only LCNC syntax graph, with an explicitly selected dataset
 *   POST      /sql/visual          Bounded read-only SQL syntax graph over the quad store
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
            p == "/sql/visual" -> return VisualSqlWire.query(method, requestBody(text), surface.store, gate)
            p == "/sparql/visual" -> return visual(method, requestBody(text))
            p == SparqlSurface.PATH -> {
                val selected = try { surface.requestedSource(path, requestHeaders(text), requestBody(text)) }
                    catch (e: IllegalArgumentException) {
                        return WireHttpResponse(400, e.message ?: "Invalid SPARQL source", "text/plain; charset=utf-8")
                    }
                if (selected == SparqlSource.RETE && live == null)
                    return WireHttpResponse(400, "Rete projection is unavailable", "text/plain; charset=utf-8")
                val rows = if ((selected ?: SparqlSource.UNION).projected) live?.invoke() else null
                gate.withLock { surface.handle(method, path, requestHeaders(text), requestBody(text), rows) }
            }
            cypher != null && CypherSurface.PATH.matches(p) -> { val rows = live?.invoke(); gate.withLock { cypher.handle(method, path, requestHeaders(text), requestBody(text), rows) } }
            else -> return null
        }
        return WireHttpResponse(reply.status, reply.body, reply.contentType, headers = reply.headers - "Content-Type" - "Access-Control-Allow-Origin")
    }

    /** Query a syntax specimen without dispatching it as an LCNC procedure or allowing SPARQL Update. */
    private suspend fun visual(method: String, body: String): WireHttpResponse {
        fun error(status: Int, message: String) = WireHttpResponse(status, jsonOf(mapOf("error" to message)), "application/json; charset=utf-8")
        if (method != "POST") return error(405, "Visual queries require POST")
        if (body.length > VISUAL_BODY_BYTES || body.encodeToByteArray().size > VISUAL_BODY_BYTES)
            return error(413, "Visual query document is too large")
        if (!visualDepthAllowed(body)) return error(413, "Visual query JSON exceeds the nesting limit or is malformed")
        val request = try { reifyStrict(body) as? Map<*, *> ?: return error(400, "Expected a JSON object") }
            catch (e: Exception) { return error(400, "Invalid JSON: ${e.message}") }
        val dataset = request["dataset"] as? String ?: return error(400, "Select a dataset")
        val selected = try { SparqlSource.of(dataset) } catch (_: IllegalArgumentException) { return error(400, "Unknown dataset") }
        if (selected.projected && live == null) return error(400, "Rete overlay is unavailable")
        val candidateVariable = when (val value = request["candidateVariable"]) {
            null -> null
            is String -> value.removePrefix("?").takeIf { it.isNotBlank() }
                ?: return error(400, "Candidate variable is empty")
            else -> return error(400, "Candidate variable must be a string")
        }
        if (candidateVariable != null && !selected.projected)
            return error(400, "Candidate details require the live Rete dataset")
        val document = request["program"] as? Map<*, *> ?: return error(400, "Expected a program object")
        fun count(value: Any?): Int = when (value) {
            is List<*> -> value.size
            is Array<*> -> value.size
            else -> 0 // the program decoder will report malformed fields
        }
        if (count(document["nodes"]) > VISUAL_NODES || count(document["wires"]) > VISUAL_NODES)
            return error(413, "Visual query has too many nodes or wires")
        val query = try { SparqlGraph.lower(LcncProgramConfix.fromJson("visual query", jsonOf(document))) }
            catch (e: Exception) { return error(400, e.message ?: "Invalid visual query") }
        if (query.graph.patterns.size > VISUAL_PATTERNS) return error(413, "Visual query has too many patterns")
        if (candidateVariable != null && candidateVariable !in query.variables)
            return error(400, "Candidate variable must be projected by the query")
        if (query.graph.variables().any { it.length > VISUAL_VARIABLE_CHARS } ||
            query.variables.any { it.length > VISUAL_VARIABLE_CHARS })
            return error(413, "Visual query variable name is too long")
        if (query.text.length > VISUAL_QUERY_CHARS) return error(413, "Visual query text is too large")
        val rows = if (selected.projected) live?.invoke() else null
        return gate.withLock {
            // Avoid constructing the combined cursor when the selected inputs already exceed the budget.
            if ((if (selected.stored) surface.store.size.toLong() else 0L) + (rows?.size ?: 0).toLong() > VISUAL_SOURCE_ROWS)
                return@withLock error(413, "Selected dataset exceeds the visual query source limit")
            val checkedLive: Cursor?
            val source: Cursor
            try {
                checkedLive = if (candidateVariable != null) rows?.let(::visualSnapshot) else rows
                source = visualSnapshot(surface.quads(checkedLive, selected))
            } catch (limit: VisualLimit) { return@withLock error(413, limit.message ?: "Visual source limit") }
              catch (failure: Exception) { return@withLock error(400, failure.message ?: "Invalid quad source") }
            val refusal = try { visualWorkRefusal(query, source, candidateVariable != null) }
                catch (e: Exception) { return@withLock error(400, e.message ?: "Invalid quad source") }
            if (refusal != null) return@withLock error(413, refusal)
            val result = surface.answer(query, source, "json", "")
            if (result.status != 200) error(result.status, result.body)
            else {
                val results = reifyStrict(result.body)
                val candidateContext = if (candidateVariable == null) emptyMap() else try {
                    mapOf("candidateContext" to VisualCandidateContext.extract(candidateVariable, results, requireNotNull(checkedLive)))
                } catch (e: Exception) { return@withLock error(400, e.message ?: "Cannot inspect live proposal fields") }
                val response = jsonOf(mapOf("dataset" to dataset, "query" to query.text,
                    "results" to results, "source" to selected.descriptor(rows != null)) + candidateContext)
                if (response.encodeToByteArray().size > VISUAL_RESPONSE_BYTES)
                    error(413, "Visual query result exceeds the response size limit")
                else WireHttpResponse(200, response, "application/json; charset=utf-8")
            }
        }
    }

    companion object {
        private const val VISUAL_BODY_BYTES = 65_536
        private const val VISUAL_DEPTH = 64
        private const val VISUAL_NODES = 128
        private const val VISUAL_PATTERNS = 8
        private const val VISUAL_VARIABLE_CHARS = 128
        private const val VISUAL_QUERY_CHARS = 65_536
        private const val VISUAL_SOURCE_ROWS = 20_000L
        private const val VISUAL_JOIN_ROWS = 100_000L
        private const val VISUAL_TERM_CHARS = 4_096
        private const val VISUAL_SOURCE_CHARS = 4_000_000L
        // Four candidate IRIs (at most VISUAL_TERM_CHARS each) and at most two
        // 128-character values for each whitelisted field, including JSON escaping.
        private const val VISUAL_CANDIDATE_RESERVE_BYTES = 300_000L
        // Below the canvas PatchRead limit of 1 MiB, including the query and response envelope.
        private const val VISUAL_RESPONSE_BYTES = 900_000L

        /** Bound nesting before the recursive JSON and LCNC parsers see the input. */
        private fun visualDepthAllowed(body: String): Boolean {
            var depth = 0
            var quoted = false
            var escaped = false
            for (ch in body) {
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') quoted = false
                } else when (ch) {
                    '"' -> quoted = true
                    '{', '[' -> if (++depth > VISUAL_DEPTH) return false
                    '}', ']' -> if (--depth < 0) return false
                }
            }
            return true // strict parsing diagnoses incomplete strings and mismatched delimiters
        }

        private class VisualLimit(message: String) : IllegalArgumentException(message)

        /** Capture bounded immutable quad lexemes once, before either the guard or executor reads them. */
        private fun visualSnapshot(source: Cursor): Cursor {
            if (source.size.toLong() > VISUAL_SOURCE_ROWS) throw VisualLimit("Selected dataset exceeds the visual query source limit")
            if (source.size == 0) return emptySeriesOf()
            val names = source.names
            val ord = IntArray(4) { i -> (0 until names.size).firstOrNull {
                names[it].contentEquals(QUAD_COLUMNS[i]) || names[it].endsWith("." + QUAD_COLUMNS[i])
            } ?: error("a graph pattern needs a ${QUAD_COLUMNS[i]} column") }
            val cells = Array(4) { arrayOfNulls<String>(source.size) }
            var chars = 0L
            for (row in 0 until source.size) {
                val values = source[row].values
                for (column in 0..3) {
                    val raw = values[ord[column]] ?: continue
                    if (raw is CharSequence && raw.length > VISUAL_TERM_CHARS)
                        throw VisualLimit("Selected dataset has a term too large for visual query")
                    val cell = scalarText(raw)
                    if (cell.length > VISUAL_TERM_CHARS) throw VisualLimit("Selected dataset has a term too large for visual query")
                    chars += cell.length
                    if (chars > VISUAL_SOURCE_CHARS) throw VisualLimit("Selected dataset exceeds the visual query text limit")
                    cells[column][row] = cell
                }
            }
            val metas = 4 j { column: Int -> ColumnMeta(QUAD_COLUMNS[column], IOMemento.IoString).let { metadata -> { metadata } } }
            return source.size j { row: Int -> ReifiedSplitSeries2(4 j { column: Int -> cells[column][row] as Any? }, metas) }
        }

        /**
         * Every prior binding matches at most the largest bucket keyed by all already-bound
         * variables. Multi-valued keys retain their full fanout; an unshared pattern stays a
         * Cartesian product. Per-variable widths bound final projection values independently
         * of unrelated source literals. These are upper bounds, not estimates or functional keys.
         */
        private fun visualWorkRefusal(query: SparqlQuery, source: Cursor, withCandidates: Boolean): String? {
            val fixed = query.text.length.toLong() * 6L + query.variables.sumOf { it.length.toLong() * 6L } + 8_192L +
                if (withCandidates) VISUAL_CANDIDATE_RESERVE_BYTES else 0L
            if (fixed >= VISUAL_RESPONSE_BYTES) return "Visual query may exceed the response size limit"
            if (source.size == 0) return null
            val boundVariables = mutableSetOf<String>()
            val variableLengths = mutableMapOf<String, Int>()
            var bound = 1L
            for (pattern in query.graph.patterns.view) {
                val slots = pattern.slots
                val variables = slots.map { it?.takeIf { term -> term.isVariable }?.name }
                val shared = variables.filterNotNull().filter { it in boundVariables }.distinct()
                val keySlots = shared.map { name -> variables.indexOf(name) }
                val buckets = mutableMapOf<String, Int>()
                val lengths = mutableMapOf<String, Int>()
                var fanout = 0
                row@ for (row in 0 until source.size) {
                    val values = source[row].values
                    val graph = values[0] as? String ?: continue
                    if (!query.graph.allows(pattern, graph)) continue
                    for (slot in 0..3) {
                        val cell = values[slot] as? String ?: continue@row
                        val constant = slots[slot]?.takeIf { !it.isVariable }?.text
                        if (constant != null && constant != cell) continue@row
                        val variable = variables[slot]
                        if (variable != null) for (other in slot + 1..3)
                            if (variables[other] == variable && values[other] != cell) continue@row
                    }
                    val key = graphJoinKey(keySlots.size) { values[keySlots[it]] as String }
                    val count = (buckets[key] ?: 0) + 1
                    buckets[key] = count; fanout = maxOf(fanout, count)
                    for (slot in 0..3) variables[slot]?.let { name ->
                        lengths[name] = maxOf(lengths[name] ?: 0, (values[slot] as String).length)
                    }
                }
                if (fanout > 0 && bound > VISUAL_JOIN_ROWS / fanout)
                    return "Visual query may exceed the intermediate join limit; narrow its patterns"
                bound *= fanout.toLong()
                for (name in variables.filterNotNull()) {
                    boundVariables.add(name)
                    variableLengths[name] = minOf(variableLengths[name] ?: Int.MAX_VALUE, lengths[name] ?: 0)
                }
            }
            val width = 1L + query.variables.sumOf { (it.length.toLong() + (variableLengths[it] ?: 0).toLong()) * 6L + 160L }
            if (bound > (VISUAL_RESPONSE_BYTES - fixed) / width)
                return "Visual query may exceed the result size limit; narrow its patterns"
            return null
        }

        val ROUTES = listOf("GET" to "/sparql", "POST" to "/sparql", "POST" to "/sparql/visual", "POST" to "/sql/visual", "POST" to "/db/{db}/tx/commit")
    }
}
