package borg.trikeshed.parse.json

// Define the Codec interface
abstract class Codec<I : Comparable<I>, O> {
    abstract fun encode(input: Iterable<I>): Sequence<O>
    abstract fun decode(output: Iterable<O>): Sequence<I>
    inner class FrequencyModel {
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
    }
}

class CounterCodec : Codec<Char, Int>() {
    val frequencyModel = FrequencyModel()

    override fun encode(input: Iterable<Char>): Sequence<Int> = sequence {
        for (c in input) {
            frequencyModel.updateFrequency(c)
            yield(c.code)
        }
    }

    override fun decode(output: Iterable<Int>): Sequence<Char> = sequence { for (c in output) yield(c.toChar()) }

    fun getFrequency(c: Char): Int {
        return frequencyModel.getFrequency(c)
    }
}


fun main() {
    val codec = CounterCodec()
    val input = listOf("Codec", "example", "Iterable", "Sequence").toString()

    println("Input: $input")

    // Encode input to output
    val output = codec.encode("$input".asIterable())
    println("Output: ${output.toList()}")

    // Decode output to round-trip
    val roundTrip = codec.decode(output.asIterable()).asIterable().joinToString("")
    println("Round-trip: ${roundTrip}")

    // Verify if input and round-trip are the same
    println("Input and round-trip are the same: ${input == roundTrip}")

    // Get frequency and cumulative frequency of 'e'
    val e = 'e'
    val frequency = codec.getFrequency(e)
    val cumulativeFrequency = codec.frequencyModel.getCumulativeFrequency(e)
    println("Frequency of '$e': $frequency")
    println("Cumulative frequency of '$e': $cumulativeFrequency")
}
