package borg.trikeshed.forge.server

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.parse.kursive.sql.*
import borg.trikeshed.rdf.QuadStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A small read-only SQL specimen over a copied quad cursor. This is not a general SQL endpoint. */
internal object VisualSqlWire {
    private const val BODY_BYTES = 65_536
    private const val DEPTH = 32
    private const val NODES = 32
    private const val SOURCE_ROWS = 1_000
    private const val SOURCE_TERMS = 4_096
    private const val TERM_CHARS = 512
    private const val SOURCE_CHARS = 1_000_000L
    private const val COLUMNS = 8
    private const val RESPONSE_BYTES = 900_000L
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val allowed = _s[SqlGraph.SELECT, SqlGraph.TABLE_NAME, SqlGraph.ITEM_EXPR, SqlGraph.ITEM_STAR,
        SqlGraph.COL, SqlGraph.LIT, SqlGraph.CMP, SqlGraph.LOGIC]
    private val contracts = SqlGraph.contracts().associateBy { it.type }

    suspend fun query(method: String, body: String, store: QuadStore, gate: Mutex): WireHttpResponse {
        fun error(status: Int, message: String) = WireHttpResponse(status, jsonOf(_m["error" j message]), "application/json; charset=utf-8")
        if (method != "POST") return error(405, "Visual SQL requires POST")
        if (body.length > BODY_BYTES || body.encodeToByteArray().size > BODY_BYTES)
            return error(413, "Visual SQL document is too large")
        if (!wellFormedUtf16(body)) return error(400, "Visual SQL contains invalid Unicode")
        if (!depthAllowed(body)) return error(413, "Visual SQL JSON exceeds the nesting limit")
        val request = try { reifyStrict(body) as? Map<*, *> ?: return error(400, "Expected a JSON object") }
            catch (e: Exception) { return error(400, "Invalid JSON") }
        if (hasDuplicateKeys(request)) return error(400, "Duplicate JSON object key")
        if (!wellFormedStrings(request)) return error(400, "Visual SQL contains invalid Unicode")
        if (request.keys.any { it != "dataset" && it != "program" }) return error(400, "Unknown request field")
        if (request["dataset"] != "quad-store") return error(400, "Select the quad-store dataset")
        val document = request["program"] as? Map<*, *> ?: return error(400, "Expected a program object")
        fun count(value: Any?): Int = when (value) {
            is List<*> -> value.size
            is Array<*> -> value.size
            else -> 0 // The program decoder diagnoses malformed fields.
        }
        if (count(document["nodes"]) > NODES || count(document["wires"]) > NODES)
            return error(413, "Visual SQL has too many nodes or wires")
        val nodeValues = document["nodes"] as? List<*> ?: emptyList<Any?>()
        if (nodeValues.any { raw ->
            val node = raw as? Map<*, *>
            val params = node?.get("params") as? Map<*, *>
            params != null && params.values.any { it !is String }
        }) return error(400, "Visual SQL node parameter values must be strings")
        val program = try { LcncProgramConfix.fromJson("visual SQL", jsonOf(document)) }
            catch (e: Exception) { return error(400, e.message ?: "Invalid visual SQL") }
        if (!program.controls.inspectionOnly) return error(400, "Visual SQL requires an inspection-only graph")
        if (program.nodes.view.any { node ->
            node.type !in allowed || node.params.keys.any { it !in contracts.getValue(node.type).params.keys } ||
                node.params.any { (key, value) -> value.length > if (key == "value") TERM_CHARS else 128 } ||
                node.type == SqlGraph.LIT && node.params["kind"]?.trim()?.equals("double", ignoreCase = true) == true
        }) return error(400, "Visual SQL contains an unsupported node or parameter")
        val stmt = try { SqlGraph.lower(program) } catch (e: Exception) { return error(400, e.message ?: "Invalid visual SQL") }
        val from = stmt.from ?: return error(400, "Visual SQL requires the quad table")
        if (!from.name.asString().equals(QuadStore.RDF_QUAD, ignoreCase = true) || from.args != null)
            return error(400, "Visual SQL can query only DB.DBA.RDF_QUAD")
        if (stmt.joins.size > 0 || stmt.groupBy.size > 0 || stmt.having != null || stmt.orderBy.size > 0 ||
            stmt.limit != null || stmt.offset != null || stmt.union.size > 0)
            return error(400, "Visual SQL does not yet support joins, grouping, sorting, limits or offsets")
        val star = program.nodes.view.any { it.type == SqlGraph.ITEM_STAR }
        // The planner currently represents SELECT * with the same AST as the
        // string literal '*'. Refuse that one ambiguous projection, not WHERE '*'.
        val single = if (stmt.columns.size == 1) stmt.columns[0].expr else null
        if (!star && ((single as? LitExpr)?.lit as? StringLiteral)?.asString() == "*")
            return error(400, "A sole '*' string literal is ambiguous with SELECT *")
        val alias = from.alias?.asString()
        val allowedQualifiers = if (alias == null) setOf(QuadStore.RDF_QUAD, "RDF_QUAD") else setOf(alias)
        if (program.nodes.view.any { node ->
            if (node.type != SqlGraph.COL) return@any false
            val name = node.params["name"]?.trim().orEmpty()
            val dot = name.lastIndexOf('.')
            val column = if (dot < 0) name else name.substring(dot + 1)
            val qualifier = if (dot < 0) null else name.substring(0, dot)
            column !in QuadStore.COLUMNS || qualifier != null && qualifier !in allowedQualifiers
        }) return error(400, "Visual SQL column must refer to the selected quad table")
        if (program.nodes.view.any { node ->
            if (node.type != SqlGraph.LIT || node.params["kind"]?.trim()?.equals("int", true) != true) return@any false
            val value = node.params["value"]?.trim()?.toLongOrNull()
            value == null || value !in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER
        }) return error(400, "Visual SQL integer exceeds the exact JSON range")
        val columns = if (star) _l["G", "S", "P", "O"] else stmt.columns.view.map(::displayName)
        if (columns.isEmpty() || columns.size > COLUMNS || columns.any { it.length > 128 })
            return error(413, "Visual SQL has too many or oversized output columns")

        return gate.withLock {
            if (store.size > SOURCE_ROWS || store.terms > SOURCE_TERMS)
                return@withLock error(413, "Quad store exceeds the visual SQL source limit")
            var longest = program.nodes.view.flatMap { it.params.values }.maxOfOrNull { it.length } ?: 0
            var total = 0L
            for (i in 0 until store.terms) {
                val lexeme = store.lexeme(i)
                if (!wellFormedUtf16(lexeme)) return@withLock error(400, "Quad store has a term with invalid Unicode")
                val size = lexeme.length
                if (size > TERM_CHARS) return@withLock error(413, "Quad store has a term too large for visual SQL")
                longest = maxOf(longest, size)
                total += size
                if (total > SOURCE_CHARS) return@withLock error(413, "Quad store exceeds the visual SQL text limit")
            }
            // Each output cell is a bounded source string, literal or null. Six bytes per UTF-16
            // unit covers JSON escaping. Reject before planning/materializing; never silently truncate.
            val fixed = 8_192L + columns.sumOf { it.length.toLong() * 6L + 16L }
            val rowBound = 8L + columns.size.toLong() * (longest.toLong() * 6L + 32L)
            if (fixed + store.size.toLong() * rowBound > RESPONSE_BYTES)
                return@withLock error(413, "Visual SQL may exceed the result size limit")
            try {
                val snapshot = store.scan()
                val result = stmt.plan({ name ->
                    require(name.equals(QuadStore.RDF_QUAD, ignoreCase = true)) { "Unexpected table" }
                    snapshot
                }).result
                check(result.size <= store.size) { "Visual SQL exceeded its row bound" }
                val rows = (0 until result.size).map { r ->
                    val values = result[r].values
                    check(values.size == columns.size) { "Unexpected result width" }
                    (0 until values.size).map { c ->
                        val value = values[c]
                        require(value == null || value is String || value is Int || value is Long) { "Unexpected result type" }
                        require(value !is Long || value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) { "Integer result exceeds the exact JSON range" }
                        value
                    }
                }
                val out = jsonOf(_m["dataset" j "quad-store", "columns" j columns, "rows" j rows,
                    "scope" j "ephemeral quad-store snapshot; live Rete and project catalog excluded"])
                if (out.encodeToByteArray().size > RESPONSE_BYTES) error(413, "Visual SQL result exceeds the size limit")
                else WireHttpResponse(200, out, "application/json; charset=utf-8")
            } catch (e: Exception) { error(400, e.message ?: "Visual SQL failed") }
        }
    }

