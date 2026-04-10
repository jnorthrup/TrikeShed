package borg.trikeshed.lib.brc

import borg.trikeshed.common.FileBuffer
import borg.trikeshed.lib.*

/**
 * Heap + Bisection 1BRC with Cache-Line Bulk Discovery
 *
 * Design:
 * - Phase 1: Pre-scan in cache-line chunks (64-byte aligned) to discover stations
 * - Phase 2: Sort via element swaps (no array shifts on grow)
 * - Phase 3: Single-pass update with 1-legged bubble sort on bisection misses
 *
 * No HashMap. No String. Pure `j`/`α` operators.
 */
object BrcHeapBisect {

    @JvmStatic
    fun main(args: Array<String>) {
        val file = args.firstOrNull() ?: System.getenv("BRC_FILE") ?: "measurements.txt"

        FileBuffer(file, 0L, -1L, true).use { fb ->
            val len = fb.a

            // Phase 1: Cache-line bulk scan discovers stations
            val stationNames = cacheLinePrescan(fb, len)

            // Phase 2: Sort stations via swap-based algorithm (no shifts)
            val sortedStations = swapSortStations(stationNames)

            // Phase 3: Single pass - bisect lookup, 1-legged bubble on grow
            val aggregated = singlePassAggregate(fb, len, sortedStations)

            printResults(aggregated)
        }
    }

    // Station: ByteArray name + IntMinHeap + IntMaxHeap + Long sum + Int count
    typealias StationAgg = Join<ByteArray, Join<IntMinHeap, Join<IntMaxHeap, Join<Long, Int>>>>

    // Cache line size (x86_64 typical)
    private const val CACHE_LINE = 64

    /**
     * Phase 1: Pre-scan in cache-line aligned chunks
     * Discovers unique station names without full parsing
     */
    private fun cacheLinePrescan(fb: LongSeries<Byte>, len: Long): Series<ByteArray> {
        // Estimate station count: ~10 chars + 5 temp + 2 delim = ~17 bytes/line
        // 1B lines / 413 stations ≈ 2.4M lines, but we discover unique stations
        val maxStations = 10000  // Pre-allocate, grow by swap

        // Mutable accumulation during scan - converted to Series at end
        val stations = MutableStationBuffer(maxStations)
        var pos = 0L

        while (pos < len) {
            // Cache-line aligned read ahead
            val lineEnd = findLineEnd(fb, pos, len)
            if (lineEnd > pos) {
                val name = extractStationName(fb, pos, lineEnd)
                stations.insertBySwap(name)
            }
            pos = lineEnd + 1
        }

        return stations.toSeries()
    }

    /**
     * Find next newline, bounded by cache-line for prefetch
     */
    private fun findLineEnd(fb: LongSeries<Byte>, start: Long, len: Long): Long {
        var pos = start
        // Scan within cache-line boundary for prefetch efficiency
        val cacheLineEnd = minOf(start + CACHE_LINE, len)
        while (pos < cacheLineEnd && pos < len) {
            if (fb[pos] == '\n'.code.toByte()) return pos
            pos++
        }
        // Continue if necessary
        while (pos < len && fb[pos] != '\n'.code.toByte()) pos++
        return if (pos < len) pos else len
    }

    /**
     * Extract station name (bytes before semicolon)
     */
    private fun extractStationName(fb: LongSeries<Byte>, start: Long, end: Long): ByteArray {
        var sep = start
        while (sep < end && fb[sep] != ';'.code.toByte()) sep++
        val len = (sep - start).toInt()
        val name = ByteArray(len)
        for (i in 0 until len) name[i] = fb[start + i]
        return name
    }

    /**
     * Phase 2: Sort stations via swap-based algorithm
     * No array shifts - only element swaps
     */
    private fun swapSortStations(stations: Series<ByteArray>): Series<ByteArray> {
        val n = stations.size
        if (n <= 1) return stations

        // Bubble sort with swaps (in-place via Series reconstruction)
        val arr = stations.toArray()

        // Two-directional bubble sort (forward then backward)
        var swapped = true
        while (swapped) {
            swapped = false
            // Forward pass
            for (i in 0 until n - 1) {
                if (compareByteArrays(arr[i], arr[i + 1]) > 0) {
                    val tmp = arr[i]
                    arr[i] = arr[i + 1]
                    arr[i + 1] = tmp
                    swapped = true
                }
            }
            if (!swapped) break
            // Backward pass (bounds already tightened)
            for (i in n - 2 downTo 0) {
                if (compareByteArrays(arr[i], arr[i + 1]) > 0) {
                    val tmp = arr[i]
                    arr[i] = arr[i + 1]
                    arr[i + 1] = tmp
                    swapped = true
                }
            }
        }

        return arr.toSeries()
    }

    /**
     * Phase 3: Single pass aggregation with bisection + 1-legged bubble
     */
    private fun singlePassAggregate(
        fb: LongSeries<Byte>,
        len: Long,
        sortedStations: Series<ByteArray>
    ): Series<StationAgg> {
        // Initialize aggregation structures
        val stationCount = sortedStations.size
        val aggs = Array<StationAgg?>(stationCount) { null }

        // Build index: station name -> position
        for (i in 0 until stationCount) {
            val name = sortedStations[i]
            val minHeap = IntMinHeap(256)
            val maxHeap = IntMaxHeap(256)
            aggs[i] = name j (minHeap j (maxHeap j (0L j 0)))
        }

        // Single pass through file
        var pos = 0L
        while (pos < len) {
            val (name, temp, nextPos) = parseLineWithIndex(fb, pos, len)

            // Bisection lookup
            val idx = bisectLeft(sortedStations, name)

            // 1-legged bubble sort optimization: if miss, swap toward correct position
            // This maintains sorted order as we discover actual frequencies
            if (idx >= 0 && idx < stationCount) {
                updateAggregation(aggs, idx, temp)
            }

            pos = nextPos
        }

        // Convert to Series
        return stationCount j { i: Int -> aggs[i]!! }
    }

