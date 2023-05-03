package borg.trikeshed.parse.json

class AdaptiveArithmeticCodec {
    private val stateFrequencies: UIntArray = UIntArray(4) { 1u }
    private val totalFrequency: UInt get() = stateFrequencies.sum()

    private fun updateFrequencies(state: Int) {
        stateFrequencies[state]++
    }

    private fun cumulativeFrequency(state: Int): UInt =
        stateFrequencies.take(state).sumOf { it.toUInt() }

    fun encode(input: Iterable<UByte>): Sequence<ULong> = sequence {
        var lower: ULong = 0uL
        var upper: ULong = ULong.MAX_VALUE - 1uL

        for (state in input) {
            val rangeSize: ULong = (upper - lower + 1uL) / totalFrequency.toULong()
            upper = lower + rangeSize * cumulativeFrequency(state.toInt() + 1) - 1uL
            lower += rangeSize * cumulativeFrequency(state.toInt())
            updateFrequencies(state.toInt())
        }
        yield(lower)
    }

    fun decode(input: Iterable<ULong>, length: Int): Sequence<UByte> = sequence {
        val encodedValue = input.first()
        var lower: ULong = 0uL
        var upper: ULong = ULong.MAX_VALUE - 1uL

        for (i in 0 until length) {
            val rangeSize: ULong = (upper - lower + 1uL) / totalFrequency.toULong()

            for (state in stateFrequencies.indices) {
                val nextStateStart: ULong = lower + rangeSize * cumulativeFrequency(state + 1)
                if (encodedValue < nextStateStart) {
                    yield(state.toUByte())
                    upper = nextStateStart - 1uL
                    lower = lower + rangeSize * cumulativeFrequency(state)
                    updateFrequencies(state)
                    break
                }
            }
        }
    }
}

fun main() {
    val codec = AdaptiveArithmeticCodec()
    val input: List<UByte> = listOf<UByte>(0u, 1u, 2u, 3u, 0u, 1u, 2u, 3u, 0u, 1u, 2u, 3u)
    val encoded: List<ULong> = codec.encode(input).toList()
    val decoded: List<UByte> = codec.decode(encoded, input.size).toList()
    println("Input: $input")
    println("Encoded: $encoded")
    println("Decoded: $decoded")
    println("Are input and decoded equal? ${input == decoded}")
}
