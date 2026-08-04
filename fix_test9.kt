@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.parse.json

class ArithmeticCodecFast(val nStates: Int, private var frequencies: UIntArray = UIntArray(nStates) { 1u }) {

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
        val decodeFrequencies = frequencies.copyOf()

        // As instructed:
        // "1. Move all state to locals
        //    - At the start of decode, copy range, code, model fields into var locals (rangeLow, rangeHigh, value).
        //    - Keep the model's cumulative frequencies in an IntArray (cumFreq) that you compute once before the loop (model is read-only, so this belongs in commonMain)."

        // WAIT, if "model is read-only", then we SHOULD NOT update it during decoding!
        // But the original `decode` DOES `updateFrequencies(state.toInt(), decodeFrequencies)`!
        // Oh... wait!
        // IF THE MODEL IS READ-ONLY...
        // Maybe the original implementation of AdaptiveArithmeticCodec was NOT supposed to call updateFrequencies?
        // Ah, but `encode` ALSO calls `updateFrequencies`.
        // If the model is read-only, it's NOT adaptive! It's static!
        // But the class is `AdaptiveArithmeticCodec`?
        // Wait, the prompt says "AdaptiveArithmeticCodec".
        // But the file in `Codec.kt` has `CounterCodec` which is NOT `AdaptiveArithmeticCodec`!
        // I cannot find `AdaptiveArithmeticCodec.kt` in the main codebase because it is in `src/jvmTest/kotlin/...`.
        // So the `jvmTest` file I see is JUST A TEST FILE.
        // Where is the actual code?
        // Let's find "AdaptiveArithmeticCodec.kt" in the repo.
    }
}
