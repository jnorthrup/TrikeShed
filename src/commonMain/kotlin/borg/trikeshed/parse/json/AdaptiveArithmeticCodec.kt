@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.parse.json
class ArithmeticCodec(val nStates: Int, private var frequencies: UIntArray = UIntArray(nStates) { 1u }) {

    fun updateFrequencies(state: Int) = frequencies[state]++

    fun encode(input: Iterable<UInt>): Sequence<Double> = sequence {
        var lower = 0.0
        var upper = 1.0

        // ⚡ Bolt: maintain running totals instead of summing slices in loop
        for (state in input) {
            val stateInt = state.toInt()
            var totalFreq = 0u
            var cumulativeFreq = 0u

            for (i in frequencies.indices) {
                if (i < stateInt) cumulativeFreq += frequencies[i]
                totalFreq += frequencies[i]
            }

            val freq = frequencies[stateInt]

            val range = upper - lower
            upper = lower + range * (cumulativeFreq + freq).toDouble() / totalFreq.toDouble()
            lower = lower + range * cumulativeFreq.toDouble() / totalFreq.toDouble()

            updateFrequencies(stateInt)
        }
        yield(lower)
    }

    fun decode(encoded: Double, length: Int): Sequence<UInt> = sequence {
        var value = encoded
        val decodeFrequencies = frequencies.copyOf()

        repeat(length) {
            val state = findState(value, decodeFrequencies)
            yield(state)
            updateFrequencies(state.toInt(), decodeFrequencies)

            // ⚡ Bolt: iterate once to calculate sum and cumulative freq instead of allocating slice
            val stateInt = state.toInt()
            var totalFreq = 0u
            var cumulativeFreq = 0u

            for (i in decodeFrequencies.indices) {
                if (i < stateInt) cumulativeFreq += decodeFrequencies[i]
                totalFreq += decodeFrequencies[i]
            }

            val freq = decodeFrequencies[stateInt]

            value = (value - cumulativeFreq.toDouble() / totalFreq.toDouble()) * totalFreq.toDouble() / freq.toDouble()
        }
    }

    private fun findState(targetProb: Double, frequencies: UIntArray): UInt {
        var accum = 0.0
        val totalFreq = frequencies.sum()
        for ((i, freq) in frequencies.withIndex()) {
            val prob = freq.toDouble() / totalFreq.toDouble()
            accum += prob
            if (accum > targetProb) {
                return i.toUInt()
            }
        }
        throw IllegalStateException("should never get here")
    }

    private fun updateFrequencies(state: Int, frequencies: UIntArray) = frequencies[state]++
}

fun main() {
    val codec = ArithmeticCodec(4)
    val input = listOf(
        0u,
        1u,
        2u,
        3u,
        0u,
        1u,
        2u,
        3u,
        0u,
        2u,
        3u,
        0u,
        1u,
        2u,
        3u,
        0u,
        1u,
        2u,
        3u,
        0u,
        2u,
        3u,
        0u,
        1u,
        2u,
        3u,
        0u,
        1u,
        2u,
        3u,
        0u,
        1u,
        2u,
        3u,
        0u,
        1u,
        2u,
        3u
    )
    val encoded = codec.encode(input).first()
    val decoded = codec.decode(encoded, input.size)

    println(input)
    println(encoded)
    println(decoded.toList())

    //compare both
    println("matching? ${input.toList() == decoded.toList()} ")
}


