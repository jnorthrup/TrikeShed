package borg.trikeshed.pgwire

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.*

/** What one statement answered: rows to send, a command tag, or the empty query. */
sealed class PgResult {
    class Rows(val fields: List<PgField>, val rows: Cursor) : PgResult()
    class Command(val tag: String, val notices: List<ByteArray> = emptyList()) : PgResult()
    data object Empty : PgResult()
}

/**
 * One backend session of the pg wire over the SQL [surface]: the startup handshake (trust), the simple
 * protocol psql speaks, the extended protocol every driver speaks (Parse / Bind / Describe / Execute /
 * Sync / Close, `$n` parameters bound as literals), the session parameters (`SET`, `SHOW`, `pg_settings`)
 * and a transaction status that is bookkeeping only. Pure: bytes in through [receive], bytes out.
 */
class PgSession(val surface: PostgrestSurface, val pid: Int, val secret: Int) {
    companion object {
        /** The server's parameters, reported at startup in this order, as pg 14 reports its `GUC_REPORT` set. */
        val REPORTED: Map<String, String> = linkedMapOf(
            "application_name" to "", "client_encoding" to "UTF8", "DateStyle" to "ISO, MDY", "default_transaction_read_only" to "off",
            "in_hot_standby" to "off", "integer_datetimes" to "on", "IntervalStyle" to "postgres", "is_superuser" to "off",
            "server_encoding" to "UTF8", "server_version" to "14.0", "session_authorization" to SqlFunctions.OWNER,
            "standard_conforming_strings" to "on", "TimeZone" to "UTC",
        )

        /** Parameters `SHOW` answers beyond the reported set. */
        val SETTINGS: Map<String, String> = REPORTED + mapOf(
            "search_path" to "\"\$user\", public", "extra_float_digits" to "1", "statement_timeout" to "0", "lock_timeout" to "0",
            "idle_in_transaction_session_timeout" to "0", "transaction_isolation" to "read committed", "transaction_read_only" to "off",
            "max_identifier_length" to "63", "server_version_num" to "140000", "bytea_output" to "hex", "client_min_messages" to "notice",
            "max_connections" to "64", "escape_string_warning" to "on",
            "synchronize_seqscans" to "on", "ssl" to "off", "block_size" to "8192", "wal_level" to "minimal",
        )

        val TRANSACTION_ENDS = setOf("COMMIT", "END", "ROLLBACK", "ABORT")
        val EMPTY = ByteArray(0)

        /** pg's encoding names (pg_enc2name), compared as pg compares them: letters and digits, case-blind. */
        val ENCODINGS: Set<String> = setOf(
            "SQL_ASCII", "EUC_JP", "EUC_CN", "EUC_KR", "EUC_TW", "EUC_JIS_2004", "UTF8", "UNICODE", "MULE_INTERNAL", "LATIN1", "LATIN2", "LATIN3",
            "LATIN4", "LATIN5", "LATIN6", "LATIN7", "LATIN8", "LATIN9", "LATIN10", "WIN1256", "WIN1258", "WIN866", "WIN874", "KOI8R", "WIN1251",
            "WIN1252", "ISO_8859_5", "ISO_8859_6", "ISO_8859_7", "ISO_8859_8", "WIN1250", "WIN1253", "WIN1254", "WIN1255", "WIN1257", "KOI8U",
            "SJIS", "BIG5", "GBK", "UHC", "GB18030", "JOHAB", "SHIFT_JIS_2004", "ISO88591", "ISO88592", "ISO88593", "ISO88594", "ISO885915",
        ).map(::encodingKey).toSet()

        fun encodingKey(name: String): String = name.filter { it.isLetterOrDigit() }.lowercase()

        /** `SHOW x y z` spellings that name one parameter. */
        val SHOW_PHRASES: Map<String, String> = mapOf(
            "transaction isolation level" to "transaction_isolation", "time zone" to "TimeZone", "session authorization" to "session_authorization",
        )

        /** Text outside quotes with `-- …` and nested `/* … */` comments removed. */
        fun strip(text: String): String = buildString {
            var quote = '\u0000'
            var depth = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
                when {
                    depth > 0 -> { if (c == '*' && next == '/') { depth--; i++ } else if (c == '/' && next == '*') { depth++; i++ } }
                    quote != '\u0000' -> { append(c); if (c == quote) quote = '\u0000' }
                    c == '\'' || c == '"' -> { quote = c; append(c) }
                    c == '-' && next == '-' -> { while (i < text.length && text[i] != '\n') i++; continue }
                    c == '/' && next == '*' -> { depth = 1; i++ }
                    else -> append(c)
                }
                i++
            }
        }

        /** The statements of one simple-protocol query, comments removed, split on `;` outside quotes; empty ones dropped. */
        fun statements(text: String): List<String> {
            val clean = strip(text)
            val out = ArrayList<String>()
            var quote = '\u0000'
            var start = 0
            for (i in clean.indices) {
                val c = clean[i]
                when {
                    quote != '\u0000' -> if (c == quote) quote = '\u0000'
                    c == '\'' || c == '"' -> quote = c
                    c == ';' -> { out += clean.substring(start, i); start = i + 1 }
                }
            }
            out += clean.substring(start)
            return out.map { it.trim() }.filter { it.isNotEmpty() }
        }

        /** `$n` placeholders outside quotes, each replaced by [literal] of its 1-based index. */
        fun splice(text: String, literal: (Int) -> String): String = buildString {
            var quote = '\u0000'
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    quote != '\u0000' -> { append(c); if (c == quote) quote = '\u0000'; i++ }
                    c == '\'' || c == '"' -> { quote = c; append(c); i++ }
                    c == '$' && i + 1 < text.length && text[i + 1].isDigit() -> {
                        var j = i + 1
                        while (j < text.length && text[j].isDigit()) j++
                        append(literal(text.substring(i + 1, j).toInt()))
                        i = j
                    }
                    else -> { append(c); i++ }
                }
            }
        }

        /** How many parameters a statement names: its highest `$n`. */
        fun parameterCount(text: String): Int {
            var n = 0
            splice(text) { k -> if (k > n) n = k; "" }
            return n
        }
    }

    class Prepared(val text: String, val types: IntArray)
    class Portal(val result: PgResult, var offset: Int = 0)

    val settings: LinkedHashMap<String, String> = LinkedHashMap(SETTINGS)
    var transaction: Char = 'I'
        private set
    var closed: Boolean = false
        private set
    var started: Boolean = false
        private set
    private val statements = HashMap<String, Prepared>()
    private val portals = HashMap<String, Portal>()
    /** After an error in the extended protocol every message but Sync is ignored, as pg does. */
    private var skipping = false

    fun receive(message: PgFrontend): ByteArray = when (message) {
        is PgFrontend.Startup -> startup(message.parameters)
        PgFrontend.SslRequest, PgFrontend.GssEncRequest -> PgBackend.NO
        is PgFrontend.CancelRequest -> EMPTY.also { closed = true }
        is PgFrontend.Query -> query(message.text)
        is PgFrontend.Parse -> extended { parse(message) }
        is PgFrontend.Bind -> extended { bind(message) }
        is PgFrontend.Describe -> extended { describe(message) }
        is PgFrontend.Execute -> extended { execute(message) }
        is PgFrontend.Close -> extended { close(message) }
        PgFrontend.Sync -> { skipping = false; PgBackend.readyForQuery(transaction) }
        PgFrontend.Flush -> EMPTY
        PgFrontend.Terminate -> EMPTY.also { closed = true }
        is PgFrontend.Password -> EMPTY
        is PgFrontend.Unknown -> {
            closed = true
            PgBackend.errorResponse("08P01", "invalid frontend message type ${message.type.code}", severity = "FATAL")
        }
        is PgFrontend.Malformed -> {
            closed = true
            PgBackend.errorResponse("08P01", message.reason, severity = "FATAL")
        }
    }

    // ── startup ──────────────────────────────────────────────────────────────────────────────

    private fun startup(parameters: Map<String, String>): ByteArray {
        // Any encoding pg knows is accepted and reported back; the bytes sent stay UTF-8, as pg sends them for SQL_ASCII.
        val encoding = parameters["client_encoding"]
        if (encoding != null && encodingKey(encoding) !in ENCODINGS) {
            closed = true
            return PgBackend.errorResponse("22023", "invalid value for parameter \"client_encoding\": \"$encoding\"", severity = "FATAL")
        }
        // One database here, the one pg_database lists; libpq's default database is the user name, which pg refuses the same way.
        val database = parameters["database"] ?: parameters["user"]
        if (database != null && database != SqlFunctions.DATABASE) {
            closed = true
            return PgBackend.errorResponse("3D000", "database \"$database\" does not exist", severity = "FATAL")
        }
        parameters["user"]?.let { settings["session_authorization"] = it }
        parameters["application_name"]?.let { settings["application_name"] = it }
        parameters["search_path"]?.let { settings["search_path"] = it }
        parameters["options"]?.let { options -> options.split(Regex("\\s+")).forEach { opt -> opt.removePrefix("-c").removePrefix("--").split('=', limit = 2).let { if (it.size == 2 && it[0].isNotEmpty()) settings[it[0]] = it[1] } } }
        for ((k, v) in parameters) if (k != "user" && k != "database" && k != "options" && k != "replication" && k in SETTINGS) settings[k] = v
        started = true
        val out = ArrayList<ByteArray>()
        out += PgBackend.authenticationOk()
        for (name in REPORTED.keys) out += PgBackend.parameterStatus(name, settings.getValue(name))
        out += PgBackend.backendKeyData(pid, secret)
        out += PgBackend.readyForQuery(transaction)
        return concat(out)
    }

    // ── the simple protocol ──────────────────────────────────────────────────────────────────

    private fun query(text: String): ByteArray {
        val out = ArrayList<ByteArray>()
        val parts = statements(text)
        if (parts.isEmpty()) out += PgBackend.emptyQueryResponse()
        else for (part in parts) {
            try {
                val result = run(part)
                val fields = (result as? PgResult.Rows)?.fields
                if (fields != null) out += PgBackend.rowDescription(fields)
                out += emit(Portal(result), 0)
            } catch (e: Throwable) {
                out += failure(e)
                break
            }
        }
        out += PgBackend.readyForQuery(transaction)
        return concat(out)
    }

    /** An error as pg reports it: the surface's SQLSTATE; a transaction in progress becomes a failed one. */
    private fun failure(e: Throwable): ByteArray {
        if (transaction == 'T') transaction = 'E'
        if (e is PgProtocolError) return PgBackend.errorResponse("08P01", e.message ?: "protocol violation")
        val error = surface.classify(e)
        return PgBackend.errorResponse(error.code, error.message ?: "error", error.details, error.hint)
    }

    // ── the extended protocol ────────────────────────────────────────────────────────────────

    private inline fun extended(block: () -> ByteArray): ByteArray {
        if (skipping) return EMPTY
        return try { block() } catch (e: Throwable) { skipping = true; failure(e) }
    }

    private fun parse(m: PgFrontend.Parse): ByteArray {
        statements[m.name] = Prepared(m.text, m.types)
        return PgBackend.parseComplete()
    }

    private fun bind(m: PgFrontend.Bind): ByteArray {
        val prepared = statements[m.statement] ?: throw PostgrestError(400, "26000", "prepared statement \"${m.statement}\" does not exist")
        val count = parameterCount(strip(prepared.text))
        if (m.values.size != count) throw PostgrestError(400, "08P01", "bind message supplies ${m.values.size} parameters, but prepared statement \"${m.statement}\" requires $count")
        val text = splice(strip(prepared.text)) { k -> literal(m.values[k - 1], m.formats[k - 1], prepared.types.getOrElse(k - 1) { 0 }) }
        val result = run(text).let { r ->
            if (r is PgResult.Rows) PgResult.Rows(r.fields.mapIndexed { i, f -> f.formatted(m.resultFormats, i) }, r.rows) else r
        }
        portals[m.portal] = Portal(result)
        return PgBackend.bindComplete()
    }

    private fun PgField.formatted(formats: IntArray, i: Int): PgField {
        val asked = if (formats.isEmpty()) 0 else if (formats.size == 1) formats[0] else formats.getOrElse(i) { 0 }
        return if (asked == 1 && PgBackend.binaryCapable(typeOid)) PgField(name, typeOid, 1) else this
    }

    /** A bound parameter as the literal that stands in its place; binary text types are their UTF-8 bytes, jsonb drops its version byte. */
    private fun literal(value: ByteArray?, format: Int, type: Int): String {
        if (value == null) return "NULL"
        if (format == 1) when (type) {
            16 -> return if (value.isNotEmpty() && value[0].toInt() != 0) "TRUE" else "FALSE"
            20 -> return PlatformCodecReader.long(value).toString()
            23 -> return PlatformCodecReader.int(value).toString()
            21 -> return PlatformCodecReader.short(value).toString()
            701 -> return renderNumber(PlatformCodecReader.double(value))
            700 -> return renderNumber(PlatformCodecReader.float(value))
            3802 -> return "'" + value.decodeToString(1).replace("'", "''") + "'"
            0, 19, 25, 705, 1043 -> Unit
            else -> throw PostgrestError(400, "0A000", "binary parameter format for type oid $type is not supported")
        }
        val text = value.decodeToString()
        return when (type) {
            16 -> if (castTo(text, "bool") == true) "TRUE" else "FALSE"
            20, 21, 23, 26 -> castTo(text, "int8").toString()
            700, 701, 1700 -> renderNumber(castTo(text, "float8") as Number)
            else -> "'" + text.replace("'", "''") + "'"
        }
    }

    private fun describe(m: PgFrontend.Describe): ByteArray = when (m.kind) {
        'S' -> {
            val prepared = statements[m.name] ?: throw PostgrestError(400, "26000", "prepared statement \"${m.name}\" does not exist")
            val count = parameterCount(strip(prepared.text))
            val oids = IntArray(count) { prepared.types.getOrElse(it) { 0 }.let { oid -> if (oid == 0) 25 else oid } }
            val result = run(splice(strip(prepared.text)) { "NULL" }, describing = true)
            concat(listOf(PgBackend.parameterDescription(oids), (result as? PgResult.Rows)?.let { PgBackend.rowDescription(it.fields) } ?: PgBackend.noData()))
        }
        'P' -> {
            val portal = portals[m.name] ?: throw PostgrestError(400, "34000", "portal \"${m.name}\" does not exist")
            (portal.result as? PgResult.Rows)?.let { PgBackend.rowDescription(it.fields) } ?: PgBackend.noData()
        }
        else -> throw PgProtocolError("invalid DESCRIBE message subtype ${m.kind}")
    }

    private fun execute(m: PgFrontend.Execute): ByteArray {
        val portal = portals[m.portal] ?: throw PostgrestError(400, "34000", "portal \"${m.portal}\" does not exist")
        return emit(portal, m.maxRows)
    }

    private fun close(m: PgFrontend.Close): ByteArray {
        when (m.kind) {
            'S' -> statements.remove(m.name)
            'P' -> portals.remove(m.name)
            else -> throw PgProtocolError("invalid CLOSE message subtype ${m.kind}")
        }
        return PgBackend.closeComplete()
    }

    /** The rows of [portal] from its offset, at most [maxRows] when positive, then the tag or a suspension. */
    private fun emit(portal: Portal, maxRows: Int): ByteArray = when (val result = portal.result) {
        PgResult.Empty -> PgBackend.emptyQueryResponse()
        is PgResult.Command -> concat(result.notices + PgBackend.commandComplete(result.tag))
        is PgResult.Rows -> {
            val out = ArrayList<ByteArray>()
            val rows = result.rows
            val fields = result.fields
            val end = if (maxRows > 0) minOf(rows.size, portal.offset + maxRows) else rows.size
            var sent = 0
            for (r in portal.offset until end) {
                val values = rows[r].values
                out += PgBackend.dataRow(List(fields.size) { c ->
                    val cell = if (c < values.size) values[c] else null
                    if (fields[c].format == 1) PgBackend.binary(cell, fields[c].typeOid) else PgBackend.text(cell)
                })
                sent++
            }
            portal.offset = end
            out += if (end < rows.size) PgBackend.portalSuspended() else PgBackend.commandComplete("SELECT $sent")
            concat(out)
        }
    }

    // ── statements ───────────────────────────────────────────────────────────────────────────

    /** One statement's result. [describing] plans a statement whose parameters are NULL, for a Describe of it. */
    fun run(text: String, describing: Boolean = false): PgResult {
        val statement = strip(text).trim().removeSuffix(";").trim()
        if (statement.isEmpty()) return PgResult.Empty
        val words = statement.split(Regex("\\s+"), limit = 4)
        val first = words[0].uppercase()
        if (transaction == 'E' && first !in TRANSACTION_ENDS)
            throw PostgrestError(400, "25P02", "current transaction is aborted, commands ignored until end of transaction block")
        return when (first) {
            "SET" -> set(statement)
            "RESET" -> reset(statement.substring(5).trim().ifEmpty { throw PostgrestError(400, "42601", "syntax error at end of input") })
            "SHOW" -> show(statement.substring(4).trim().ifEmpty { throw PostgrestError(400, "42601", "syntax error at end of input") })
            "BEGIN", "START" -> PgResult.Command("BEGIN").also { if (!describing) transaction = 'T' }
            "COMMIT", "END" -> PgResult.Command(if (transaction == 'E') "ROLLBACK" else "COMMIT").also { if (!describing) transaction = 'I' }
            "ROLLBACK", "ABORT" -> PgResult.Command("ROLLBACK").also { if (!describing) transaction = 'I' }
            "DISCARD" -> PgResult.Command("DISCARD " + (words.getOrNull(1)?.uppercase() ?: "ALL"))
            "DEALLOCATE" -> PgResult.Command("DEALLOCATE").also { if (!describing) words.getOrNull(1)?.let { if (it.uppercase() == "ALL") statements.clear() else statements.remove(it.trim('"')) } }
            "CREATE" -> if (words.getOrNull(1)?.uppercase() == "TABLE") PgResult.Command("CREATE TABLE").also { if (!describing) surface.createTable(statement) }
                else throw PostgrestError(400, "0A000", "CREATE ${words.getOrNull(1)?.uppercase()} is not supported")
            "DROP" -> if (words.getOrNull(1)?.uppercase() == "TABLE") PgResult.Command("DROP TABLE").also { if (!describing) surface.dropTable(statement) }
                else throw PostgrestError(400, "0A000", "DROP ${words.getOrNull(1)?.uppercase()} is not supported")
            "SPARQL" -> surface.spasqlRows(statement.substring("SPARQL".length)).let { (names, cursor) -> PgResult.Rows(fields(null, cursor, names), cursor) }
            "SELECT" -> {
                val stmt = SqlParser.parse(statement.toSeries()) ?: throw PostgrestError(400, "42601", "syntax error in \"${statement.take(80)}\"")
                val cursor = stmt.plan(::resolve, compiler = surface.compiler)
                PgResult.Rows(fields(stmt, cursor, emptyList()), cursor)
            }
            "INSERT", "UPDATE", "DELETE", "WITH", "ALTER", "TRUNCATE", "GRANT", "REVOKE", "COPY", "VACUUM", "ANALYZE", "EXPLAIN", "PREPARE", "LISTEN", "NOTIFY" ->
                throw PostgrestError(400, "0A000", "$first is not supported on this wire")
            else -> throw PostgrestError(400, "42601", "syntax error at or near \"${words[0].take(40)}\"")
        }
    }

    /** `pg_settings` is this session's; every other name is the catalog's. */
    private fun resolve(name: String): Cursor =
        if (name.lowercase().removePrefix("pg_catalog.") == "pg_settings") settingsTable() else surface.catalog.resolve(name)

    private fun settingsTable(): Cursor = rowsCursor(
        listOf("name", "setting", "unit", "category", "short_desc", "extra_desc", "context", "vartype", "source", "min_val", "max_val", "enumvals", "boot_val", "reset_val", "sourcefile", "sourceline", "pending_restart"),
        settings.entries.sortedBy { it.key.lowercase() }.map { (name, value) ->
            mapOf(
                "name" to name, "setting" to value, "unit" to null, "category" to "Client Connection Defaults", "short_desc" to "", "extra_desc" to null,
                "context" to "user", "vartype" to if (value == "on" || value == "off") "bool" else if (value.toLongOrNull() != null) "integer" else "string",
                "source" to if (SETTINGS[name] == value) "default" else "session", "min_val" to null,
                "max_val" to null, "enumvals" to null, "boot_val" to SETTINGS[name], "reset_val" to SETTINGS[name], "sourcefile" to null,
                "sourceline" to null, "pending_restart" to false,
            )
        },
        mapOf("unit" to "text", "extra_desc" to "text", "min_val" to "text", "max_val" to "text", "enumvals" to "text", "sourcefile" to "text", "sourceline" to "int8", "pending_restart" to "bool", "boot_val" to "text", "reset_val" to "text"),
    )

    /**
     * `SET [SESSION|LOCAL] name (TO|=) value` and pg's spellings `SET TIME ZONE`, `SET NAMES`, `SET SCHEMA`: recorded; a
     * reported parameter also answers a ParameterStatus. Transaction characteristics, constraints and roles are tags.
     */
    private fun set(statement: String): PgResult {
        val spelled = Regex("(?is)SET\\s+(?:SESSION\\s+|LOCAL\\s+)?(TIME\\s+ZONE|NAMES|SCHEMA)\\s+(.+)").matchEntire(statement)?.let { m ->
            val name = when (m.groupValues[1].uppercase().replace(Regex("\\s+"), " ")) { "TIME ZONE" -> "TimeZone"; "NAMES" -> "client_encoding"; else -> "search_path" }
            name to m.groupValues[2]
        }
        val m = spelled ?: Regex("(?is)SET\\s+(?:SESSION\\s+|LOCAL\\s+)?([A-Za-z_][A-Za-z0-9_.]*)\\s*(?:TO|=)\\s*(.+)").matchEntire(statement)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: Regex("(?is)SET\\s+(?:SESSION\\s+)?CHARACTERISTICS\\s+.*|SET\\s+TRANSACTION\\s+.*|SET\\s+CONSTRAINTS\\s+.*|SET\\s+ROLE\\s+.*|SET\\s+SESSION\\s+AUTHORIZATION\\s+.*").matchEntire(statement)?.let { return PgResult.Command("SET") }
            ?: throw PostgrestError(400, "42601", "syntax error at or near \"${statement.take(40)}\"")
        val name = m.first
        val raw = m.second.trim()
        val value = if (raw.uppercase() == "DEFAULT" || raw.uppercase() == "LOCAL" && name == "TimeZone") SETTINGS[name] ?: "" else unquote(raw)
        if (name.equals("client_encoding", ignoreCase = true) && encodingKey(value) !in ENCODINGS)
            throw PostgrestError(400, "22023", "invalid value for parameter \"client_encoding\": \"$value\"")
        val key = SETTINGS.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        settings[key] = value
        val notices = if (key in REPORTED) listOf(PgBackend.parameterStatus(key, value)) else emptyList()
        return PgResult.Command("SET", notices)
    }

    /** `RESET name`, `RESET ALL`, and the phrases `SHOW` takes. */
    private fun reset(what: String): PgResult {
        if (what.uppercase() == "ALL") { settings.clear(); settings.putAll(SETTINGS); return PgResult.Command("RESET") }
        val name = SHOW_PHRASES[what.lowercase().replace(Regex("\\s+"), " ")] ?: what
        if (name.any { it.isWhitespace() }) throw PostgrestError(400, "42601", "syntax error at or near \"${name.substringAfter(' ').take(40)}\"")
        val key = SETTINGS.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        SETTINGS[key]?.let { settings[key] = it } ?: settings.remove(key)
        return PgResult.Command("RESET", if (key in REPORTED) listOf(PgBackend.parameterStatus(key, settings.getValue(key))) else emptyList())
    }

    /** A SET value: one quoted string, or the bare text with its quoted list members kept as written. */
    private fun unquote(raw: String): String =
        if (raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'")) raw.substring(1, raw.length - 1).replace("''", "'") else raw

    /** `SHOW name`, `SHOW ALL`, and the phrases `SHOW TRANSACTION ISOLATION LEVEL`, `SHOW TIME ZONE`, `SHOW SESSION AUTHORIZATION`. */
    private fun show(what: String): PgResult {
        if (what.uppercase() == "ALL") return PgResult.Rows(
            listOf(PgField("name", 25), PgField("setting", 25), PgField("description", 25)),
            rowsCursor(listOf("name", "setting", "description"), settings.entries.sortedBy { it.key.lowercase() }.map { (k, v) -> mapOf("name" to k, "setting" to v, "description" to "") }),
        )
        val name = SHOW_PHRASES[what.lowercase().replace(Regex("\\s+"), " ")] ?: what
        if (name.any { it.isWhitespace() }) throw PostgrestError(400, "42601", "syntax error at or near \"${name.substringAfter(' ').take(40)}\"")
        val key = settings.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: throw PostgrestError(400, "42704", "unrecognized configuration parameter \"$name\"")
        return PgResult.Rows(listOf(PgField(key, 25)), rowsCursor(listOf(key), listOf(mapOf(key to settings.getValue(key)))))
    }

    /**
     * RowDescription for a result: the executed cursor's metadata when it has rows; for an empty result the
     * statement's own names (the scan's unqualified column names for `*`) with the scan's types where a
     * column reads straight through, text otherwise. Reading the cursor's size runs the plan.
     */
    private fun fields(stmt: SelectStmt?, cursor: Cursor, names: List<String>): List<PgField> {
        val inner = (cursor as? QueryCursor)?.result ?: cursor
        val metas: List<ColumnMeta>? = when {
            inner is ColumnCursor -> inner.metas.toList()
            inner.size > 0 -> inner[0].let { row -> List(row.size) { row.metas[it]() } }
            else -> null
        }
        if (metas != null && inner.size > 0) return metas.map { PgField(it.name.toString(), pgTypeOid(it)) }
        if (stmt == null) return metas?.map { PgField(it.name.toString(), pgTypeOid(it)) } ?: names.map { PgField(it, 25) }
        val visible = metas.orEmpty().filter { !it.name.startsWith("#") }
        val items = stmt.columns.view.toList()
        if (items.size == 1 && (items[0].expr as? LitExpr)?.lit.let { it is StringLiteral && it.asString() == "*" })
            return visible.map { PgField(it.name.toString().substringAfterLast('.'), pgTypeOid(it)) }
        return items.map { item ->
            val ref = (item.expr as? ColumnRef)?.id?.asString()
            val meta = ref?.let { r -> visible.firstOrNull { it.name.toString() == r } ?: visible.singleOrNull { it.name.endsWith(".$r") } }
            PgField(displayName(item), meta?.let(::pgTypeOid) ?: 25L)
        }
    }
}

/** Network-order readers for binary parameters, from offset 0. */
object PlatformCodecReader {
    fun long(b: ByteArray): Long = borg.trikeshed.userspace.nio.platform.spi.PlatformCodec.readLong(b)
    fun int(b: ByteArray): Int = borg.trikeshed.userspace.nio.platform.spi.PlatformCodec.readInt(b)
    fun short(b: ByteArray): Short = borg.trikeshed.userspace.nio.platform.spi.PlatformCodec.readShort(b)
    fun double(b: ByteArray): Double = Double.fromBits(long(b))
    fun float(b: ByteArray): Float = Float.fromBits(int(b))
}

/** The frames of one reply, in order. */
fun concat(chunks: List<ByteArray>): ByteArray {
    val out = ByteArray(chunks.sumOf { it.size })
    var at = 0
    for (c in chunks) { c.copyInto(out, at); at += c.size }
    return out
}
