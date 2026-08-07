import re

with open("src/commonMain/kotlin/borg/trikeshed/parse/json/Codec.kt", "r") as f:
    content = f.read()

search = """    inner class FrequencyModel : Comparable<FrequencyModel> {
        val frequencyTable = java.util.TreeMap<I, Int>()
        var totalFrequency = 0

        fun updateFrequency(c: I) {
            val currentCount = frequencyTable.getOrDefault(c, 0)
            frequencyTable[c] = currentCount + 1
            totalFrequency++
        }

        fun getFrequency(c: I): Int {
            return frequencyTable.getOrDefault(c, 0)
        }

        fun getCumulativeFrequency(c: I): Double {
            // ⚡ Bolt: Utilize TreeMap to only iterate up to 'c', avoiding full table scans
            var cumulativeFrequency = 0.0
            for (value in frequencyTable.headMap(c, true).values) {
                cumulativeFrequency += value
            }
            return if (totalFrequency == 0) 0.0 else cumulativeFrequency / totalFrequency
        }

        override fun compareTo(other: FrequencyModel): Int {
            //itarate entries
            this.frequencyTable.entries.zip(other.frequencyTable.entries).forEach {
                val toString = it.first.toString()
                val toString1 = it.second.toString()
                if (toString != toString1) {
                    return -1
                }
            }
            return 0
        }
    }
}

class CounterCodec : Codec<Char, Int>() {
    val frequencyModel = FrequencyModel()
    val flushSymbol = '\uFFFF'

    override fun encode(input: Iterable<Char>): Sequence<Int> = sequence {
        for (c in input) {
            val cumulativeFrequency = frequencyModel.getCumulativeFrequency(c)
            val range = IntRange(
                (cumulativeFrequency * 0xFFFF).toInt(),
                (cumulativeFrequency * 0xFFFF).toInt() + frequencyModel.getFrequency(c)
            )
            frequencyModel.updateFrequency(c)
            yield(range.first)
        }
        yield(flushSymbol.code)
    }

    override fun decode(output: Iterable<Int>): Sequence<Char> = sequence {
        for (c in output) {
            // ⚡ Bolt: Optimize decode by hoisting cumulative frequency calculation.
            // The previous implementation used firstOrNull with getCumulativeFrequency inside,
            // which resulted in an O(V^2) loop where V is the vocabulary size.
            // We pre-calculate cumulative frequencies iteratively over sorted entries in O(V log V).
            var char = flushSymbol
            val totalFreq = frequencyModel.totalFrequency.toDouble()
            if (totalFreq > 0) {
                var cumulativeSum = 0.0
                for (entry in frequencyModel.frequencyTable.entries.sortedBy { it.key }) {
                    val lowerBound = ((cumulativeSum / totalFreq) * 0xFFFF).toInt()
                    val upperBound = lowerBound + entry.value
                    if (c in lowerBound until upperBound) {
                        char = entry.key
                        break
                    }
                    cumulativeSum += entry.value
                }
            }
// alt:             // ⚡ Bolt: Iterate once accumulating frequencies to avoid O(V^2) lookup
// alt:             var cumulative = 0.0
// alt:             var foundChar: Char? = null
// alt:             for ((key, value) in frequencyModel.frequencyTable) {
// alt:                 val lowerBound = ((cumulative / frequencyModel.totalFrequency) * 0xFFFF).toInt()
// alt:                 val upperBound = lowerBound + value
// alt:                 if (c in lowerBound until upperBound) {
// alt:                     foundChar = key
// alt:                     break
// alt:                 }
// alt:                 cumulative += value
// alt:             }
// alt:             val char = foundChar ?: flushSymbol
            if (char == flushSymbol) {
                continue
            }
            yield(char)
            frequencyModel.updateFrequency(char)
        }
    }

    fun getFrequency(c: Char): Int {
        return frequencyModel.getFrequency(c)
    }
}"""

replace = """    inner class FrequencyModel : Comparable<FrequencyModel> {
        val frequencies = IntArray(0x10000)
        var totalFrequency = 0
        var activeSymbols: IntArray = IntArray(0)

        fun updateFrequency(c: I) {
            if (c is Char) {
                val code = c.code
                if (frequencies[code] == 0) {
                    frequencies[code]++
                    totalFrequency++
                    val active = IntArray(frequencies.count { it > 0 })
                    var idx = 0
                    for (i in frequencies.indices) {
                        if (frequencies[i] > 0) active[idx++] = i
                    }
                    activeSymbols = active
                } else {
                    frequencies[code]++
                    totalFrequency++
                }
            }
        }

        fun getFrequency(c: I): Int {
            if (c is Char) return frequencies[c.code]
            return 0
        }

        fun getCumulativeFrequency(c: I): Double {
            if (c !is Char) return 0.0
            var cumulativeFrequency = 0.0
            val code = c.code

            val size = activeSymbols.size
            for (i in 0 until size) {
                val symbol = activeSymbols[i]
                if (symbol >= code) break
                cumulativeFrequency += frequencies[symbol]
            }
            return if (totalFrequency == 0) 0.0 else cumulativeFrequency / totalFrequency
        }

        override fun compareTo(other: FrequencyModel): Int {
            for (i in frequencies.indices) {
                if (this.frequencies[i] != other.frequencies[i]) {
                    return this.frequencies[i].compareTo(other.frequencies[i])
                }
            }
            return 0
        }
    }
}

class CounterCodec : Codec<Char, Int>() {
    val frequencyModel = FrequencyModel()
    val flushSymbol = '\uFFFF'

    override fun encode(input: Iterable<Char>): Sequence<Int> = sequence {
        for (c in input) {
            val cumulativeFrequency = frequencyModel.getCumulativeFrequency(c)
            val range = IntRange(
                (cumulativeFrequency * 0xFFFF).toInt(),
                (cumulativeFrequency * 0xFFFF).toInt() + frequencyModel.getFrequency(c)
            )
            frequencyModel.updateFrequency(c)
            yield(range.first)
        }
        yield(flushSymbol.code)
    }

    override fun decode(output: Iterable<Int>): Sequence<Char> = sequence {
        // ⚡ Bolt: decode optimization - hoist lookups and loop state to locals, reuse primitive arrays for frequency mapping
        val freqs = frequencyModel.frequencies

        for (c in output) {
            var char = flushSymbol
            val totalFreq = frequencyModel.totalFrequency.toDouble()

            if (totalFreq > 0.0) {
                val target = c.toDouble() / 0xFFFF * totalFreq
                var cumulativeSum = 0.0

                val symbols = frequencyModel.activeSymbols
                val size = symbols.size

                for (i in 0 until size) {
                    val symbol = symbols[i]
                    val freq = freqs[symbol]
                    val lowerBound = cumulativeSum
                    val upperBound = lowerBound + freq

                    if (target >= lowerBound && target < upperBound) {
                        char = symbol.toChar()
                        break
                    }
                    cumulativeSum += freq
                }
            }

            if (char == flushSymbol) {
                continue
            }
            yield(char)
            frequencyModel.updateFrequency(char)
        }
    }

    fun getFrequency(c: Char): Int {
        return frequencyModel.getFrequency(c)
    }
}"""

new_content = content.replace(search, replace)
if new_content == content:
    print("FAILED CODEC")
else:
    with open("src/commonMain/kotlin/borg/trikeshed/parse/json/Codec.kt", "w") as f:
        f.write(new_content)
    print("SUCCESS CODEC")
