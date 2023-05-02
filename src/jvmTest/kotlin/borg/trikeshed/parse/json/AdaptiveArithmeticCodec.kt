package borg.trikeshed.parse.json

class AdaptiveArithmeticCodec {
    private val stateFrequencies: UIntArray = UIntArray(4) { 1u }
    private val totalFrequency: UInt get() = stateFrequencies.sum()

    private fun updateFrequencies(state: Int) {
        stateFrequencies[state]++
    }

    private fun cumulativeFrequency(state: Int): UInt =
        stateFrequencies.take(state).sumOf { it.toUInt() }

    private fun stateForCumulativeFrequency(cumulativeFrequency: UInt): Int? {
        for (i in stateFrequencies.indices) {
            if (cumulativeFrequency < cumulativeFrequency(i + 1)) {
                return i
            }
        }
        return null
    }

    fun encode(input: Iterable<UByte>): Sequence<ULong> = sequence {
        var rangeStart: ULong = 0uL
        var rangeSize: ULong = ULong.MAX_VALUE
        for (state in input) {
            val stateCumulative: UInt = cumulativeFrequency(state.toInt())
            val rangeDelta: ULong = rangeSize / totalFrequency.toULong()
            rangeStart += stateCumulative.toULong() * rangeDelta
            rangeSize = stateFrequencies[state.toInt()].toULong() * rangeDelta
            updateFrequencies(state.toInt())
        }
        yield(rangeStart)
    }

    fun decode(input: Iterable<ULong>): Sequence<UByte> = sequence {
        for (encodedValue in input) {
            var rangeStart: ULong = 0uL
            var rangeSize: ULong = ULong.MAX_VALUE
            var state = 0
            while (state < stateFrequencies.size) {
                val stateCumulative: UInt = cumulativeFrequency(state)
                val rangeDelta: ULong = rangeSize / totalFrequency.toULong()
                val nextStateStart = rangeStart + stateCumulative.toULong() * rangeDelta
                if (encodedValue < nextStateStart) {
                    yield(state.toUByte())
                    updateFrequencies(state)
                    break
                }
                rangeStart = nextStateStart
                rangeSize = stateFrequencies[state].toULong() * rangeDelta
                state++
            }
        }
    }
}

fun main() {
    val codec = AdaptiveArithmeticCodec()
    val input: List<UByte> = listOf<UByte>(0u, 1u, 2u, 3u, 0u, 1u, 2u, 3u, 0u, 1u, 2u, 3u)
    val encoded: List<ULong> = codec.encode(input).toList()
    val decoded: List<UByte> = codec.decode(encoded).toList()
    println("Input: $input")
    println("Encoded: $encoded")
    println("Decoded: $decoded")
    println("Are input and decoded equal? ${input == decoded}")
}
