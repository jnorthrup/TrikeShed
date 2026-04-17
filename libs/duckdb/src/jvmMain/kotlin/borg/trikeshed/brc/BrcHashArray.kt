package borg.trikeshed.lib.brc

import borg.trikeshed.common.FileBuffer
import borg.trikeshed.lib.*

/**
 * Hybrid Hash-Array 1BRC
 *
 * Hash table maps station name → array index
 * Dense array stores aggregation data (cache-friendly)
 *
 * No stdlib HashMap. Custom open-addressing hash table.
 * Pure `j`/`α` operators for storage.
 */
object BrcHashArray {

    @JvmStatic
    fun main(args: Array<String>) {
        val file = args.firstOrNull() ?: System.getenv("BRC_FILE") ?: "measurements.txt"

        FileBuffer(file, 0L, -1L, true).use { fb ->
            val result = accumulateHybrid(fb)
            printResults(result)
        }
    }

    // Aggregation data stored in dense array
    // Join<min, Join<max, Join<sum, count>>>
    typealias AggData = Join<Int, Join<Int, Join<Long, Int>>>

    // Result: Join<name, AggData>
    typealias StationAgg = Join<ByteArray, AggData>

    // Simple open-addressing hash table: ByteArray -> Int index
    private class StationIndex(initialCapacity: Int = 256) {
        // Hash table stores (hash, index) pairs, -1 for empty
        var table: Array<HashEntry?> = arrayOfNulls(initialCapacity)
        var mask = initialCapacity - 1  // Power of 2 for fast modulo
        var size = 0

        data class HashEntry(val hash: Int, val name: ByteArray, val index: Int)

        fun findOrInsert(name: ByteArray): Int {
            val h = hashBytes(name)
            var idx = h and mask
            var probe = 0

            while (true) {
                val entry = table[idx]
                when {
                    entry == null -> {
                        // New entry - insert
                        if (size * 2 >= table.size) {
                            grow()
                            return findOrInsert(name)  // Rehash and retry
                        }
                        val newIndex = size
                        table[idx] = HashEntry(h, name.copyOf(), newIndex)
                        size++
                        return newIndex
                    }
                    entry.hash == h && name.contentEquals(entry.name) -> {
                        // Existing - return index
                        return entry.index
                    }
                    else -> {
                        // Collision - linear probe
                        probe++
                        idx = (h + probe) and mask
                    }
                }
            }
        }

        private fun grow() {
            val oldTable = table
            val newCapacity = table.size * 2
            table = arrayOfNulls(newCapacity)
            mask = newCapacity - 1
            size = 0

            // Rehash all entries
            for (entry in oldTable) {
                if (entry != null) {
                    val newIdx = findSlot(entry.hash, entry.name)
                    table[newIdx] = HashEntry(entry.hash, entry.name, entry.index)
                }
            }
            size = oldTable.count { it != null }
        }

        private fun findSlot(h: Int, name: ByteArray): Int {
            var idx = h and mask
            var probe = 0
            while (table[idx] != null) {
                probe++
                idx = (h + probe) and mask
            }
            return idx
        }

        private fun hashBytes(arr: ByteArray): Int {
            // FNV-1a inspired hash
            var h = -2128831035
            for (b in arr) {
                h = h xor (b.toInt() and 0xFF)
                h *= 16777619
            }
            return h
        }
    }

    // Dense aggregation array
    private class Aggregator(initialCapacity: Int = 256) {
        val index = StationIndex(initialCapacity)
        var names: Array<ByteArray?> = arrayOfNulls(initialCapacity)
        var mins: IntArray = IntArray(initialCapacity)
        var maxs: IntArray = IntArray(initialCapacity)
        var sums: LongArray = LongArray(initialCapacity)
        var counts: IntArray = IntArray(initialCapacity)

        fun accumulate(name: ByteArray, temp: Int) {
            val idx = index.findOrInsert(name)

            // Ensure arrays can hold
            if (idx >= names.size) {
                val newSize = names.size * 2
                names = names.copyOf(newSize)
                mins = mins.copyOf(newSize)
                maxs = maxs.copyOf(newSize)
                sums = sums.copyOf(newSize)
                counts = counts.copyOf(newSize)
            }

            if (names[idx] == null) {
                // New station
                names[idx] = name
                mins[idx] = temp
                maxs[idx] = temp
                sums[idx] = temp.toLong()
                counts[idx] = 1
            } else {
                // Update existing
                if (temp < mins[idx]) mins[idx] = temp
                if (temp > maxs[idx]) maxs[idx] = temp
                sums[idx] += temp
                counts[idx]++
            }
        }

        fun toSeries(): Series<StationAgg> {
            // Build from populated indices only
            val validCount = (0 until index.size).count { names[it] != null }
            var outIdx = 0

            return validCount j { i: Int ->
                // Find next valid index
                while (outIdx < names.size && names[outIdx] == null) outIdx++
                val idx = outIdx++

                names[idx]!! j (
                    mins[idx] j (
                        maxs[idx] j (
                            sums[idx] j counts[idx]
                        )
                    )
                )
            }
        }
    }

    private fun accumulateHybrid(fb: LongSeries<Byte>): Series<StationAgg> {
        val agg = Aggregator()
        val len = fb.a
        var pos = 0L

        while (pos < len) {
            // Parse inline
            var sep = pos
            while (sep < len && fb[sep] != ';'.code.toByte()) sep++

            val nameLen = (sep - pos).toInt()
            val name = ByteArray(nameLen)
            for (i in 0 until nameLen) name[i] = fb[pos + i]

            // Parse temp
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

            if (p < len && fb[p] == '\n'.code.toByte()) p++

            agg.accumulate(name, temp)
            pos = p
        }

        return agg.toSeries()
    }

    private fun printResults(results: Series<StationAgg>) {
        // Convert to array and sort for output
        val arr = results.toArray()
        arr.sortWith { a, b -> compareByteArrays(a.first, b.first) }

        print('{')
        var first = true
        for (item in arr) {
            if (!first) print(", ")
            first = false

            val name = item.first
            val stats = item.second
            val min = stats.first
            val rest = stats.second
            val max = rest.first
            val sumCnt = rest.second
            val mean = ((sumCnt.first.toDouble() / sumCnt.second) + 0.5).toInt()

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

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val diff = a[i].toInt() - b[i].toInt()
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private fun fmt(temp10: Int): String {
        val abs = kotlin.math.abs(temp10)
        val sign = if (temp10 < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }
}
