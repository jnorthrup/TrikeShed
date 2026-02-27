/*
 * TrikeShed DuckDB Cursor - The Natural Wrapper
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package borg.trikeshed.duck

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.native.concurrent.freeze
import kotlinx.cinterop.*

// Generated cinterop imports
import duckdb.*

fun Any.toStatusInt(): Int = when (this) {
    is UInt -> this.toInt()
    is Int -> this
    is Enum<*> -> (this as Enum<*>).ordinal
    else -> 0
}

fun Any.toTypeInt(): Int = when (this) {
    is UInt -> this.toInt()
    is Int -> this
    is Enum<*> -> (this as Enum<*>).ordinal
    else -> 0
}

data class DuckCursor(
    private val resultStruct: duckdb_result,
    private val connection: duckdb_connection
) : AutoCloseable, Iterable<DuckCursor> {

    val rowCount: Int by lazy { duckdb_row_count(resultStruct.ptr).toInt() }
    val columnCount: Int by lazy { duckdb_column_count(resultStruct.ptr).toInt() }

    fun getColumnName(index: Int): String =
        duckdb_column_name(resultStruct.ptr, index.toULong())?.toKString() ?: "col$index"

    fun getColumnType(index: Int): DuckDBType {
        val typeVal: Any = duckdb_column_type(resultStruct.ptr, index.toULong())
        return DuckDBType.fromValue(typeVal.toTypeInt())
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
        duckdb_destroy_result(resultStruct.ptr)
    }

    private fun materializeColumn(colIdx: Int): Series<Any?> {
        val type = getColumnType(colIdx)
        val rowCount = this.rowCount

        return when (type) {
            DuckDBType.DOUBLE -> {
                val data = Array(rowCount) { idx ->
                    duckdb_value_double(resultStruct.ptr, colIdx.toULong(), idx.toULong())
                }
                Series(rowCount) { data[it] }
            }
            DuckDBType.INTEGER -> {
                val data = Array(rowCount) { idx ->
                    duckdb_value_int32(resultStruct.ptr, colIdx.toULong(), idx.toULong())
                }
                Series(rowCount) { data[it] }
            }
            DuckDBType.BIGINT -> {
                val data = Array(rowCount) { idx ->
                    duckdb_value_int64(resultStruct.ptr, colIdx.toULong(), idx.toULong())
                }
                Series(rowCount) { data[it] }
            }
            DuckDBType.VARCHAR -> {
                val data = Array(rowCount) { idx ->
                    val strPtr = duckdb_value_varchar(resultStruct.ptr, colIdx.toULong(), idx.toULong())
                    strPtr?.toKString()
                }
                Series(rowCount) { data[it] }
            }
            DuckDBType.BOOLEAN -> {
                val data = Array(rowCount) { idx ->
                    duckdb_value_boolean(resultStruct.ptr, colIdx.toULong(), idx.toULong())
                }
                Series(rowCount) { data[it] }
            }
            else -> {
                val data = Array(rowCount) { idx ->
                    val strPtr = duckdb_value_varchar(resultStruct.ptr, colIdx.toULong(), idx.toULong())
                    strPtr?.toKString()
                }
                Series(rowCount) { data[it] }
            }
        }
    }

    companion object {
        fun fromSQL(conn: duckdb_connection, sql: String): DuckCursor {
            val resultStruct = nativeHeap.alloc<duckdb_result>()
            
            memScoped {
                val preparedVar = allocPointerTo<_duckdb_prepared_statement>()
                if (duckdb_prepare(conn, sql, preparedVar.ptr).toStatusInt() != 0) {
                    throw DuckDBException("Failed to prepare: $sql")
                }
                
                val prepared = preparedVar.value!!
                if (duckdb_execute_prepared(prepared, resultStruct.ptr).toStatusInt() != 0) {
                    val error = duckdb_result_error(resultStruct.ptr)
                    val errorMsg = error?.toKString() ?: "Unknown error"
                    val prepDestroyVar = allocPointerTo<_duckdb_prepared_statement>()
                    prepDestroyVar.value = prepared
                    duckdb_destroy_prepare(prepDestroyVar.ptr)
                    nativeHeap.free(resultStruct)
                    throw DuckDBException("Query failed: $errorMsg")
                }
                
                val prepDestroyVar = allocPointerTo<_duckdb_prepared_statement>()
                prepDestroyVar.value = prepared
                duckdb_destroy_prepare(prepDestroyVar.ptr)
            }
            
            return DuckCursor(resultStruct, conn)
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
            val connVar = allocPointerTo<_duckdb_connection>()
            connVar.value = connection
            duckdb_disconnect(connVar.ptr)
            
            val dbVar = allocPointerTo<_duckdb_database>()
            dbVar.value = database
            duckdb_close(dbVar.ptr)
        }
    }

    companion object {
        fun open(path: String): DuckConnection = createConnection(path)
        fun memory(): DuckConnection = createConnection(null)

        private fun createConnection(path: String?): DuckConnection {
            var db: duckdb_database? = null
            var conn: duckdb_connection? = null
            
            memScoped {
                val dbVar = allocPointerTo<_duckdb_database>()
                val connVar = allocPointerTo<_duckdb_connection>()
                
                if (duckdb_open(path, dbVar.ptr).toStatusInt() != 0) {
                    throw DuckDBException("Failed to open database")
                }
                db = dbVar.value!!
                
                if (duckdb_connect(db, connVar.ptr).toStatusInt() != 0) {
                    val dbCloseVar = allocPointerTo<_duckdb_database>()
                    dbCloseVar.value = db
                    duckdb_close(dbCloseVar.ptr)
                    throw DuckDBException("Failed to connect")
                }
                conn = connVar.value!!
            }
            
            return DuckConnection(db!!, conn!!)
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

private fun CPointer<ByteVar>?.toKString(): String? =
    this?.readBytes()?.decodeToString()?.takeIf { it.isNotEmpty() }

private fun CPointer<ByteVar>.readBytes(): ByteArray {
    var length = 0
    while (this[length].toInt() != 0) {
        length++
    }
    val bytes = ByteArray(length)
    for (i in 0 until length) {
        bytes[i] = this[i]
    }
    return bytes
}

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