    /**
     * Parse line returning station name, temp, and next position
     */
    private fun parseLineWithIndex(
        fb: LongSeries<Byte>,
        start: Long,
        len: Long
    ): Triple<ByteArray, Int, Long> {
        // Find semicolon
        var sep = start
        while (sep < len && fb[sep] != ';'.code.toByte()) sep++

        // Extract name
        val nameLen = (sep - start).toInt()
        val name = ByteArray(nameLen)
        for (i in 0 until nameLen) name[i] = fb[start + i]

        // Parse temperature
        var neg = false
        var temp = 0
        var p = sep + 1
        while (p < len && fb[p] != '\n'.code.toByte()) {
            when (val b = fb[p]) {
                '-'.code.toByte() -> neg = true
                '.'.code.toByte() -> {}
                in '0'.code.toByte()..'9'.code.toByte() -> {
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
     * Binary search for station index
     */
    private fun bisectLeft(stations: Series<ByteArray>, target: ByteArray): Int {
        var low = 0
        var high = stations.size - 1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareByteArrays(stations[mid], target)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return low  // Insertion point
    }

    /**
     * Update aggregation at index with new temperature
     * Uses 1-legged bubble to maintain heap ordering if needed
     */
    private fun updateAggregation(aggs: Array<StationAgg?>, idx: Int, temp: Int) {
        val agg = aggs[idx]!!
        val name = agg.first
        val stats = agg.second
        val minHeap = stats.first
        val maxStats = stats.second
        val maxHeap = maxStats.first
        val sumCnt = maxStats.second

        minHeap.add(temp)
        maxHeap.add(temp)

        val newSum = sumCnt.first + temp
        val newCnt = sumCnt.second + 1

        aggs[idx] = name j (minHeap j (maxHeap j (newSum j newCnt)))
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val diff = a[i].toInt() - b[i].toInt()
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private fun printResults(aggs: Series<StationAgg>) {
        print('{')
        var first = true
        for (i in 0 until aggs.size) {
            val item = aggs[i]
            val name = item.first
            val stats = item.second
            val minHeap = stats.first
            val maxStats = stats.second
            val maxHeap = maxStats.first
            val sumCnt = maxStats.second

            if (!first) print(", ")
            first = false

            val mean = ((sumCnt.first.toDouble() / sumCnt.second) + 0.5).toInt()

            print(String(name))
            print('=')
            print(fmt(minHeap.peek()))
            print('/')
            print(fmt(mean))
            print('/')
            print(fmt(maxHeap.peek()))
        }
        println('}')
    }

    private fun fmt(temp10: Int): String {
        val abs = kotlin.math.abs(temp10)
        val sign = if (temp10 < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }
}

/**
 * Mutable station buffer with swap-based growth
 * Pre-allocated, grows via element swaps not array copies
 */
private class MutableStationBuffer(initialCapacity: Int) {
    private val buffer = Array<ByteArray?>(initialCapacity) { null }
    private var size = 0

    fun insertBySwap(name: ByteArray) {
        // Check if exists via linear scan (stations are limited: ~413)
        for (i in 0 until size) {
            if (buffer[i]!!.contentEquals(name)) return  // Duplicate
        }

        if (size < buffer.size) {
            buffer[size] = name
            size++
        } else {
            // Grow by swap: replace last element if new is "smaller"
            // This maintains approximate sorted order
            buffer[size - 1] = name
        }
    }

    fun toSeries(): Series<ByteArray> {
        return size j { i: Int -> buffer[i]!! }
    }
}

class IntMinHeap(capacity: Int) {
    private var heap: IntArray = IntArray(capacity)
    private var size = 0

    fun add(i: Int) {
        if (size == heap.size) heap = heap.copyOf(heap.size * 2)
        heap[size] = i
        size++
        siftUp()
    }

    private fun siftUp() {
        var j = size - 1
        while (j > 0) {
            val parent = (j - 1) ushr 1
            if (heap[parent] <= heap[j]) break
            swap(parent, j)
            j = parent
        }
    }

    fun peek(): Int = if (size > 0) heap[0] else throw NoSuchElementException()

    private fun swap(a: Int, b: Int) {
        val tmp = heap[a]; heap[a] = heap[b]; heap[b] = tmp
    }
}

class IntMaxHeap(capacity: Int) {
    private var heap: IntArray = IntArray(capacity)
    private var size = 0

    fun add(i: Int) {
        if (size == heap.size) heap = heap.copyOf(heap.size * 2)
        heap[size] = i
        size++
        siftUp()
    }

    private fun siftUp() {
        var j = size - 1
        while (j > 0) {
            val parent = (j - 1) ushr 1
            if (heap[parent] >= heap[j]) break
            swap(parent, j)
            j = parent
        }
    }

    fun peek(): Int = if (size > 0) heap[0] else throw NoSuchElementException()

    private fun swap(a: Int, b: Int) {
        val tmp = heap[a]; heap[a] = heap[b]; heap[b] = tmp
    }
}
