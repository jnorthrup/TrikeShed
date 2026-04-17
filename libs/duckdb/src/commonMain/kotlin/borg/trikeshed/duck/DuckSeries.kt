/**
 * DuckSeries — SQL queries returning trikeshed Series.
 *
 * Minimal SQL→Series adapter. Not an ORM. The schema IS the query.
 */
package borg.trikeshed.duck

import borg.trikeshed.lib.Series

/**
 * DuckSeries: execute SQL queries against DuckDB, return trikeshed Series.
 *
 * On JVM, this uses duckdb-jdbc via JDBC.
 * On Native, this uses cinterop with DuckDB C API.
 */
expect class DuckSeries {
    constructor(path: String?)
    fun query(sql: String, vararg args: Any?): Series<Any?>
    fun columns(sql: String, vararg args: Any?): Map<String, Series<Any?>>
    fun execute(sql: String, vararg args: Any?): Long
    fun close()
}

expect fun duckOpen(path: String): DuckSeries
expect fun duckMemory(): DuckSeries
