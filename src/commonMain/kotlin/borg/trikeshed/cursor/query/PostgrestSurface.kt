package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.JsPathElement
import borg.trikeshed.parse.confix.idxOf
import borg.trikeshed.parse.confix.keyOf
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.kursive.sql.*
import borg.trikeshed.parse.reify

/** One PostgREST reply: status, headers and body (empty for HEAD and minimal writes). */
data class PostgrestReply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) {
    val contentType: String get() = headers["Content-Type"] ?: "application/json; charset=utf-8"
}

/** A PostgREST error: HTTP [status] and the `{code, message, details, hint}` envelope. */
class PostgrestError(val status: Int, val code: String, message: String, val details: String? = null, val hint: String? = null) :
    RuntimeException(message)

/** A function `/rpc/<name>` calls: named arguments in, a table out. */
interface RpcFunction {
    val parameters: List<String>

    /** A stable function may also be called with GET, its arguments in the query string. */
    val stable: Boolean get() = true

    fun call(args: Map<String, Any?>, catalog: Catalog, compiler: ExprCompiler): Cursor
}

/** `CREATE TABLE` / `DROP TABLE` for the raw-SQL entry point. */
interface TableDdl {
    /** Declares [name] with [columns] (name to pg type); false when it exists. */
    fun create(name: String, columns: List<Pair<String, String>>): Boolean

    /** False when there was no such table. */
    fun drop(name: String): Boolean
}

/**
 * Supabase's data API over the [catalog]: PostgREST's URL grammar lowered to the one SQL AST and
 * planned by the one planner, so a `/rest/v1` read runs the same kernels as the same query typed as
 * SQL. commonMain; no sockets: the daemon's wire hands `(method, path, headers, body)` in.
 *
 *   GET|HEAD  /rest/v1/<table>?select=&<col>=<op>.<v>&or=()&order=&limit=&offset=   (Range, Prefer: count=)
 *   POST      /rest/v1/<table>[?on_conflict=id&columns=&select=]                   (Prefer: return=, resolution=)
 *   PATCH     /rest/v1/<table>?<filters>        DELETE /rest/v1/<table>?<filters>
 *   POST|GET  /rest/v1/rpc/<fn>                 GET /rest/v1/   (OpenAPI 2.0)
 *   POST      /pg/query {"query": "..."}        (pg-meta: SELECT, CREATE TABLE, DROP TABLE)
 *
 * Not built (answered with PGRST127 / PGRST123 / PGRST200): full-text, range and array operators,
 * `(any|all)` modifiers, aggregates and resource embedding. Writes are per row: a multi-row write that
 * fails part way leaves the earlier rows written (Couch has no multi-document transaction).
 */
