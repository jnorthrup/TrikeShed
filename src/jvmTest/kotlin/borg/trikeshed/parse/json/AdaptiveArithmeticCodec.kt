package borg.trikeshed.parse.json
class ArithmeticCodec(val nStates: Int, private var frequencies: UIntArray = UIntArray(nStates) { 1u }) {

    fun updateFrequencies(state: Int) = frequencies[state]++

    fun encode(input: Iterable<UInt>): Sequence<Double> = sequence {
        var lower = 0.0
        var upper = 1.0

        for (state in input) {
            val totalFreq = frequencies.sum()
            val cumulativeFreq = frequencies.sliceArray(0 until state.toInt()).sum()
            val freq = frequencies[state.toInt()]

            val range = upper - lower
            upper = lower + range * (cumulativeFreq + freq).toDouble() / totalFreq.toDouble()
            lower = lower + range * cumulativeFreq.toDouble() / totalFreq.toDouble()

            updateFrequencies(state.toInt())
        }
        yield(lower)
    }

    fun decode(encoded: Double, length: Int): Sequence<UInt> = sequence {
        var value = encoded

        repeat(length) {
            val state = findState(value)
            yield(state)
            updateFrequencies(state.toInt())

            val totalFreq = frequencies.sum()
            val cumulativeFreq = frequencies.sliceArray(0 until state.toInt()).sum()
            val freq = frequencies[state.toInt()]

            value = (value - cumulativeFreq.toDouble() / totalFreq.toDouble()) * totalFreq.toDouble() / freq.toDouble()
        }
    }

    private fun findState(targetProb: Double): UInt {
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
}

fun main() {
    val codec = ArithmeticCodec(4)
    val input = listOf(0u, 1u, 2u, 3u, 0u, 1u, 2u, 3u, 0u, 1u, 2u, 3u, 0u, 1u, 2u, 3u)
    val encoded = codec.encode(input).first()
    val decoded = codec.decode(encoded, input.size)

    println(input)
    println(encoded)
    println(decoded.toList())

    //compare both
    println("matching? ${input.toList() == decoded.toList()} ")
}
