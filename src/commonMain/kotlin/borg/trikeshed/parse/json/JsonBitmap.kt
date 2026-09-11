@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.parse.json

import borg.trikeshed.parse.json.JsonBitmap.LexerEvents.EscapeIncrement
import borg.trikeshed.parse.json.JsonBitmap.LexerEvents.QuoteIncrement

object JsonBitmap {

    enum class JsStateEvent(val predicate: (UByte) -> Boolean) {
        Unchanged({ false }),
        ScopeOpen({ it.toUInt() == 0x7bU || it.toUInt() == 0x5bU }),
        ScopeClose({ it.toUInt() == 0x7dU || it.toUInt() == 0x5dU }),
        ValueDelim({ it.toUInt() == 0x2cU }),
        ;

        companion object {
            val cache: Array<JsStateEvent> = entries.drop(1).toTypedArray().reversedArray()

            fun test(byte: UByte): Int {
                return cache.firstOrNull { it.predicate(byte) }?.ordinal ?: Unchanged.ordinal
            }
        }
    }

    enum class LexerEvents(val predicate: (UByte) -> Boolean) {
        Unchanged({ false }),
        QuoteIncrement({ it.toUInt() == 0x22U }),
        EscapeIncrement({ it.toUInt() == 0x5cU }),
        UtfInitiatorOrContinuation({ it >= 0x80U })
        ;

        companion object {
            val cache: Array<LexerEvents> = entries.drop(1).toTypedArray()
            fun test(byte: UByte): Int {
                return cache.firstOrNull { it.predicate(byte) }?.ordinal ?: Unchanged.ordinal
            }
        }
    }

    /**
     * Scalar byte classification: structural event in the low two bits, lexer event
     * in the high two bits of each nibble. Earlier input occupies the high nibble.
     * An odd final byte leaves a zero low nibble; retain the original byte count.
     *
     * The structural-bitmap idea was also explored in jnorthrup/simdjsonbitmapcodec
     * (c3c90fb07ca9e4c8b4fc3c62688ffc1cad4718c3). This repairs TrikeShed's existing
     * two-stage implementation; it does not import that repository's bit masks.
     */
    fun encode(input: UByteArray): UByteArray {
        val output = UByteArray(input.size / 2 + input.size % 2)
        for (i in input.indices) {
            val event = JsStateEvent.test(input[i]) or (LexerEvents.test(input[i]) shl 2)
            val shift = if (i % 2 == 0) 4 else 0
            output[i / 2] = output[i / 2] or (event shl shift).toUByte()
        }
        return output
    }

    /**
     * Compact a contiguous stream of [encode] nibbles in place, retaining quote
     * and escape state across array boundaries (including empty arrays). Split
     * the encoded bytes into chunks; independently padded odd input chunks cannot
     * be concatenated without their individual lengths.
     *
     * The first ceil(inputSize / 4) bytes of the concatenated arrays contain two-bit
     * [JsStateEvent] ordinals, most significant slot first. Remaining bits/bytes
     * are zeroed; array identities and sizes are retained. Omit [inputSize] only
     * when every input nibble is meaningful (or treating final padding as input).
     *
     * This is a lexical index, not JSON or UTF-8 validation. Non-ASCII UTF-8 bytes
     * cannot be ASCII quotes, backslashes or structural punctuation. Only an ASCII
     * backslash inside a string escapes the next byte. Unterminated strings remain
     * masked through the end of the supplied stream.
     */
    fun decode(
        input: Array<UByteArray>,
        inputSize: UInt = input.sumOf { it.size.toLong() * 2 }.also {
            require(it <= UInt.MAX_VALUE.toLong()) { "Bitmap input exceeds UInt byte count" }
        }.toUInt(),
    ): Array<UByteArray> {
        require(inputSize.toLong() <= input.sumOf { it.size.toLong() * 2 }) {
            "Input byte count exceeds bitmap capacity"
        }
        var remaining = inputSize.toLong()
        var quoted = false
        var escaped = false
        var outputChunk = 0
        var outputByte = 0
        var outputBits = 0
        var outputSlots = 0

        for (chunk in input) {
            for (index in chunk.indices) {
                if (remaining == 0L) break
                // Capture BOTH nibbles before any write can overwrite their byte.
                val packed = chunk[index].toInt()
                for (shift in 4 downTo 0 step 4) {
                    if (remaining == 0L) break
                    val nibble = (packed shr shift) and 0xf
                    val lexer = nibble shr 2
                    val structural = if (quoted) 0 else nibble and 3
                    when {
                        escaped -> escaped = false
                        quoted && lexer == EscapeIncrement.ordinal -> escaped = true
                        lexer == QuoteIncrement.ordinal -> quoted = !quoted
                    }
                    outputBits = outputBits or (structural shl (6 - outputSlots * 2))
                    outputSlots++
                    remaining--
                    if (outputSlots == 4 || remaining == 0L) {
                        while (outputByte == input[outputChunk].size) {
                            outputChunk++
                            outputByte = 0
                        }
                        input[outputChunk][outputByte++] = outputBits.toUByte()
                        outputBits = 0
                        outputSlots = 0
                    }
                }
            }
        }
        // Reads have finished: discard the old classification tail without erasing
        // unread nibbles or retaining misleading structural events in unused slots.
        while (outputChunk < input.size) {
            input[outputChunk].fill(0u, outputByte)
            outputChunk++
            outputByte = 0
        }
        return input
    }
}
