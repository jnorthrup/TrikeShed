@file:OptIn(ExperimentalForeignApi::class)
import kotlinx.cinterop.*
import duckdb.*

fun main() {
    memScoped {
        // duckdb_database is a pointer to duckdb_database_struct
        val dbVar = alloc<CPointerVarOf<duckdb_database>>()
        val connVar = alloc<CPointerVarOf<duckdb_connection>>()
        val resultVar = alloc<CPointerVarOf<duckdb_result>>()

        // duckdb_open expects: (const char*, duckdb_database*)
        val openResult = duckdb_open("", dbVar.ptr)
        println("Open result: $openResult")

        if (openResult == DUCKDB_SUCCESS) {
            // duckdb_connect expects: (duckdb_database, duckdb_connection*)
            val connectResult = duckdb_connect(dbVar.value, connVar.ptr)
            println("Connect result: $connectResult")

            if (connectResult == DUCKDB_SUCCESS) {
                // duckdb_query expects: (duckdb_connection, const char*, duckdb_result*)
                val queryResult = duckdb_query(connVar.value, "SELECT 1 as test", resultVar.ptr)
                println("Query result: $queryResult")

                if (queryResult == DUCKDB_SUCCESS) {
                    // duckdb_row_count expects: (duckdb_result*)
                    val rowCount = duckdb_row_count(resultVar.ptr)
                    println("Row count: $rowCount")
                }

                duckdb_destroy_result(resultVar.ptr)
            }
            duckdb_disconnect(connVar.value)
            duckdb_close(dbVar.value)
        }
    }
}