@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package borg.trikeshed.duck

import borg.trikeshed.lib.Series
import kotlinx.cinterop.*
import duckdb.*

data class DuckCursor(
    private val result: CPointer<duckdb_result>,
    private val connection: duckdb_connection
) : AutoCloseable, Iterable<DuckCursor> {

    val rowCount: Int by lazy { duckdb_row_count(result).toInt() }
    val columnCount: Int by lazy { duckdb_column_count(result).toInt() }

    fun getColumnName(index: Int): String =
        duckdb_column_name(result, index.toULong())?.toKString() ?: "col$index"

    fun getColumnType(index: Int): DuckDBType {
        val typeVal = duckdb_column_type(result, index.toULong())
        return DuckDBType.fromValue(typeVal.toInt())
    }

    fun getSeries(index: Int): Series<Any?> = materializeColumn(index)

    fun toSeriesMap(): Map<String, Series<Any?>> {
        val map = mutableMapOf<String, Series<Any?>>()
        for (i in 0 until columnCount) {
            map[getColumnName(i)] = getSeries(i)
        }
        return map
    }

    override fun iterator(): Iterator<DuckCursor> =
        if (rowCount > 0) DuckCursorIterator(this) else emptyList<DuckCursor>().iterator()

    override fun close() {
        duckdb_destroy_result(result)
        nativeHeap.free(result)
    }

    private fun materializeColumn(colIdx: Int): Series<Any?> {
        val type = getColumnType(colIdx)
        val rowCount = this.rowCount

        return when (type) {
            DuckDBType.DOUBLE -> {
                val data = DoubleArray(rowCount) { idx ->
                    duckdb_value_double(result, colIdx.toULong(), idx.toULong())
                }
                Series(rowCount) { data[it] }
            }
            DuckDBType.INTEGER -> {
                val data = IntArray(rowCount) { idx ->
                    duckdb_value_int32(result, colIdx.toULong(), idx.toULong())
                }
                Series(rowCount) { data[it] }
            }
            DuckDBType.BIGINT -> {
                val data = LongArray(rowCount) { idx ->
                    duckdb_value_int64(result, colIdx.toULong(), idx.toULong())
                }
                Series(rowCount) { data[it] }
            }
            DuckDBType.VARCHAR -> {
                val data = Array(rowCount) { idx ->
                    duckdb_value_varchar(result, colIdx.toULong(), idx.toULong())?.toKString()
                }
                Series(rowCount) { data[it] }
            }
            DuckDBType.BOOLEAN -> {
                val data = BooleanArray(rowCount) { idx ->
                    duckdb_value_boolean(result, colIdx.toULong(), idx.toULong())
                }
                Series(rowCount) { data[it] }
            }
            else -> {
                val data = Array(rowCount) { idx ->
                    duckdb_value_varchar(result, colIdx.toULong(), idx.toULong())?.toKString()
                }
                Series(rowCount) { data[it] }
            }
        }
    }

    companion object {
        fun fromSQL(conn: duckdb_connection, sql: String): DuckCursor {
            val result = nativeHeap.alloc<duckdb_result>()
            
            memScoped {
                val prepared = alloc<duckdb_prepared_statementVar>()
                if (duckdb_prepare(conn, sql, prepared.ptr) != DuckDBSuccess) {
                    nativeHeap.free(result)
                    throw DuckDBException("Failed to prepare: $sql")
                }
                
                if (duckdb_execute_prepared(prepared.value, result.ptr) != DuckDBSuccess) {
                    val error = duckdb_result_error(result.ptr)
                    val errorMsg = error?.toKString() ?: "Unknown error"
                    duckdb_destroy_result(result.ptr)
                    duckdb_destroy_prepare(prepared.ptr)
                    nativeHeap.free(result)
                    throw DuckDBException("Query failed: $errorMsg")
                }
                
                duckdb_destroy_prepare(prepared.ptr)
            }
            
            return DuckCursor(result.ptr, conn)
        }
    }
}

