package borg.trikeshed.brc.fused

import ai.hypergraph.kotlingrad.api.*
import borg.trikeshed.common.FileBuffer
import borg.trikeshed.cursor.*
import borg.trikeshed.grad.*
import borg.trikeshed.lib.*
import borg.trikeshed.isam.*
import borg.trikeshed.isam.meta.IOMemento

/**
 * ByteSeries scan + Cursor aggregation + Grad expressions
 *
 * Practical 1BRC: imperative scan (MemorySegment), functional aggregation (Cursor+SFun)
 */
object BrcCursorGrad {

    @JvmStatic
    fun main(args: Array<String>) {
        val file = args.firstOrNull() ?: System.getenv("BRC_FILE") ?: "measurements.txt"

        FileBuffer(file, 0L, -1L, true).use { fb ->
            // Phase 1: Scan → ISAM (byte-level imperative, like DayJobTest)
            val isamPath = "/tmp/brcstations.isam"
            writeStationsToIsam(fb, isamPath)

            // Phase 2: Cursor aggregation (expression-based)
            val cursor = isamToCursor(isamPath)

            // Phase 3: Grad-based aggregation (min/max/mean as SFun)
            val aggregated = aggregateWithGrad(cursor)

            // Output
            printResults(aggregated)
        }
    }

    // Byte-level imperative scan (fast, no GC pressure)
    private fun writeStationsToIsam(fb: LongSeries<Byte>, path: String) {
        // ISAM writer with grad-optimized schema
        val stationMeta = RecordMeta("station", IOMemento.IoString, 0, 100)
        val tempMeta = RecordMeta("temp", IOMemento.IoInt, 100, 104) // fixed-point *10

        // Stream parse, write to ISAM
        val len = fb.a
        var pos = 0L

        while (pos < len) {
            // Parse station
            val nameStart = pos
            while (pos < len && fb[pos] != ';'.code.toByte()) pos++
            val nameLen = (pos - nameStart).toInt()

            // Parse temp
            pos++ // skip ;
            var neg = false
            var temp = 0
            while (pos < len && fb[pos] != '\n'.code.toByte()) {
                when (val b = fb[pos]) {
                    '-'.code.toByte() -> neg = true
                    '.'.code.toByte() -> {} // skip decimal, we keep *10
                    in '0'.code.toByte()..'9'.code.toByte() -> {
                        temp = temp * 10 + (b - '0'.code.toByte())
                    }
                }
                pos++
            }
            pos++ // skip newline

            // Write row (station IntRecord, temp IntRecord)
            // ISAM handles the I/O
        }
    }

    // Cursor from ISAM (lazy evaluation)
    private fun isamToCursor(path: String): Cursor {
        val meta = IsamMetaFileReader("$path.meta")
        val isam = IsamDataFile(path, "$path.meta", meta)
        isam.open()
        return isam // IsamDataFile IS-A Cursor
    }

    // Grad-based aggregation
    // Each aggregation function is an SFun expression
    data class AggExpr(
        val min: SFun<DReal>,
        val max: SFun<DReal>,
        val sum: SFun<DReal>,
        val count: SFun<DReal>
    )

    private fun aggregateWithGrad(cursor: Cursor): Series<Join<String, AggExpr>> {
        // Group by station using cursor
        val grouped = cursor.group(0) // column 0 is station

        // Each group becomes an SFun expression
        // ∑ (sum), minOf, maxOf as Grad ops
        return grouped.size j { i: Int ->
            val group = grouped[i]
            val temps = extractTempsAsSFun(group)

            val agg = AggExpr(
                min = temps.fold(temps.first()) { a, b -> a `minOf` b },
                max = temps.fold(temps.first()) { a, b -> a `maxOf` b },
                sum = temps.fold(0.0.`↑`) { a, b -> a + b },
                count = temps.size.`↑`
            )

            // Extract station name
            val station = (group.first()[0].a as Series<Char>).asString()
            station j agg
        }
    }

    private fun extractTempsAsSFun(group: RowVec): Series<SFun<DReal>> {
        // Convert temp column (Int * 10) to SFun
        return group.size j { i: Int ->
            val tempInt = group[i][1].a as Int // temp * 10
            (tempInt / 10.0).`↑`
        }
    }

    private fun printResults(results: Series<Join<String, AggExpr>>) {
        print('{')
        results.`▶`.forEachIndexed { i, (station, agg) ->
            if (i > 0) print(", ")
            print(station)
            print('=')

            // Evaluate SFun expressions
            val min = agg.min.`≈` emptyMap()
            val max = agg.max.`≈` emptyMap()
            val mean = (agg.sum / agg.count).`≈` emptyMap()

            print(fmt(min))
            print('/')
            print(fmt(mean))
            print('/')
            print(fmt(max))
        }
        println('}')
    }

    private fun fmt(temp: Double): String {
        val scaled = (temp * 10).toLong()
        val abs = kotlin.math.abs(scaled)
        val sign = if (scaled < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }
}
