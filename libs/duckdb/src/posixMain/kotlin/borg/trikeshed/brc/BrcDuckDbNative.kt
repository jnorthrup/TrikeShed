/**
 * 1BRC Variant: DuckDB/Native
 *
 * Uses the existing DuckConnection C-interop wrapper (posixMain).
 * Single SQL aggregation via read_csv.
 * Entry point: brcDuckDbNativeMain.
 */
package borg.trikeshed.brc

import borg.trikeshed.duck.DuckConnection
import borg.trikeshed.lib.*
import kotlin.math.floor

fun brcDuckDbNativeMain(args: Array<String>) {
    val file = args.firstOrNull() ?: "measurements.txt"

    val duck = DuckConnection.memory()

    val sql = """
        SELECT station,
               MIN(temp) AS min_t,
               AVG(temp) AS avg_t,
               MAX(temp) AS max_t
        FROM read_csv('$file',
                      header=false,
                      columns={'station':'VARCHAR','temp':'DOUBLE'},
                      delim=';')
        GROUP BY station
        ORDER BY station
    """.trimIndent()

    val cols = duck.querySeries(sql)
    val stations = cols["station"] ?: error("no station column")
    val mins     = cols["min_t"]   ?: error("no min_t column")
    val avgs     = cols["avg_t"]   ?: error("no avg_t column")
    val maxs     = cols["max_t"]   ?: error("no max_t column")

    val n = stations.size
    val sb = StringBuilder("{")
    for (i in 0 until n) {
        if (i > 0) sb.append(", ")
        sb.append(stations[i])
        sb.append('=')
        sb.append(formatTemp((mins[i] as Double)))
        sb.append('/')
        sb.append(formatTemp((avgs[i] as Double)))
        sb.append('/')
        sb.append(formatTemp((maxs[i] as Double)))
    }
    sb.append('}')
    println(sb)

    duck.close()
}

private fun formatTemp(value: Double): String {
    val scaled = value * 10
    val rounded = floor(scaled + 0.5).toLong()
    val abs = if (rounded < 0) -rounded else rounded
    val sign = if (rounded < 0) "-" else ""
    return "${sign}${abs / 10}.${abs % 10}"
}