    /** reifyStrict preserves duplicate entries, including escaped spellings of the same key. */
    private fun hasDuplicateKeys(value: Any?): Boolean = when (value) {
        is Map<*, *> -> {
            val keys = HashSet<Any?>()
            value.entries.any { !keys.add(it.key) || hasDuplicateKeys(it.value) }
        }
        is List<*> -> value.any(::hasDuplicateKeys)
        is Array<*> -> value.any(::hasDuplicateKeys)
        else -> false
    }

    private fun wellFormedStrings(value: Any?): Boolean = when (value) {
        is String -> wellFormedUtf16(value)
        is Map<*, *> -> value.entries.all { wellFormedStrings(it.key) && wellFormedStrings(it.value) }
        is List<*> -> value.all(::wellFormedStrings)
        is Array<*> -> value.all(::wellFormedStrings)
        else -> true
    }

    private fun wellFormedUtf16(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch in '\uD800'..'\uDBFF') {
                if (i + 1 >= value.length || value[i + 1] !in '\uDC00'..'\uDFFF') return false
                i += 2
            } else {
                if (ch in '\uDC00'..'\uDFFF') return false
                i++
            }
        }
        return true
    }

    private fun depthAllowed(body: String): Boolean {
        var depth = 0; var quoted = false; var escaped = false
        for (ch in body) {
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > DEPTH) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return true // Strict parsing reports incomplete or mismatched JSON.
    }
}
