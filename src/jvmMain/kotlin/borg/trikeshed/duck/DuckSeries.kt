/**
 * DuckSeries — SQL queries returning trikeshed Series.
 *
 * JVM implementation using duckdb-jdbc.
 */
package borg.trikeshed.duck

import borg.trikeshed.lib.*
import java.sql.*

actual class DuckSeries(private val conn: Connection) {

    actual constructor(path: String?) : this(
        if (path != null && path.isNotEmpty()) {
            DriverManager.getConnection("jdbc:duckdb:$path")
        } else {
            DriverManager.getConnection("jdbc:duckdb:")
        }
    )

    actual fun query(sql: String, vararg args: Any?): Series<Any?> {
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

    actual fun columns(sql: String, vararg args: Any?): Map<String, Series<Any?>> {
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

    actual fun execute(sql: String, vararg args: Any?): Long {
        val prepared = conn.prepareStatement(sql)
        args.forEachIndexed { i, arg -> prepared.setObject(i + 1, arg) }
        val n = prepared.executeUpdate().toLong()
        prepared.close()
        return n
    }

    actual fun close() { conn.close() }
}

actual fun duckOpen(path: String): DuckSeries {
    return DuckSeries(path)
}

actual fun duckMemory(): DuckSeries {
    return DuckSeries(null)
}
