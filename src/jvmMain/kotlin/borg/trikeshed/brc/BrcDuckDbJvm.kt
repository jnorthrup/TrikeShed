/**
 * 1BRC Variant: DuckDB JVM Integration
 *
 * Uses DuckDB JDBC to load the measurements file and perform
 * SQL aggregation. This variant demonstrates:
 *
 * - DuckDB's CSV reader with auto-detection
 * - SQL GROUP BY aggregation
 * - TrikeShed DuckSeries for result processing
 * - Round-trip from CSV to SQL to Series output
 *
 * Performance characteristics:
 * - Fast CSV parsing (DuckDB native code)
 * - Efficient hash aggregation (DuckDB vectorized)
 * - Minimal JVM overhead (just JDBC wrapper)
 */
package borg.trikeshed.brc

import borg.trikeshed.duck.DuckSeries
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import java.io.File
import kotlin.math.floor

object BrcDuckDbJvm {

    @JvmStatic
    fun main(args: Array<String>) {
        val file = args.firstOrNull() ?: System.getenv("BRC_FILE") ?: "measurements.txt"

        // Verify file exists
        val inputFile = File(file)
        if (!inputFile.exists()) {
            System.err.println("File not found: $file")
            System.exit(1)
        }

        val db = DuckSeries.memory()

        try {
            // Load CSV directly using DuckDB's CSV reader
            // DuckDB auto-detects delimiter and column types
            db.execute("""
                CREATE TABLE measurements AS 
                SELECT * FROM read_csv_auto(
                    '$file',
                    header=false,
                    columns={'station': 'VARCHAR', 'temperature': 'DOUBLE'},
                    delimiter=';'
                )
            """)

            // Aggregate using SQL
            val result = db.columns("""
                SELECT 
                    station,
                    MIN(temperature) as min_temp,
                    AVG(temperature) as avg_temp,
                    MAX(temperature) as max_temp
                FROM measurements
                GROUP BY station
                ORDER BY station
            """)

            val stations = result["station"]!!
            val mins = result["min_temp"]!!
            val avgs = result["avg_temp"]!!
            val maxs = result["max_temp"]!!

            // Format output matching 1BRC requirements
            val sb = StringBuilder("{")
            for (i in 0 until stations.size) {
                if (i > 0) sb.append(", ")
                
                val station = stations[i].toString()
                val min = fmtDouble(mins[i] as Double)
                val avg = fmtDouble(avgs[i] as Double)
                val max = fmtDouble(maxs[i] as Double)
                
                sb.append(station).append('=').append(min).append('/').append(avg).append('/').append(max)
            }
            sb.append('}')
            println(sb.toString())

        } finally {
            db.close()
        }
    }

    /**
     * Format double to 1 decimal place with proper rounding.
     * Uses roundTowardPositive (ceiling) for .5 cases as per 1BRC spec.
     */
    private fun fmtDouble(v: Double): String {
        // Scale by 10, round, then format
        val scaled = v * 10
        val rounded = floor(scaled + 0.5).toInt()
        val abs = kotlin.math.abs(rounded)
        val sign = if (rounded < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }
}
