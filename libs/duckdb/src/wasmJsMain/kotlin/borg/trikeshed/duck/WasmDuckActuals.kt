package borg.trikeshed.duck

import borg.trikeshed.lib.Series

actual class DuckSeries {
    private val path: String?

    actual constructor(path: String?) {
        this.path = path
    }

    actual fun query(sql: String, vararg args: Any?): Series<Any?> =
        TODO("DuckDB is not implemented for wasmJs yet")

    actual fun columns(sql: String, vararg args: Any?): Map<String, Series<Any?>> =
        TODO("DuckDB is not implemented for wasmJs yet")

    actual fun execute(sql: String, vararg args: Any?): Long =
        TODO("DuckDB is not implemented for wasmJs yet")

    actual fun close() {
        // no-op
    }
}

actual fun duckOpen(path: String): DuckSeries = DuckSeries(path)

actual fun duckMemory(): DuckSeries = DuckSeries(null)