class PostgrestSurface(
    val catalog: Catalog,
    val ddl: TableDdl? = null,
    val functions: Map<String, RpcFunction> = mapOf("match_documents" to MatchDocuments()),
    val compiler: ExprCompiler = ClosureExprCompiler,
    val base: String = "/rest/v1",
) {
    fun handle(method: String, rawPath: String, headers: Map<String, String>, body: String): PostgrestReply {
        val h = headers.entries.associate { it.key.lowercase() to it.value.trim() }
        val profile = h["accept-profile"] ?: h["content-profile"]
        return answer(profile) {
            if (profile != null && profile != "public")
                throw PostgrestError(406, "PGRST106", "Invalid schema: $profile", hint = "Only the following schemas are exposed: public")
            val path = rawPath.substringBefore('?').removePrefix(base).trim('/')
            val query = decodeQuery(rawPath.substringAfter('?', ""))
            when {
                method == "OPTIONS" -> PostgrestReply(200, "", mapOf("Allow" to "OPTIONS,GET,HEAD,POST,PATCH,DELETE"))
                path.isEmpty() -> openApi()
                path.startsWith("rpc/") -> rpc(method, path.removePrefix("rpc/"), query, h, body)
                '/' in path -> throw PostgrestError(404, "PGRST125", "Invalid path specified in request URL")
                else -> table(method, path, query, h, body)
            }
        }
    }

    /** pg-meta's `POST /pg/query`: one statement, rows out as a JSON array. */
    fun sql(text: String): PostgrestReply = answer(null) {
        val statement = text.trim().removeSuffix(";").trim()
        val words = statement.split(Regex("\\s+"), limit = 4).map { it.uppercase() }
        when {
            words.take(2) == listOf("CREATE", "TABLE") -> createTable(statement)
            words.take(2) == listOf("DROP", "TABLE") -> dropTable(statement)
            else -> {
                val stmt = SqlParser.parse(statement.toSeries())
                    ?: throw PostgrestError(400, "42601", "syntax error in \"${statement.take(80)}\"")
                val cursor = stmt.plan(catalog::resolve, compiler = compiler)
                ok(jsonRows(names(cursor, emptyList()), rows(cursor)))
            }
        }
    }

    // ── errors and headers ───────────────────────────────────────────────────────────────────

    private inline fun answer(profile: String?, block: () -> PostgrestReply): PostgrestReply {
        val reply = try {
            block()
        } catch (e: Throwable) {
            val error = classify(e)
            val json = buildString {
                append("{\"code\":"); jsonOf(error.code, this)
                append(",\"message\":"); jsonOf(error.message, this)
                append(",\"details\":"); jsonOf(error.details, this)
                append(",\"hint\":"); jsonOf(error.hint, this)
                append('}')
            }
            PostgrestReply(error.status, json, mapOf("Proxy-Status" to "PostgREST; error=${error.code}"))
        }
        val headers = LinkedHashMap(reply.headers)
        headers.getOrPut("Content-Type") { "application/json; charset=utf-8" }
        headers["Access-Control-Allow-Origin"] = "*"
        headers["Access-Control-Allow-Methods"] = "GET, POST, PATCH, PUT, DELETE, OPTIONS"
        headers["Access-Control-Allow-Headers"] = "Authorization, apikey, Content-Type, Prefer, Range, Accept-Profile, Content-Profile, X-Client-Info"
        headers["Access-Control-Expose-Headers"] = "Content-Encoding, Content-Location, Content-Range, Content-Type, Date, Location, Server, Transfer-Encoding, Range-Unit"
        if (profile != null && reply.status < 400) headers["Content-Profile"] = profile
        return reply.copy(headers = headers)
    }

    private fun classify(e: Throwable): PostgrestError {
        val message = e.message ?: (e::class.simpleName ?: "error")
        return when {
            e is PostgrestError -> e
            e is DuplicateKey -> PostgrestError(409, "23505", message, "Key (id)=(${e.id}) already exists.")
            e is UndefinedTable -> PostgrestError(404, "42P01", message)
            message.startsWith("invalid input syntax for type") -> PostgrestError(400, "22P02", message)
            message.startsWith("column \"") && "does not exist" in message ->
                PostgrestError(400, "42703", message.substringBefore(" among "))
            message.startsWith("type \"") -> PostgrestError(400, "42704", message)
            message.startsWith("different vector dimensions") -> PostgrestError(400, "22000", message)
            message.startsWith("could not serialize access") -> PostgrestError(409, "40001", message)
            else -> PostgrestError(500, "XX000", message)
        }
    }

    private fun ok(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) = PostgrestReply(status, body, headers)

    // ── tables ───────────────────────────────────────────────────────────────────────────────

    private fun table(method: String, name: String, query: List<Pair<String, String>>, h: Map<String, String>, body: String): PostgrestReply {
        val table = catalog.table(name) ?: throw PostgrestError(
            404, "PGRST205", "Could not find the table 'public.$name' in the schema cache",
        )
        return when (method) {
            "GET", "HEAD" -> read(table, query, h, method == "HEAD")
            "POST" -> insert(table, query, h, body)
            "PATCH" -> update(table, query, h, body)
            "DELETE" -> delete(table, query, h)
            else -> throw PostgrestError(405, "PGRST117", "Unsupported HTTP method: $method")
        }
    }

    private fun read(table: CatalogTable, query: List<Pair<String, String>>, h: Map<String, String>, head: Boolean): PostgrestReply {
        val shape = Shape(table)
        var limit = query.int("limit")
        var offset = query.int("offset")
        h["range"]?.let { range ->
            val lower = range.substringBefore('-').trim().toIntOrNull() ?: 0
            val upper = range.substringAfter('-', "").trim().toIntOrNull()
            offset = maxOf(offset ?: 0, lower)
            if (upper != null) limit = minOf(limit ?: Int.MAX_VALUE, upper - lower + 1)
        }
        if ((limit ?: 0) < 0) throw PostgrestError(416, "PGRST103", "Requested range not satisfiable", "Limit should be greater than or equal to zero.")
        val where = shape.where(query)
        val stmt = SelectStmt(
            shape.select(query.value("select")).toSeries(), shape.ref, where,
            orderBy = shape.order(query.value("order")).toSeries(), limit = limit, offset = offset,
        )
        val cursor = run(stmt, table)
        val names = names(cursor, stmt.columns.view.map { it.alias!!.asString() })
        val rows = rows(cursor)
        val total = if (prefer(h).any { it.startsWith("count=") }) count(table, shape.ref, where) else null
        val lower = offset ?: 0
        if (total != null && lower > total && total > 0)
            throw PostgrestError(416, "PGRST103", "Requested range not satisfiable", "An offset of $lower was requested, but there are only $total rows.")
        val range = if (rows.isEmpty()) "*" else "$lower-${lower + rows.size - 1}"
        val status = if (total != null && rows.size < total) 206 else 200
        val reply = body(names, rows, h, status, mapOf("Content-Range" to "$range/${total ?: "*"}"))
        return if (head) reply.copy(body = "") else reply
    }

    private fun insert(table: CatalogTable, query: List<Pair<String, String>>, h: Map<String, String>, body: String): PostgrestReply {
        val writes = table.writes ?: throw PostgrestError(405, "25006", "cannot insert into view \"${table.name}\"")
        query.value("on_conflict")?.let { if (it != "id") throw PostgrestError(400, "42P10", "there is no unique or exclusion constraint matching the ON CONFLICT specification") }
        val columns = query.value("columns")?.split(',')?.map { it.trim().trim('"') }
        val rows = when (val parsed = reify(body)) {
            is Map<*, *> -> listOf(parsed)
            is List<*> -> parsed.map { it as? Map<*, *> ?: throw PostgrestError(400, "PGRST102", "All object keys must match") }
            else -> throw PostgrestError(400, "PGRST102", "Empty or invalid json")
        }.map { row ->
            @Suppress("UNCHECKED_CAST") val r = row as Map<String, Any?>
            if (columns == null) r else columns.associateWith { r[it] }
        }
        val prefer = prefer(h)
        val resolution = when {
            "resolution=merge-duplicates" in prefer -> Resolution.MERGE
            "resolution=ignore-duplicates" in prefer -> Resolution.IGNORE
            else -> Resolution.ERROR
        }
        if (resolution == Resolution.ERROR) {
            // Refuse the whole request before writing any row, as one transaction would.
            val ids = rows.mapNotNull { it["id"] }
            ids.groupBy { key(it) }.entries.firstOrNull { it.value.size > 1 }?.let { throw DuplicateKey(table.name, it.key) }
            ids.firstOrNull { writes.exists(it) }?.let { throw DuplicateKey(table.name, key(it)) }
        }
        val stored = rows.mapNotNull { writes.insert(it, resolution) }
        return written(table, stored, query, h, 201, "*/*")
    }

    private fun update(table: CatalogTable, query: List<Pair<String, String>>, h: Map<String, String>, body: String): PostgrestReply {
        val writes = table.writes ?: throw PostgrestError(405, "25006", "cannot update view \"${table.name}\"")
        @Suppress("UNCHECKED_CAST")
        val patch = reify(body) as? Map<String, Any?> ?: throw PostgrestError(400, "PGRST102", "Empty or invalid json")
        val stored = ids(table, query).mapNotNull { writes.update(it, patch) }
        return written(table, stored, query, h, 204, if (stored.isEmpty()) "*/*" else "0-${stored.size - 1}/*")
    }

    private fun delete(table: CatalogTable, query: List<Pair<String, String>>, h: Map<String, String>): PostgrestReply {
        val writes = table.writes ?: throw PostgrestError(405, "25006", "cannot delete from view \"${table.name}\"")
        val shape = Shape(table)
        val cursor = run(SelectStmt(shape.select("*").toSeries(), shape.ref, shape.where(query)), table)
        val names = names(cursor, emptyList())
        val before = rows(cursor).map { row -> names.indices.associate { names[it] to row[it] } }
        for (row in before) writes.delete(row["id"] ?: continue)
        return written(table, before, query, h, 204, "*/*")
    }

    /** A write's reply: the stored rows through `select=` under `return=representation`, else no body. */
    private fun written(
        table: CatalogTable, stored: List<Map<String, Any?>>, query: List<Pair<String, String>>, h: Map<String, String>,
        status: Int, range: String,
    ): PostgrestReply {
        val headers = mapOf("Content-Range" to range)
        if ("return=representation" !in prefer(h)) return PostgrestReply(status, "", headers)
        val columns = LinkedHashSet<String>().apply { table.columns().forEach { add(it.name.toString()) }; stored.forEach { addAll(it.keys) } }
        val view = CursorTable(table.name) { rowsCursor(columns.toList(), stored) }
        val shape = Shape(view)
        val stmt = SelectStmt(shape.select(query.value("select")).toSeries(), shape.ref, null)
        val cursor = run(stmt, view)
        return body(names(cursor, stmt.columns.view.map { it.alias!!.asString() }), rows(cursor), h, if (status == 204) 200 else status, headers)
    }

    private fun ids(table: CatalogTable, query: List<Pair<String, String>>): List<Any> {
        val shape = Shape(table)
        val stmt = SelectStmt(listOf(Column(ColumnRef(Identifier("id".toSeries())), Identifier("id".toSeries()))).toSeries(), shape.ref, shape.where(query))
        return rows(run(stmt, table)).mapNotNull { it[0] }
    }

    private fun count(table: CatalogTable, ref: TableRef, where: Expr?): Int =
        run(SelectStmt(listOf(Column(LitExpr(StringLiteral("*".toSeries())))).toSeries(), ref, where), table).size

    private fun run(stmt: SelectStmt, table: CatalogTable): Cursor =
        stmt.plan({ name -> if (name == table.name) table.scan() else catalog.resolve(name) }, compiler = compiler)

    // ── rpc ──────────────────────────────────────────────────────────────────────────────────

    private fun rpc(method: String, name: String, query: List<Pair<String, String>>, h: Map<String, String>, body: String): PostgrestReply {
        val fn = functions[name]
        val given: Map<*, *> = when (method) {
            "POST" -> if (body.isBlank()) emptyMap<String, Any?>() else reify(body) as? Map<*, *> ?: throw PostgrestError(400, "PGRST102", "Empty or invalid json")
            "GET", "HEAD" -> query.filter { (k, _) -> fn != null && k in fn.parameters }.toMap()
            else -> throw PostgrestError(405, "PGRST101", "Cannot use the $method method on RPC")
        }
        if (fn == null || !fn.parameters.containsAll(given.keys.map { it.toString() }))
            throw PostgrestError(
                404, "PGRST202",
                "Could not find the function public.$name(${given.keys.joinToString(", ")}) in the schema cache",
                "Searched for the function public.$name with parameters ${given.keys.joinToString(", ")} or with a single unnamed json/jsonb parameter, but no matches were found in the schema cache.",
            )
        if (method != "POST" && !fn.stable) throw PostgrestError(405, "PGRST101", "Cannot use the $method method on RPC")
        val result = fn.call(given.entries.associate { it.key.toString() to it.value }, catalog, compiler)
        // A table-valued result takes select, filters, order and paging as an outer query.
        val outer = query.filter { (k, _) -> k !in fn.parameters }
        return read(CursorTable(name) { result }, outer, h, method == "HEAD")
    }

    // ── DDL ──────────────────────────────────────────────────────────────────────────────────

    private fun createTable(statement: String): PostgrestReply {
        val ddl = ddl ?: throw PostgrestError(400, "0A000", "CREATE TABLE is not supported by this catalog")
        val m = Regex("(?is)CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?(?:public\\.)?(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)").matchEntire(statement)
            ?: throw PostgrestError(400, "42601", "syntax error in CREATE TABLE")
        val name = m.groupValues[2].trim('"')
        val constraint = setOf("PRIMARY", "NOT", "NULL", "DEFAULT", "UNIQUE", "REFERENCES", "GENERATED", "CHECK", "CONSTRAINT", "COLLATE")
        val columns = splitTop(m.groupValues[3], ',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { def ->
            val words = def.split(Regex("\\s+"))
            if (words[0].uppercase() in setOf("PRIMARY", "UNIQUE", "CONSTRAINT", "FOREIGN", "CHECK", "EXCLUDE")) return@mapNotNull null
            val type = words.drop(1).takeWhile { it.uppercase() !in constraint }.joinToString(" ").ifEmpty { "text" }
            words[0].trim('"') to type.lowercase()
        }
        if (!ddl.create(name, columns) && m.groupValues[1].isEmpty()) throw PostgrestError(400, "42P07", "relation \"$name\" already exists")
        return ok("[]")
    }

    private fun dropTable(statement: String): PostgrestReply {
        val ddl = ddl ?: throw PostgrestError(400, "0A000", "DROP TABLE is not supported by this catalog")
        val m = Regex("(?is)DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?(.+?)(\\s+(CASCADE|RESTRICT))?").matchEntire(statement)
            ?: throw PostgrestError(400, "42601", "syntax error in DROP TABLE")
        for (name in m.groupValues[2].split(',').map { it.trim().removePrefix("public.").trim('"') })
            if (!ddl.drop(name) && m.groupValues[1].isEmpty()) throw PostgrestError(400, "42P01", "table \"$name\" does not exist")
        return ok("[]")
    }

    // ── OpenAPI ──────────────────────────────────────────────────────────────────────────────

    private fun openApi(): PostgrestReply {
        val tables = catalog.tables()
        val doc = buildString {
            append("{\"swagger\":\"2.0\",\"info\":{\"title\":\"PostgREST API\",\"description\":\"TrikeShed catalog\",\"version\":\"12\"},")
            append("\"basePath\":\"/\",\"schemes\":[\"http\"],\"consumes\":[\"application/json\",\"application/vnd.pgrst.object+json\",\"text/csv\"],")
            append("\"produces\":[\"application/json\",\"application/vnd.pgrst.object+json\",\"text/csv\"],\"paths\":{\"/\":{\"get\":{\"tags\":[\"Introspection\"],\"responses\":{\"200\":{\"description\":\"OK\"}}}}")
            for (t in tables) {
                append(','); jsonOf("/${t.name}", this); append(":{")
                val verbs = if (t.writes != null) listOf("get" to "200", "post" to "201", "patch" to "204", "delete" to "204") else listOf("get" to "200")
                append(verbs.joinToString(",") { (verb, code) -> "\"$verb\":{\"tags\":[${jsonOf(t.name)}],\"responses\":{\"$code\":{\"description\":\"OK\"}}}" })
                append('}')
            }
            for (fn in functions.keys) append(",${jsonOf("/rpc/$fn")}:{\"post\":{\"tags\":[\"(rpc) $fn\"],\"responses\":{\"200\":{\"description\":\"OK\"}}}}")
            append("},\"definitions\":{")
            append(tables.joinToString(",") { t ->
                jsonOf(t.name) + ":{\"type\":\"object\",\"properties\":{" + t.columns().joinToString(",") { c ->
                    val format = pgType(c)
                    val type = when (format) { "bigint" -> "integer"; "double precision" -> "number"; "boolean" -> "boolean"; "text", "vector" -> "string"; else -> "object" }
                    jsonOf(c.name.toString()) + ":{\"type\":\"$type\",\"format\":${jsonOf(format)}}"
                } + "}}"
            })
            append("}}")
        }
        return ok(doc, headers = mapOf("Content-Type" to "application/openapi+json; charset=utf-8"))
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────

    private fun body(names: List<String>, rows: List<List<Any?>>, h: Map<String, String>, status: Int, headers: Map<String, String>): PostgrestReply {
        val accept = h["accept"].orEmpty()
        return when {
            "application/vnd.pgrst.object+json" in accept -> {
                if (rows.size != 1) throw PostgrestError(406, "PGRST116", "Cannot coerce the result to a single JSON object", "The result contains ${rows.size} rows")
                PostgrestReply(200, jsonRow(names, rows[0], StringBuilder()).toString(), headers + ("Content-Type" to "application/vnd.pgrst.object+json; charset=utf-8"))
            }
            "text/csv" in accept -> PostgrestReply(status, csv(names, rows), headers + ("Content-Type" to "text/csv; charset=utf-8"))
            accept.isEmpty() || "application/json" in accept || "*/*" in accept -> PostgrestReply(status, jsonRows(names, rows), headers)
            else -> throw PostgrestError(406, "PGRST107", "None of these media types are available: $accept")
        }
    }

    private fun jsonRows(names: List<String>, rows: List<List<Any?>>): String = buildString {
        append('[')
        rows.forEachIndexed { i, row -> if (i > 0) append(','); jsonRow(names, row, this) }
        append(']')
    }

    private fun jsonRow(names: List<String>, row: List<Any?>, out: StringBuilder): StringBuilder {
        out.append('{')
        names.forEachIndexed { c, name -> if (c > 0) out.append(','); jsonOf(name, out); out.append(':'); cellJson(row[c], out) }
        return out.append('}')
    }

    private fun csv(names: List<String>, rows: List<List<Any?>>): String = buildString {
        fun field(value: Any?) {
            val text = when (value) {
                null -> return
                is FloatArray -> value.joinToString(",", "[", "]")
                is String -> value
                else -> StringBuilder().also { cellJson(value, it) }.toString()
            }
            if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) append('"').append(text.replace("\"", "\"\"")).append('"') else append(text)
        }
        append(names.joinToString(",")).append('\n')
        for (row in rows) { row.forEachIndexed { i, v -> if (i > 0) append(','); field(v) }; append('\n') }
    }

    private fun rows(cursor: Cursor): List<List<Any?>> = List(cursor.size) { r -> cursor[r].values.let { v -> List(v.size) { v[it] } } }

    private fun names(cursor: Cursor, fallback: List<String>): List<String> =
        if (cursor.size == 0) fallback else cursor.columnNames.let { n -> List(n.size) { n[it].toString() } }

    // ── the URL grammar ──────────────────────────────────────────────────────────────────────

    /** One table's columns and the PostgREST grammar over them. */
    private inner class Shape(val table: CatalogTable) {
        val columns: Map<String, ColumnMeta> = table.columns().associateBy { it.name.toString() }
        val ref = TableRef(Identifier(table.name.toSeries()))

        fun select(spec: String?): List<Column> {
            val items = splitTop(spec ?: "*", ',').map { it.trim() }.filter { it.isNotEmpty() }
            return items.flatMap { item ->
                when {
                    item == "*" -> table.columns().map { c -> c.name.toString().let { Column(ColumnRef(Identifier(it.toSeries())), Identifier(it.toSeries())) } }
                    item == "count()" || Regex("\\.(sum|avg|min|max|count)\\(\\)").containsMatchIn(item) ->
                        throw PostgrestError(400, "PGRST123", "Use of aggregate functions is not allowed")
                    '(' in item -> throw PostgrestError(400, "PGRST200", "Could not find a relationship between '${table.name}' and '${item.substringBefore('(').trimStart('.').substringAfter(':')}' in the schema cache")
                    else -> {
                        val colon = Regex("(?<!:):(?!:)").find(item)?.range?.first
                        val alias = colon?.let { item.substring(0, it) }
                        val rest = colon?.let { item.substring(it + 1) } ?: item
                        val (fieldSpec, cast) = rest.split("::", limit = 2).let { it[0] to it.getOrNull(1) }
                        val (expr, name) = field(fieldSpec)
                        listOf(Column(cast?.let { Cast(expr, it.lowercase()) } ?: expr, Identifier((alias ?: name).toSeries())))
                    }
                }
            }
        }

        /** `col`, `col->k`, `col->>k`, `col->0`: the expression and its output key (the last segment). */
        fun field(spec: String): Pair<Expr, String> {
            val name = spec.substringBefore("->").trim().trim('"')
            if (columns.isNotEmpty() && name !in columns) throw PostgrestError(400, "42703", "column ${table.name}.$name does not exist")
            var expr: Expr = ColumnRef(Identifier(name.toSeries()))
            var last = name
            var rest = spec.trim().substring(spec.trim().indexOf(name) + name.length).trimStart('"')
            while (rest.startsWith("->")) {
                val text = rest.startsWith("->>")
                rest = rest.removePrefix(if (text) "->>" else "->")
                val step = rest.substringBefore("->")
                rest = rest.substring(step.length)
                val key = step.trim().trim('\'', '"')
                val element: JsPathElement = key.toIntOrNull()?.let { idxOf(it) } ?: keyOf(key)
                expr = Path(expr, s_[element], text)
                last = key
            }
            return expr to last
        }

        /** Every filter and logic tree, ANDed; null when there are none. */
        fun where(query: List<Pair<String, String>>): Expr? = query.mapNotNull { (key, value) ->
            when (key) {
                "select", "columns", "on_conflict", "order", "limit", "offset", "apikey" -> null
                "or", "and" -> logic(key, value)
                "not.or", "not.and" -> Not(logic(key.removePrefix("not."), value))
                else -> filter(key, value)
            }
        }.reduceOrNull { a, b -> BinaryExpr(a, "AND", b) }

        fun logic(op: String, list: String): Expr {
            val body = list.trim()
            if (!body.startsWith("(") || !body.endsWith(")")) throw parseError(list)
            return splitTop(body.substring(1, body.length - 1), ',').map { it.trim() }.map { item ->
                val nested = Regex("^(not\\.)?(and|or)\\((.*)\\)$").matchEntire(item)
                if (nested != null) logic(nested.groupValues[2], "(" + nested.groupValues[3] + ")").let { if (nested.groupValues[1].isNotEmpty()) Not(it) else it }
                else filter(item.substringBefore('.'), item.substringAfter('.', ""))
            }.reduce { a, b -> BinaryExpr(a, op.uppercase(), b) }
        }

        fun filter(columnSpec: String, spec: String): Expr {
            val (column, _) = field(columnSpec)
            val meta = (column as? ColumnRef)?.let { columns[it.id.asString()] }
            val negated = spec.startsWith("not.")
            val body = spec.removePrefix("not.")
            if ('.' !in body) throw parseError(spec)
            val op = body.substringBefore('.')
            val value = body.substringAfter('.')
            val e = when (op) {
                "eq" -> BinaryExpr(column, "=", literal(value, meta))
                "neq" -> BinaryExpr(column, "<>", literal(value, meta))
                "gt" -> BinaryExpr(column, ">", literal(value, meta))
                "gte" -> BinaryExpr(column, ">=", literal(value, meta))
                "lt" -> BinaryExpr(column, "<", literal(value, meta))
                "lte" -> BinaryExpr(column, "<=", literal(value, meta))
                "like" -> BinaryExpr(column, "LIKE", LitExpr(StringLiteral(unquote(value).replace('*', '%').toSeries())))
                "ilike" -> BinaryExpr(column, "ILIKE", LitExpr(StringLiteral(unquote(value).replace('*', '%').toSeries())))
                "in" -> {
                    if (!value.startsWith("(") || !value.endsWith(")")) throw parseError(spec)
                    InList(column, splitTop(value.substring(1, value.length - 1), ',').map { literal(unquote(it.trim()), meta) }.toSeries())
                }
                "is" -> when (value.lowercase()) {
                    "null", "unknown" -> Is(column, null)
                    "not_null" -> Is(column, null, negated = true)
                    "true" -> Is(column, true)
                    "false" -> Is(column, false)
                    else -> throw parseError(spec)
                }
                "match", "imatch", "isdistinct", "fts", "plfts", "phfts", "wfts", "cs", "cd", "ov", "sl", "sr", "nxr", "nxl", "adj" ->
                    throw PostgrestError(400, "PGRST127", "Feature not implemented", op)
                else -> if ('(' in op) throw PostgrestError(400, "PGRST127", "Feature not implemented", op) else throw parseError(spec)
            }
            return if (negated) Not(e) else e
        }

        fun order(spec: String?): List<OrderItem> = spec?.let { splitTop(it, ',') }.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.map { item ->
            val parts = item.split('.')
            val (expr, _) = field(parts[0])
            var descending = false
            var nullsFirst: Boolean? = null
            for (modifier in parts.drop(1)) when (modifier) {
                "asc" -> descending = false
                "desc" -> descending = true
                "nullsfirst" -> nullsFirst = true
                "nullslast" -> nullsFirst = false
                else -> throw PostgrestError(400, "PGRST100", "\"failed to parse order ($item)\" (line 1, column 1)")
            }
            OrderItem(expr, descending, nullsFirst)
        }

        /**
         * A filter value typed by its column, so `n=eq.5` compares numbers. A number is a numeric
         * literal (the kernels route `column op number`); anything else is a cast that folds once per
         * statement, or fails as pg does (22P02).
         */
        fun literal(value: String, meta: ColumnMeta?): Expr {
            val text = LitExpr(StringLiteral(value.toSeries()))
            val integral = meta?.type == IOMemento.IoLong || meta?.type == IOMemento.IoInt
            val fractional = meta?.type == IOMemento.IoDouble || meta?.type == IOMemento.IoFloat
            return when {
                meta == null -> text
                meta.isVector -> Cast(text, "vector")
                integral -> value.toLongOrNull()?.let { LitExpr(NumericLiteral(it)) } ?: Cast(text, "int8")
                fractional -> value.toDoubleOrNull()?.let { LitExpr(NumericLiteral(it)) } ?: Cast(text, "float8")
                meta.type == IOMemento.IoBoolean -> Cast(text, "bool")
                else -> text
            }
        }

        fun parseError(spec: String) = PostgrestError(
            400, "PGRST100", "\"failed to parse filter ($spec)\" (line 1, column 1)",
            "unexpected \"${spec.firstOrNull() ?: ' '}\" expecting \"not\" or operator (eq, gt, ...)",
        )
    }
}

