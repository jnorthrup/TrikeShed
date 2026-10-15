package borg.trikeshed.hermes

import org.sqlite.Function
import org.sqlite.ProgressHandler
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteException
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteLimits
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Base64

/** Real SQLite execution; database images cross only the supplied userspace storage boundary. */
class HermesSqlite(
    val readImage: (String) -> ByteArray?,
    val writeImage: (String, ByteArray) -> Unit,
    val callFunction: (String, List<Any?>) -> Any?,
) : AutoCloseable {
    class Connection(
        val db: SQLiteConnection,
        val path: String?,
        val readOnly: Boolean,
        var image: ByteArray?,
        var generation: Long = 0,
    )

    val connections = mutableMapOf<Int, Connection>()
    val writers = mutableMapOf<String, Int>()
    var sequence = 0

    companion object {
        fun encode(value: Any?): Any? = when (value) {
            null -> mapOf("t" to "null")
            is ByteArray -> mapOf("t" to "blob", "v" to Base64.getEncoder().encodeToString(value))
            is Byte, is Short, is Int, is Long -> mapOf("t" to "int", "v" to value.toString())
            is Boolean -> mapOf("t" to "int", "v" to if (value) "1" else "0")
            is Float, is Double -> mapOf("t" to "real", "v" to value.toString())
            is String -> mapOf("t" to "text", "v" to value)
            else -> throw IllegalArgumentException("unsupported SQLite value: ${value::class.simpleName}")
        }

        fun decode(value: Any?): Any? {
            val entry = value as? Map<*, *> ?: throw IllegalArgumentException("typed SQLite value required")
            val text = entry["v"] as? String
            return when (entry["t"]) {
                "null" -> null
                "blob" -> Base64.getDecoder().decode(text ?: error("missing blob"))
                "int" -> text?.toLong() ?: error("missing integer")
                "real" -> when (text) {
                    "nan" -> Double.NaN
                    "inf" -> Double.POSITIVE_INFINITY
                    "-inf" -> Double.NEGATIVE_INFINITY
                    else -> text?.toDouble() ?: error("missing real")
                }
                "text" -> text ?: error("missing text")
                else -> throw IllegalArgumentException("invalid SQLite value type")
            }
        }
    }

    @Synchronized
    fun call(request: Map<String, Any?>): Map<String, Any?> = try {
        mapOf("value" to operation(request))
    } catch (failure: Exception) {
        val sqlite = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLiteException>().firstOrNull()
        val code = sqlite?.resultCode?.code
        val kind = when {
            failure is UnsupportedOperationException -> "NotSupportedError"
            failure is IllegalArgumentException || failure is IllegalStateException -> "ProgrammingError"
            code != null && (code and 255) == 19 -> "IntegrityError"
            code != null && (code and 255) in setOf(11, 26) -> "DatabaseError"
            code != null && (code and 255) in setOf(18, 25) -> "DataError"
            else -> "OperationalError"
        }
        mapOf("error" to mapOf("kind" to kind, "detail" to failure.message,
            "code" to code, "name" to sqlite?.resultCode?.name))
    }

    fun operation(request: Map<String, Any?>): Any? {
        fun number(name: String): Int = (request[name] as? Number)?.toInt() ?: error("missing $name")
        fun text(name: String): String = request[name] as? String ?: error("missing $name")
        val op = text("op")
        if (op == "version") {
            return memory().use { it.libversion() }
        }
        if (op == "constants") return SQLiteErrorCode.values().associate { it.name to it.code }
        if (op == "connect") return connect(request)
        val id = number("id")
        val connection = connections[id] ?: throw IllegalStateException("Cannot operate on a closed database")
        val db = connection.db
        when (op) {
            "lease" -> return mapOf("kind" to "sqlite-image", "path" to connection.path,
                "connection" to id, "generation" to connection.generation.toString(),
                "readonly" to connection.readOnly, "current" to current(connection, id))
            "close" -> {
                // SQLite close rolls back its pending transaction. Do not run guest progress
                // callbacks, checkpoints, or image publication while retiring a lease.
                db.close()
                connections.remove(id)
                if (connection.path != null && writers[connection.path] == id) writers.remove(connection.path)
                return null
            }
            "function" -> {
                val name = text("name")
                val arity = number("arity")
                val function = request["function"] as? String
                if (function == null) Function.destroy(db, name, arity)
                else Function.create(db, name, object : Function() {
                    override fun xFunc() {
                        try {
                            val arguments = (0 until args()).map { at ->
                                when (value_type(at)) {
                                    1 -> value_long(at)
                                    2 -> value_double(at)
                                    3 -> value_text(at)
                                    4 -> value_blob(at)
                                    else -> null
                                }
                            }
                            when (val value = callFunction(function, arguments)) {
                                null -> result()
                                is ByteArray -> result(value)
                                is String -> result(value)
                                is Byte, is Short, is Int, is Long -> result((value as Number).toLong())
                                is Float, is Double -> result((value as Number).toDouble())
                                is Boolean -> result(if (value) 1 else 0)
                                else -> error("user-defined function returned an unsupported type")
                            }
                        } catch (failure: Exception) {
                            error(failure.message ?: "user-defined function raised an exception")
                        }
                    }
                }, arity, if (request["deterministic"] == true) Function.FLAG_DETERMINISTIC else 0)
                return null
            }
            "progress" -> {
                val function = request["function"] as? String
                if (function == null) ProgressHandler.clearHandler(db)
                else ProgressHandler.setHandler(db, number("steps"), object : ProgressHandler() {
                    override fun progress(): Int = try {
                        when (val value = callFunction(function, emptyList())) {
                            null, false -> 0
                            is Number -> if (value.toLong() == 0L) 0 else 1
                            else -> 1
                        }
                    } catch (_: Exception) { 1 }
                })
                return null
            }
            "interrupt" -> { db.database.interrupt(); return null }
        }
        refresh(connection)
        return when (op) {
            "stats" -> stats(connection)
            "execute" -> execute(connection, text("sql"), request["parameters"], request["isolation"] as? String)
            "script" -> {
                val sql = text("sql")
                HermesSqliteSql.check(sql, script = true)
                if (connection.readOnly) HermesSqliteSql.checkReadOnly(sql)
                if (inTransaction(db)) db.createStatement().use { it.execute("COMMIT") }
                try {
                    val rc = db.database._exec(sql)
                    if (rc != 0) db.database.throwex(rc)
                } finally { persist(connection) }
                stats(connection)
            }
            "commit", "rollback" -> {
                if (inTransaction(db)) db.createStatement().use { it.execute(if (op == "commit") "COMMIT" else "ROLLBACK") }
                persist(connection)
                stats(connection)
            }
            "serialize" -> encode(db.serialize("main"))
            else -> throw UnsupportedOperationException("unsupported SQLite operation: $op")
        }
    }

    fun memory(): SQLiteConnection {
        val config = SQLiteConfig().apply {
            enableLoadExtension(false)
            setTempStore(SQLiteConfig.TempStore.MEMORY)
        }
        val db = config.createConnection("jdbc:sqlite::memory:") as SQLiteConnection
        db.setLimit(SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0)
        db.database.enable_load_extension(false)
        return db
    }

    fun connect(request: Map<String, Any?>): Map<String, Any?> {
        val path = (request["path"] as? String)?.takeUnless { it == ":memory:" }
        require(path == null || path.isNotEmpty()) { "temporary database files are unavailable; use :memory:" }
        val readOnly = request["readonly"] == true
        if (path != null && !readOnly && path in writers)
            throw UnsupportedOperationException("a writable SQLite connection already owns this virtual database: $path")
        val image = path?.let(readImage)
        if (path != null && listOf("-wal", "-shm").any { readImage(path + it)?.isNotEmpty() == true })
            throw UnsupportedOperationException("SQLite sidecar import requires a checkpointed database image")
        if ((readOnly || request["existing"] == true) && image == null) throw SQLException("unable to open database file")
        val db = memory()
        try {
            if (image != null && image.isNotEmpty()) db.deserialize("main", image)
            db.busyTimeout = ((request["timeout"] as? Number)?.toDouble()?.times(1000)?.toInt() ?: 5000).coerceAtLeast(0)
            if (readOnly) db.createStatement().use { it.execute("PRAGMA query_only=ON") }
            val id = ++sequence
            val connection = Connection(db, path, readOnly, image)
            connections[id] = connection
            if (path != null && !readOnly) writers[path] = id
            if (!readOnly) persist(connection)
            return stats(connection) + mapOf("id" to id, "path" to path, "readonly" to readOnly)
        } catch (failure: Exception) {
            connections.entries.removeIf { it.value.db === db }
            if (path != null && !readOnly) writers.remove(path)
            db.close()
            throw failure
        }
    }

    fun refresh(connection: Connection) {
        if (connection.path == null) return
        if (!connection.readOnly) {
            val current = readImage(connection.path)
            if (!imagesEqual(connection.image, current))
                throw SQLException("virtual SQLite database image changed under the active writable lease")
            return
        }
        if (inTransaction(connection.db)) return
        val current = readImage(connection.path) ?: throw SQLException("virtual database image disappeared")
        if (connection.image?.contentEquals(current) != true) {
            connection.db.deserialize("main", current)
            connection.image = current
        }
    }

    fun inTransaction(db: SQLiteConnection): Boolean {
        // JDBC autoCommit tracks JDBC's setting, not SQL BEGIN used by the donor.
        return try {
            db.createStatement().use { it.execute("BEGIN") }
            db.createStatement().use { it.execute("ROLLBACK") }
            false
        } catch (failure: SQLiteException) {
            if ((failure.resultCode.code and 255) == 1 && failure.message.orEmpty().contains("within a transaction")) true
            else throw failure
        }
    }

    fun persist(connection: Connection) {
        if (connection.readOnly || connection.path == null || inTransaction(connection.db)) return
        if (!imagesEqual(connection.image, readImage(connection.path)))
            throw SQLException("virtual SQLite database image changed under the active writable lease")
        val image = connection.db.serialize("main")
        if (connection.image?.contentEquals(image) == true) return
        try {
            writeImage(connection.path, image)
            connection.image = image
            connection.generation++
        } catch (failure: Exception) {
            // A failed publication cannot be acknowledged as a committed virtual database.
            connection.image?.takeIf { it.isNotEmpty() }?.let { connection.db.deserialize("main", it) }
            throw SQLException("virtual SQLite image publication failed", failure)
        }
    }

    fun imagesEqual(left: ByteArray?, right: ByteArray?): Boolean =
        if (left == null) right == null else right != null && left.contentEquals(right)

    fun current(connection: Connection, id: Int): Boolean = connection.path == null ||
        (imagesEqual(connection.image, readImage(connection.path)) &&
            (connection.readOnly || writers[connection.path] == id))

    fun stats(connection: Connection): Map<String, Any?> = mapOf(
        "in_transaction" to inTransaction(connection.db),
        "total_changes" to connection.db.database.total_changes().toString(),
        "generation" to connection.generation.toString(),
    )

    fun execute(connection: Connection, sql: String, parameters: Any?, isolation: String?): Map<String, Any?> {
        HermesSqliteSql.check(sql)
        if (connection.readOnly) HermesSqliteSql.checkReadOnly(sql)
        val db = connection.db
        val command = HermesSqliteSql.command(sql)
        val dml = command in setOf("INSERT", "UPDATE", "DELETE", "REPLACE")
        if (isolation != null && dml && !inTransaction(db)) {
            require(isolation in setOf("", "DEFERRED", "IMMEDIATE", "EXCLUSIVE")) { "invalid isolation level" }
            db.createStatement().use { it.execute("BEGIN $isolation") }
        }
        var result: Map<String, Any?>? = null
        try {
            db.prepareStatement(sql).use { statement ->
                val count = statement.parameterMetaData.parameterCount
                val values = when (parameters) {
                    is Map<*, *> -> {
                        val names = HermesSqliteSql.parameters(sql)
                        (1..count).map { index ->
                            val name = names[index] ?: throw IllegalArgumentException("binding $index has no name")
                            if (!parameters.containsKey(name)) throw IllegalArgumentException("missing binding :$name")
                            decode(parameters[name])
                        }
                    }
                    is List<*> -> parameters.map(::decode).also {
                        require(it.size == count) { "Incorrect number of bindings supplied: statement uses $count, supplied ${it.size}" }
                    }
                    else -> if (count == 0) emptyList() else throw IllegalArgumentException("parameters are required")
                }
                values.forEachIndexed { at, value ->
                    when (value) {
                        null -> statement.setNull(at + 1, Types.NULL)
                        is ByteArray -> statement.setBytes(at + 1, value)
                        is Long -> statement.setLong(at + 1, value)
                        is Double -> statement.setDouble(at + 1, value)
                        is String -> statement.setString(at + 1, value)
                    }
                }
                val rows = mutableListOf<List<Any?>>()
                var columns: List<String>? = null
                if (statement.execute()) statement.resultSet.use { cursor ->
                    val meta = cursor.metaData
                    columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                    while (cursor.next()) rows.add((1..meta.columnCount).map { encode(column(cursor, it)) })
                }
                result = mapOf("rows" to rows, "columns" to columns,
                    "rowcount" to if (dml) db.database.changes().toString() else "-1")
            }
            val lastId = db.createStatement().use { statement ->
                statement.executeQuery("SELECT last_insert_rowid()").use { rows -> rows.next(); rows.getLong(1).toString() }
            }
            return result.orEmpty() + mapOf("lastrowid" to lastId) + stats(connection)
        } finally { persist(connection) }
    }

    fun column(cursor: ResultSet, index: Int): Any? = cursor.getObject(index).let { value ->
        when (value) {
            is java.sql.Blob -> value.getBytes(1, value.length().toInt())
            else -> value
        }
    }

    @Synchronized
    override fun close() {
        var failure: Exception? = null
        for (connection in connections.values) try {
            connection.db.close()
        } catch (caught: Exception) { if (failure == null) failure = caught else failure.addSuppressed(caught) }
        connections.clear()
        writers.clear()
        failure?.let { throw it }
    }
}
