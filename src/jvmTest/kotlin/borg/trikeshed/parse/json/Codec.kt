package borg.trikeshed.parse.json

// Define the Codec interface
abstract class Codec<I : Comparable<I>, O> {
    abstract fun encode(input: Iterable<I>): Sequence<O>
    abstract fun decode(output: Iterable<O>): Sequence<I>
    inner class FrequencyModel : Comparable<FrequencyModel> {
        val frequencyTable = mutableMapOf<I, Int>()
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
            var cumulativeFrequency = 0.0
            for ((key, value) in frequencyTable) {
                if (key <= c) {
                    cumulativeFrequency += value
                }
            }
            return cumulativeFrequency / totalFrequency
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
            frequencyModel.updateFrequency(c)
            yield(c.code)
        }
        yield(flushSymbol.code)
    }

    override fun decode(output: Iterable<Int>): Sequence<Char> = sequence {
        for (c in output) {
            val char = c.toChar()
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
}


fun main() {
    val iCodec = CounterCodec()
    val oCodec = CounterCodec()
    val input = listOf("Codec", "example", "Iterable", "Sequence").toString()

    println("Input: $input")

    // Encode input to output
    val output = iCodec.encode("$input".asIterable()).toList()
    println("Output: ${output.toList()}")

    // Decode output to round-trip
    val roundTrip = oCodec.decode(output.asIterable()).asIterable().joinToString("")
    println("Round-trip: ${roundTrip}")

    // Verify if input and round-trip are the same
    println("Input and round-trip are the same: ${input == roundTrip}")

    //verify the two codecs have same frequency model
    val b = iCodec.frequencyModel.compareTo(oCodec.frequencyModel) == 0
    println("Frequency models are the same: $b")
    if (!b) {
        // dump both frequency models
        println("iCodec frequency model: ${iCodec.frequencyModel.frequencyTable}")
        println("oCodec frequency model: ${oCodec.frequencyModel.frequencyTable}")
    }

    // Get frequency and cumulative frequency of 'e'
    val e = 'e'
    val frequency = iCodec.getFrequency(e)
    val cumulativeFrequency = iCodec.frequencyModel.getCumulativeFrequency(e)
    println("Frequency of '$e': $frequency")
    println("Cumulative frequency of '$e': $cumulativeFrequency")
}
