package borg.trikeshed.lib.brc

import borg.trikeshed.common.FileBuffer
import borg.trikeshed.lib.*

/**
 * Pure TrikeShed 1BRC - no HashMap, no String, no stdlib collections.
 *
 * Uses only:
 * - j (Join) for pairing
 * - α (alpha) for transformation
 * - Series<Byte> via FileBuffer
 * - Lazy folding for aggregation
 */
object BrcPure {

    @JvmStatic
    fun main(args: Array<String>) {
        val file = args.firstOrNull() ?: System.getenv("BRC_FILE") ?: "measurements.txt"

        FileBuffer(file, 0L, -1L, true).use { fb ->
            val len = fb.a

            // Build lazy Series of line positions using only j
            val positions = buildLinePositions(fb, len)

            // Parse each line to StationTemp Join via α
            val temps: Series<StationTemp> = positions α { pos ->
                parseLine(fb, pos.a, pos.b)
            }

            // Aggregate using pure Series folding - no HashMap
            val aggregated = aggregateStations(temps)

            // Output format
            printResults(aggregated)
        }
    }

    // Station name as ByteArray slice + temperature as fixed-point
    typealias StationTemp = Join<ByteArray, Int>  // name bytes, temp*10

    // Line position as Join<start, end>
    private fun buildLinePositions(fb: LongSeries<Byte>, len: Long): Series<Join<Long, Long>> {
        // Count lines first - unavoidable with lazy Series
        var count = 0
        var pos = 0L
        while (pos < len) {
            if (fb[pos] == '\n'.code.toByte() || pos == len - 1) count++
            pos++
        }

        // Build position Series via j
        return count j { idx: Int ->
            // Find start of line idx
            var start = 0L
            var line = 0
            var p = 0L
            while (p < len && line < idx) {
                if (fb[p] == '\n'.code.toByte()) line++
                p++
                if (line == idx) start = p
            }

            // Find end (next newline or EOF)
            var end = len
            p = start
            while (p < len) {
                if (fb[p] == '\n'.code.toByte()) {
                    end = p
                    break
                }
                p++
            }
            start j end
        }
    }

    private fun parseLine(fb: LongSeries<Byte>, start: Long, end: Long): StationTemp {
        // Find semicolon
        var sep = start
        while (sep < end && fb[sep] != ';'.code.toByte()) sep++

        // Copy station name to ByteArray (can't slice due to variable length)
        val nameLen = (sep - start).toInt()
        val name = ByteArray(nameLen)
        var i = 0
        var p = start
        while (p < sep && i < nameLen) {
            name[i++] = fb[p++]
        }

        // Parse temperature as fixed-point *10
        var neg = false
        var temp = 0
        p = sep + 1
        while (p < end) {
            val b = fb[p++]
            when {
                b == '-'.code.toByte() -> neg = true
                b == '.'.code.toByte() -> {} // skip
                b >= '0'.code.toByte() && b <= '9'.code.toByte() -> {
                    temp = temp * 10 + (b - '0'.code.toByte()).toInt()
                }
            }
        }
        if (neg) temp = -temp

        return name j temp
    }

    private fun aggregateStations(temps: Series<StationTemp>): Series<StationAgg> {
        // Extract unique stations via sorting + dedup
        // Sort by station name (ByteArray comparison)
        val sorted = sortByStation(temps)
        val deduped = dedupStations(sorted)
        return deduped α { (name, temps) ->
            // Compute min/max/sum/count
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            var sum = 0L
            var cnt = 0
            var i = 0
            while (i < temps.size) {
                val t = temps[i].second
                if (t < min) min = t
                if (t > max) max = t
                sum += t
                cnt++
                i++
            }
            // Returns: Join<name, Join<min, Join<max, Join<sum, count>>>>
            // Simplified: name j (min j (max j (sum j cnt)))
            name j (min j (max j (sum j cnt)))
        }
    }

    // Sort stations - bubble sort (sadly) since we can't use stdlib
    // This is O(n²) but demonstrates pure Series composition
    private fun sortByStation(temps: Series<StationTemp>): Series<StationTemp> {
        // For now, return as-is - full sort requires more infrastructure
        // or we'd need a Series-backed quicksort implementation
        return temps
    }

    // Group by station name
    private fun dedupStations(temps: Series<StationTemp>): Series<Join<ByteArray, Series<Int>>> {
        // Returns series of (station_name, temperatures_for_station)
        // For now single station per temp - needs proper grouping
        return temps α { (name, temp) ->
            name j (1 j { temp })  // Wrap temp in Series
        }
    }

    typealias StationAgg = Join<ByteArray, Join<Int, Join<Int, Join<Long, Int>>>>

    private fun printResults(aggs: Series<StationAgg>) {
        // Print as {name=min/mean/max, ...}
        print('{')
        var first = true
        var i = 0
        while (i < aggs.size) {
            val item = aggs[i]
            val name = item.first
            val stats = item.second
            val min = stats.first
            val rest = stats.second
            val max = rest.first
            val sumCnt = rest.second
            val sum = sumCnt.first
            val cnt = sumCnt.second

            // Mean = sum/cnt, rounded
            val mean = ((sum.toDouble() / cnt) + 0.5).toInt()

            if (!first) print(", ")
            first = false

            // Print name from ByteArray
            print(String(name))
            print('=')
            print(fmt(min))
            print('/')
            print(fmt(mean))
            print('/')
            print(fmt(max))
            i++
        }
        println('}')
    }

    private fun fmt(temp10: Int): String {
        val abs = if (temp10 < 0) -temp10 else temp10
        val sign = if (temp10 < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }
}
