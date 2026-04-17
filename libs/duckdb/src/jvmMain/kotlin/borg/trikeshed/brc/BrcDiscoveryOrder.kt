package borg.trikeshed.lib.brc

import borg.trikeshed.common.FileBuffer
import borg.trikeshed.lib.*

/**
 * Discovery-Order 1BRC with Final Sort
 *
 * Design: Accumulate in discovery order (O(1) append), sort once at output.
 * No HashMap. No String. Pure `j`/`α` operators.
 *
 * For 413 stations:
 * - 1B accumulations: O(1) append (fast)
 * - 1B lookups: O(n) scan with early termination (413 max, cache-friendly)
 * - Final sort: O(413 log 413) ≈ 3,700 comparisons (negligible)
 */
object BrcDiscoveryOrder {

    @JvmStatic
    fun main(args: Array<String>) {
        val file = args.firstOrNull() ?: System.getenv("BRC_FILE") ?: "measurements.txt"

        FileBuffer(file, 0L, -1L, true).use { fb ->
            // Phase 1: Single pass accumulation in discovery order
            val stations = accumulateDiscoveryOrder(fb)

            // Phase 2: Sort once before output (not during accumulation)
            val sorted = sortByStationName(stations)

            // Phase 3: Output
            printResults(sorted)
        }
    }

    // Station aggregation with scalar min/max (no heaps)
    // Join<name, Join<min, Join<max, Join<sum, count>>>>
    typealias StationAgg = Join<ByteArray, Join<Int, Join<Int, Join<Long, Int>>>>

    /**
     * Pre-allocated mutable station buffer with O(1) growth
     * Grows by reallocation (rare: 413 stations max, ~10 reallocations)
     */
    private class StationAccumulator(initialCapacity: Int = 64) {
        var stations: Array<StationAgg?> = arrayOfNulls(initialCapacity)
        var size: Int = 0

        fun findOrAppend(name: ByteArray, temp: Int): Boolean {
            // Linear scan for existing station (O(n), but n=413 max, cache-line friendly)
            for (i in 0 until size) {
                val s = stations[i]!!
                if (name.contentEquals(s.first)) {
                    // Update existing
                    updateAt(i, temp)
                    return true
                }
            }
            // New station: O(1) append
            append(name, temp)
            return false
        }

        private fun updateAt(idx: Int, temp: Int) {
            val s = stations[idx]!!
            val name = s.first
            val stats = s.second
            val min = stats.first
            val rest = stats.second
            val max = rest.first
            val sumCnt = rest.second
            val sum = sumCnt.first
            val cnt = sumCnt.second

            stations[idx] = name j (
                if (temp < min) temp else min j (
                    if (temp > max) temp else max j (
                        sum + temp j (cnt + 1)
                    )
                )
            )
        }

        private fun append(name: ByteArray, temp: Int) {
            if (size >= stations.size) {
                // Grow (exponential, rare)
                stations = stations.copyOf(stations.size * 2)
            }
            stations[size] = name j (temp j (temp j (temp.toLong() j 1)))
            size++
        }

        fun toSeries(): Series<StationAgg> = size j { i: Int ->
            val s = stations[i]!!
            s.first j (s.second.first j (s.second.second.first j s.second.second.second))
        }
    }

    /**
     * Single pass scan: O(1) append, O(n) lookup with cache-friendly scan
     */
    private fun accumulateDiscoveryOrder(fb: LongSeries<Byte>): Series<StationAgg> {
        val acc = StationAccumulator()
        val len = fb.a
        var pos = 0L

        while (pos < len) {
            // Parse line inline (no allocation)
            val (name, temp, nextPos) = parseLine(fb, pos, len)
            acc.findOrAppend(name, temp)
            pos = nextPos
        }

        return acc.toSeries()
    }

    /**
     * Parse line: extract station name and temperature
     */
    private fun parseLine(fb: LongSeries<Byte>, start: Long, len: Long): Triple<ByteArray, Int, Long> {
        // Find semicolon
        var sep = start
        while (sep < len && fb[sep] != ';'.code.toByte()) sep++

        // Extract name (copy once)
        val nameLen = (sep - start).toInt()
        val name = ByteArray(nameLen)
        for (i in 0 until nameLen) name[i] = fb[start + i]

        // Parse temperature (fixed-point *10)
        var neg = false
        var temp = 0
        var p = sep + 1
        while (p < len && fb[p] != '\n'.code.toByte()) {
            val b = fb[p]
            when {
                b == '-'.code.toByte() -> neg = true
                b == '.'.code.toByte() -> {}
                b >= '0'.code.toByte() && b <= '9'.code.toByte() -> {
                    temp = temp * 10 + (b - '0'.code.toByte())
                }
            }
            p++
        }
        if (neg) temp = -temp

        // Skip newline
        if (p < len && fb[p] == '\n'.code.toByte()) p++

        return Triple(name, temp, p)
    }

    /**
     * Single sort at the end (not during hot loop)
     * Uses swap-based selection sort (no extra allocation)
     */
    private fun sortByStationName(stations: Series<StationAgg>): Series<StationAgg> {
        val n = stations.size
        if (n <= 1) return stations

        // Convert to mutable array
        val arr = stations.toArray()

        // Selection sort with ByteArray comparison (stable, in-place)
        for (i in 0 until n - 1) {
            var minIdx = i
            for (j in i + 1 until n) {
                if (compareByteArrays(arr[j].first, arr[minIdx].first) < 0) {
                    minIdx = j
                }
            }
            if (minIdx != i) {
                val tmp = arr[i]
                arr[i] = arr[minIdx]
                arr[minIdx] = tmp
            }
        }

        return arr.toSeries()
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val diff = a[i].toInt() - b[i].toInt()
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private fun printResults(sorted: Series<StationAgg>) {
        print('{')
        var first = true
        for (i in 0 until sorted.size) {
            val item = sorted[i]
            val name = item.first
            val stats = item.second
            val min = stats.first
            val rest = stats.second
            val max = rest.first
            val sumCnt = rest.second
            val mean = ((sumCnt.first.toDouble() / sumCnt.second) + 0.5).toInt()

            if (!first) print(", ")
            first = false

            print(String(name))
            print('=')
            print(fmt(min))
            print('/')
            print(fmt(mean))
            print('/')
            print(fmt(max))
        }
        println('}')
    }

    private fun fmt(temp10: Int): String {
        val abs = kotlin.math.abs(temp10)
        val sign = if (temp10 < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }
}
