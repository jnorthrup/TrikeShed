/**
 * DuckSeries — SQL queries returning trikeshed Series.
 *
 * POSIX implementation using DuckDB C API.
 * Requires: `brew install duckdb` (macOS) or equivalent on Linux.
 */
@file:OptIn(ExperimentalForeignApi::class)
package borg.trikeshed.duck

import borg.trikeshed.lib.*
import borg.trikeshed.isam.meta.PlatformCodec
import kotlinx.cinterop.*
import duckdb.*

/** Check if null mask indicates null at given row. */
private fun isNullAt(nullMask: CPointer<BooleanVar>?, row: Int): Boolean =
    nullMask?.reinterpret<ByteVar>()?.get(row)?.toInt() != 0

actual class DuckSeries actual constructor(path: String?) {
    private var dbPtr: CPointerVarOf<duckdb_database>? = null
    private var connPtr: CPointerVarOf<duckdb_connection>? = null

    init {
        dbPtr = nativeHeap.alloc<CPointerVarOf<duckdb_database>>()
        val openResult = duckdb_open(path, dbPtr!!.ptr)
        if (openResult != DuckDBSuccess) {
            error("Failed to open DuckDB database: $path")
        }
        connPtr = nativeHeap.alloc<CPointerVarOf<duckdb_connection>>()
        val connResult = duckdb_connect(dbPtr!!.value, connPtr!!.ptr)
        if (connResult != DuckDBSuccess) {
            duckdb_close(dbPtr!!.ptr)
            error("Failed to connect to DuckDB")
        }
    }

    companion object {
        fun open(path: String): DuckSeries {
            val instance = DuckSeries(path)
            return instance
        }

        fun memory(): DuckSeries = open("")
    }

    actual fun query(sql: String, vararg args: Any?): Series<Any?> {
        val conn = connPtr ?: error("Not connected")
        val stmtPtr = nativeHeap.alloc<CPointerVarOf<duckdb_prepared_statement>>()
        val prepareResult = duckdb_prepare(conn.value, sql, stmtPtr.ptr)
        if (prepareResult != DuckDBSuccess) {
            error("Failed to prepare statement: $sql")
        }
        val stmt = stmtPtr.value ?: error("Null prepared statement")

        args.forEachIndexed { index, arg -> bindParameter(stmt, index, arg) }

        memScoped {
            val result = alloc<duckdb_result>()
            val executeResult = duckdb_execute_prepared(stmt, result.ptr)

            if (executeResult != DuckDBSuccess) {
                val err = duckdb_result_error(result.ptr)?.toKString()
                duckdb_destroy_prepare(stmtPtr.ptr)
                duckdb_destroy_result(result.ptr)
                error("Failed to execute query: $err")
            }

            val rowCount = duckdb_row_count(result.ptr).toInt()
            val values = mutableListOf<Any?>()

            if (rowCount > 0) {
                val columnType = duckdb_column_type(result.ptr, 0u)
                val nullMask = duckdb_nullmask_data(result.ptr, 0u)
                val columnData = duckdb_column_data(result.ptr, 0u)

                for (row in 0 until rowCount) {
                    if (isNullAt(nullMask, row)) {
                        values.add(null)
                    } else {
                        values.add(extractValue(columnData, columnType, row))
                    }
                }
            }

            duckdb_destroy_result(result.ptr)
            duckdb_destroy_prepare(stmtPtr.ptr)

            return values.size j { values[it] }
        }
    }

    actual fun columns(sql: String, vararg args: Any?): Map<String, Series<Any?>> {
        val conn = connPtr ?: error("Not connected")
        val stmtPtr = nativeHeap.alloc<CPointerVarOf<duckdb_prepared_statement>>()
        val prepareResult = duckdb_prepare(conn.value, sql, stmtPtr.ptr)
        if (prepareResult != DuckDBSuccess) {
            error("Failed to prepare statement: $sql")
        }
        val stmt = stmtPtr.value ?: error("Null prepared statement")

        args.forEachIndexed { index, arg -> bindParameter(stmt, index, arg) }

        memScoped {
            val result = alloc<duckdb_result>()
            val executeResult = duckdb_execute_prepared(stmt, result.ptr)

            if (executeResult != DuckDBSuccess) {
                val err = duckdb_result_error(result.ptr)?.toKString()
                duckdb_destroy_prepare(stmtPtr.ptr)
                duckdb_destroy_result(result.ptr)
                error("Failed to execute query: $err")
            }

            val colCount = duckdb_column_count(result.ptr).toInt()
            val rowCount = duckdb_row_count(result.ptr).toInt()

            if (colCount == 0 || rowCount == 0) {
                duckdb_destroy_result(result.ptr)
                duckdb_destroy_prepare(stmtPtr.ptr)
                return emptyMap()
            }

            val resultMap = mutableMapOf<String, Series<Any?>>()

            for (col in 0 until colCount) {
                val colIdx = col.toULong()
                val colName = duckdb_column_name(result.ptr, colIdx)?.toKString() ?: "col$col"
                val columnType = duckdb_column_type(result.ptr, colIdx)
                val nullMask = duckdb_nullmask_data(result.ptr, colIdx)
                val columnData = duckdb_column_data(result.ptr, colIdx)

                val values = mutableListOf<Any?>()
                for (row in 0 until rowCount) {
                    if (isNullAt(nullMask, row)) {
                        values.add(null)
                    } else {
                        values.add(extractValue(columnData, columnType, row))
                    }
                }
                resultMap[colName] = values.size j { values[it] }
            }

            duckdb_destroy_result(result.ptr)
            duckdb_destroy_prepare(stmtPtr.ptr)

            return resultMap
        }
    }

    actual fun execute(sql: String, vararg args: Any?): Long {
        val conn = connPtr ?: error("Not connected")
        val stmtPtr = nativeHeap.alloc<CPointerVarOf<duckdb_prepared_statement>>()
        val prepareResult = duckdb_prepare(conn.value, sql, stmtPtr.ptr)
        if (prepareResult != DuckDBSuccess) {
            error("Failed to prepare statement: $sql")
        }
        val stmt = stmtPtr.value ?: error("Null prepared statement")

        args.forEachIndexed { index, arg -> bindParameter(stmt, index, arg) }

        memScoped {
            val result = alloc<duckdb_result>()
            val executeResult = duckdb_execute_prepared(stmt, result.ptr)

            if (executeResult != DuckDBSuccess) {
                val err = duckdb_result_error(result.ptr)?.toKString()
                duckdb_destroy_prepare(stmtPtr.ptr)
                duckdb_destroy_result(result.ptr)
                error("Failed to execute query: $err")
            }

            val rowsChanged = duckdb_rows_changed(result.ptr)

            duckdb_destroy_result(result.ptr)
            duckdb_destroy_prepare(stmtPtr.ptr)

            return rowsChanged.toLong()
        }
    }

    actual fun close() {
        connPtr?.let { duckdb_disconnect(it.ptr) }
        dbPtr?.let { duckdb_close(it.ptr) }
    }

    private fun bindParameter(stmt: duckdb_prepared_statement, index: Int, arg: Any?) {
        val idx = (index + 1).toULong()

        if (arg == null) {
            val bindResult = duckdb_bind_null(stmt, idx)
            if (bindResult != DuckDBSuccess) error("Failed to bind null at $index")
            return
        }

        val bindResult = when (arg) {
            is Boolean -> duckdb_bind_boolean(stmt, idx, arg)
            is Byte -> duckdb_bind_int8(stmt, idx, arg)
            is Short -> duckdb_bind_int16(stmt, idx, arg)
            is Int -> duckdb_bind_int32(stmt, idx, arg)
            is Long -> duckdb_bind_int64(stmt, idx, arg)
            is Float -> duckdb_bind_float(stmt, idx, arg)
            is Double -> duckdb_bind_double(stmt, idx, arg)
            is String -> duckdb_bind_varchar(stmt, idx, arg)
            else -> error("Unsupported parameter type: ${arg::class.simpleName}")
        }

        if (bindResult != DuckDBSuccess) error("Failed to bind at $index: $arg")
    }

    private fun extractValue(data: CPointer<*>?, type: duckdb_type, row: Int): Any? {
        if (data == null) return null
        val bytePtr = data.reinterpret<ByteVar>()
        val codec = PlatformCodec.currentPlatformCodec

        fun rowBytes(size: Int): ByteArray = ByteArray(size) { bytePtr[row * size + it] }

        return when (type) {
            DUCKDB_TYPE_BOOLEAN -> bytePtr[row] != 0.toByte()
            DUCKDB_TYPE_TINYINT -> bytePtr[row]
            DUCKDB_TYPE_SMALLINT -> codec.readShort(rowBytes(2))
            DUCKDB_TYPE_INTEGER -> codec.readInt(rowBytes(4))
            DUCKDB_TYPE_BIGINT -> codec.readLong(rowBytes(8))
            DUCKDB_TYPE_UTINYINT -> bytePtr[row].toUByte()
            DUCKDB_TYPE_USMALLINT -> codec.readUShort(rowBytes(2))
            DUCKDB_TYPE_UINTEGER -> codec.readUInt(rowBytes(4))
            DUCKDB_TYPE_UBIGINT -> codec.readULong(rowBytes(8))
            DUCKDB_TYPE_FLOAT -> codec.readFloat(rowBytes(4))
            DUCKDB_TYPE_DOUBLE -> codec.readDouble(rowBytes(8))
            DUCKDB_TYPE_VARCHAR -> {
                val ptr = data.reinterpret<CPointerVar<ByteVar>>()
                ptr[row]?.toKString()
            }
            else -> {
                val ptr = data.reinterpret<CPointerVar<ByteVar>>()
                ptr[row]?.toKString()
            }
        }
    }
}

actual fun duckOpen(path: String): DuckSeries = DuckSeries.open(path)
actual fun duckMemory(): DuckSeries = DuckSeries.memory()
