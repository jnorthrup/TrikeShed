/**
 * DuckSeries — SQL queries returning trikeshed Series.
 *
 * JVM-only implementation using duckdb-jdbc.
 * Native support via C API is future work.
 */
package borg.trikeshed.duck

import borg.trikeshed.lib.*
import java.sql.*

/**
 * Execute SQL queries against DuckDB, return trikeshed Series.
 *
 * Usage:
 * ```kotlin
 * val db = DuckSeries.open("candles.duckdb")
 * val close = db.query<Double>("SELECT close FROM candles WHERE pair=? ORDER BY ts", "BTC/USD")
 * val indicators = FeatureExtractor.compute(close, high, low, volume)
 * db.close()
 * ```
 */
class DuckSeries private constructor(private val conn: Connection) {

    /** Execute SQL, return first column as Series */
    fun query(sql: String, vararg args: Any?): Series<Any?> {
        val prepared = conn.prepareStatement(sql)
        args.forEachIndexed { i, arg -> prepared.setObject(i + 1, arg) }
        val rs = prepared.executeQuery()

        val values = mutableListOf<Any?>()
        while (rs.next()) {
            values.add(rs.getObject(1))
        }
        rs.close(); prepared.close()
        return values.size j { values[it] }
    }

    /** Execute SQL, return all columns as Map<name, Series> */
    fun columns(sql: String, vararg args: Any?): Map<String, Series<Any?>> {
        val prepared = conn.prepareStatement(sql)
        args.forEachIndexed { i, arg -> prepared.setObject(i + 1, arg) }
        val rs = prepared.executeQuery()
        val meta = rs.metaData
        val colCount = meta.columnCount

        val rows = mutableListOf<Array<Any?>>()
        while (rs.next()) {
            val row = Array(colCount) { rs.getObject(it + 1) }
            rows.add(row)
        }
        rs.close(); prepared.close()
        if (rows.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, Series<Any?>>()
        for (i in 0 until colCount) {
            val name = meta.getColumnName(i + 1)
            result[name] = rows.size j { r: Int -> rows[r][i] }
        }
        return result
    }

    /** Execute SQL (INSERT, UPDATE, CREATE), return affected rows */
    fun execute(sql: String, vararg args: Any?): Long {
        val prepared = conn.prepareStatement(sql)
        args.forEachIndexed { i, arg -> prepared.setObject(i + 1, arg) }
        val n = prepared.executeUpdate().toLong()
        prepared.close()
        return n
    }

    /** Close connection */
    fun close() { conn.close() }

    companion object {
        /** Open DuckDB file */
        @JvmStatic
        fun open(path: String): DuckSeries {
            val conn = DriverManager.getConnection("jdbc:duckdb:$path")
            return DuckSeries(conn)
        }

        /** Open in-memory DuckDB */
        @JvmStatic
        fun memory(): DuckSeries {
            val conn = DriverManager.getConnection("jdbc:duckdb:")
            return DuckSeries(conn)
        }
    }
}