/**
 * Supabase's `match_documents(query_embedding, match_threshold, match_count)`: the rows of [table]
 * whose cosine similarity `1 - (embedding <=> query)` exceeds the threshold, nearest first, with a
 * `similarity` column and without the vector itself. Planned as SQL, so the vector kernels serve it.
 */
class MatchDocuments(val table: String = "documents", val vector: String = "embedding") : RpcFunction {
    override val parameters = listOf("query_embedding", "match_threshold", "match_count")

    override fun call(args: Map<String, Any?>, catalog: Catalog, compiler: ExprCompiler): Cursor {
        val probe = when (val q = args["query_embedding"]) {
            is String -> q
            is List<*> -> q.joinToString(",", "[", "]")
            else -> throw PostgrestError(400, "22023", "query_embedding must be a vector")
        }
        val threshold = args["match_threshold"].let { (it as? Number)?.toDouble() ?: it?.toString()?.toDoubleOrNull() ?: 0.0 }
        val count = args["match_count"].let { (it as? Number)?.toInt() ?: it?.toString()?.toIntOrNull() ?: 10 }
        val source = catalog.table(table) ?: throw UndefinedTable(table)
        fun id(name: String) = Identifier(name.toSeries())
        val distance = BinaryExpr(ColumnRef(id(vector)), "<=>", Cast(LitExpr(StringLiteral(probe.toSeries())), "vector"))
        val similarity = BinaryExpr(LitExpr(NumericLiteral(1)), "-", distance)
        val columns = source.columns().filter { !it.isVector }.map { Column(ColumnRef(id(it.name.toString())), id(it.name.toString())) } +
            Column(similarity, id("similarity"))
        return SelectStmt(
            columns.toSeries(), TableRef(id(table)), BinaryExpr(similarity, ">", LitExpr(NumericLiteral(threshold))),
            orderBy = listOf(OrderItem(distance, false)).toSeries(), limit = count,
        ).plan(catalog::resolve, compiler = compiler)
    }
}