private class DuckCursorIterator(private val cursor: DuckCursor) : Iterator<DuckCursor> {
    private var currentRow = 0
    override fun hasNext(): Boolean = currentRow < cursor.rowCount
    override fun next(): DuckCursor {
        if (!hasNext()) throw NoSuchElementException()
        currentRow++
        return cursor
    }
}

data class DuckConnection(
    private val database: duckdb_database,
    private val connection: duckdb_connection
) : AutoCloseable {

    fun query(sql: String): DuckCursor = DuckCursor.fromSQL(connection, sql)

    fun execute(sql: String) {
        query(sql).use { }
    }

    fun querySeries(sql: String): Map<String, Series<Any?>> =
        query(sql).use { it.toSeriesMap() }

    override fun close() {
        memScoped {
            val connVar = alloc<duckdb_connectionVar>()
            connVar.value = connection
            duckdb_disconnect(connVar.ptr)
            
            val dbVar = alloc<duckdb_databaseVar>()
            dbVar.value = database
            duckdb_close(dbVar.ptr)
        }
    }

    companion object {
        fun open(path: String): DuckConnection = createConnection(path)
        fun memory(): DuckConnection = createConnection(null)

        private fun createConnection(path: String?): DuckConnection {
            memScoped {
                val dbVar = alloc<duckdb_databaseVar>()
                val connVar = alloc<duckdb_connectionVar>()
                
                if (duckdb_open(path, dbVar.ptr) != DuckDBSuccess) {
                    throw DuckDBException("Failed to open database")
                }
                val db = dbVar.value!!
                
                if (duckdb_connect(db, connVar.ptr) != DuckDBSuccess) {
                    duckdb_close(dbVar.ptr)
                    throw DuckDBException("Failed to connect")
                }
                return DuckConnection(db, connVar.value!!)
            }
        }
    }
}

enum class DuckDBType {
    INVALID, BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT,
    UTINYINT, USMALLINT, UINTEGER, UBIGINT,
    FLOAT, DOUBLE, TIMESTAMP, DATE, TIME, INTERVAL, HUGEINT,
    VARCHAR, BLOB, DECIMAL, TIMESTAMP_MS, TIMESTAMP_NS,
    ENUM, LIST, STRUCT, MAP, UUID, JSON;

    companion object {
        fun fromValue(value: Int): DuckDBType = when (value) {
            1 -> BOOLEAN
            2 -> TINYINT
            3 -> SMALLINT
            4 -> INTEGER
            5 -> BIGINT
            6 -> UTINYINT
            7 -> USMALLINT
            8 -> UINTEGER
            9 -> UBIGINT
            10 -> FLOAT
            11 -> DOUBLE
            12 -> TIMESTAMP
            13 -> DATE
            14 -> TIME
            15 -> INTERVAL
            16 -> HUGEINT
            17 -> VARCHAR
            18 -> BLOB
            19 -> DECIMAL
            20 -> TIMESTAMP_MS
            21 -> TIMESTAMP_NS
            22 -> ENUM
            23 -> LIST
            24 -> STRUCT
            25 -> MAP
            26 -> UUID
            27 -> JSON
            else -> INVALID
        }
    }
}

class DuckDBException(message: String) : Exception(message)

fun DuckConnection.queryOHLCV(symbol: String): Map<String, Series<Any?>> {
    val sql = """
        SELECT timestamp, open, high, low, close, volume
        FROM candles
        WHERE symbol = '$symbol'
        ORDER BY timestamp
    """
    return querySeries(sql)
}

fun DuckConnection.queryIndicators(symbol: String): Map<String, Series<Any?>> {
    val sql = """
        SELECT
            close,
            (close - LAG(close) OVER (ORDER BY timestamp)) as return,
            AVG(close) OVER (ORDER BY timestamp ROWS 20 PRECEDING) as sma20,
            STDDEV(close) OVER (ORDER BY timestamp ROWS 20 PRECEDING) as vol20
        FROM candles
        WHERE symbol = '$symbol'
        ORDER BY timestamp
    """
    return querySeries(sql)
}