/** Form-decoded query pairs in order (`+` is a space), repeats kept. */
fun decodeQuery(query: String): List<Pair<String, String>> = query.split('&').filter { it.isNotEmpty() }.map { pair ->
    percentDecode(pair.substringBefore('=')) to percentDecode(pair.substringAfter('=', ""))
}

fun percentDecode(text: String): String {
    val bytes = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val hi = if (c == '%' && i + 2 < text.length) text[i + 1].digitToIntOrNull(16) else null
        val lo = if (hi != null) text[i + 2].digitToIntOrNull(16) else null
        when {
            c == '+' -> { bytes += ' '.code.toByte(); i++ }
            hi != null && lo != null -> { bytes += (hi * 16 + lo).toByte(); i += 3 }
            else -> {
                val width = if (c.isHighSurrogate() && i + 1 < text.length) 2 else 1
                for (b in text.substring(i, i + width).encodeToByteArray()) bytes += b
                i += width
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

/** [text] split on [separator] outside parentheses and double quotes. */
fun splitTop(text: String, separator: Char): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var quoted = false
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            quoted && c == '\\' -> i++
            c == '"' -> quoted = !quoted
            !quoted && c == '(' -> depth++
            !quoted && c == ')' -> depth--
            !quoted && depth == 0 && c == separator -> { out += text.substring(start, i); start = i + 1 }
        }
        i++
    }
    out += text.substring(start)
    return out
}

/** A double-quoted list item without its quotes and escapes. */
private fun unquote(value: String): String =
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    else value

private fun key(id: Any): String = when (id) {
    is Double -> if (id == kotlin.math.floor(id) && kotlin.math.abs(id) < 9.007199254740992E15) id.toLong().toString() else id.toString()
    else -> id.toString()
}

private fun List<Pair<String, String>>.value(name: String): String? = lastOrNull { it.first == name }?.second

private fun List<Pair<String, String>>.int(name: String): Int? = value(name)?.let {
    it.toIntOrNull() ?: throw PostgrestError(400, "PGRST100", "\"failed to parse $name parameter ($it)\" (line 1, column 1)")
}

/** Every `Prefer` preference, split on commas. */
private fun prefer(h: Map<String, String>): List<String> = h["prefer"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
