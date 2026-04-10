package borg.literbike.ccek.json

/**
 * JsonBitmap - Port of TrikeShed JsonBitmap.kt
 *
 * 4-bit per byte encoding: 2 bits lexer state + 2 bits JS state
 */

/** JS State Events (2 bits) */
enum class JsStateEvent(val value: Int) {
    Unchanged(0),
    ScopeOpen(1),  // { [
    ScopeClose(2), // } ]
    ValueDelim(3), // ,
}

/** Lexer Events (2 bits) */
enum class LexerEvents(val value: Int) {
    Unchanged(0),
    QuoteIncrement(1),            // "
    EscapeIncrement(2),           // \
    UtfInitiatorOrContinuation(3),// >= 0x80
}

/** JsonBitmap - Structural bitmap for JSON parsing */
object JsonBitmap {
    /**
     * Encode input bytes to 4-bit bitmap
     * Output: 2 bytes per input byte (4 bits each, packed)
     */
    fun encode(input: ByteArray): ByteArray {
        val outputSize = (input.size + 1) / 2
        val output = ByteArray(outputSize)

        for (i in input.indices) {
            val byte = input[i]
            val jsState = testJsState(byte)
            val lexerEvent = testLexerEvent(byte)
            val packed = (jsState.value) or ((lexerEvent.value) shl 2)

            val outIdx = i / 2
            if (i % 2 == 0) {
                // High nibble
                output[outIdx] = (packed shl 4).toByte()
            } else {
                // Low nibble
                output[outIdx] = (output[outIdx].toInt() or packed).toByte()
            }
        }

        return output
    }

    /**
     * Decode bitmap with quote/escape state machine
     * Returns filtered structural events (only outside quotes)
     */
    fun decode(bitmap: ByteArray, inputSize: Int): ByteArray {
        val result = ByteArray((inputSize + 3) / 4) // 2 bits per byte
        var quoteCounter: Int = 0
        var escapeCounter: Int = 0

        for (i in 0 until inputSize) {
            val (jsState, lexerEvent) = unpack(bitmap, i)

            // Update state machine
            if (quoteCounter % 2 != 0) {
                // Inside quotes
                if (escapeCounter % 2 != 0) {
                    escapeCounter = 0
                } else if (lexerEvent == LexerEvents.EscapeIncrement) {
                    escapeCounter = 1
                } else if (lexerEvent == LexerEvents.QuoteIncrement) {
                    quoteCounter += 1
                }
            } else {
                // Outside quotes
                if (lexerEvent == LexerEvents.QuoteIncrement) {
                    quoteCounter += 1
                }
            }

            // Mask JS state if inside quotes
            val maskedState = if (quoteCounter % 2 != 0) {
                JsStateEvent.Unchanged.value
            } else {
                jsState.value
            }

            // Pack 2-bit result (4 results per byte)
            val outIdx = i / 4
            val shift = (3 - (i % 4)) * 2
            result[outIdx] = (result[outIdx].toInt() or (maskedState shl shift)).toByte()
        }

        return result
    }

    /** Test byte for JS state event */
    private fun testJsState(byte: Byte): JsStateEvent {
        return when (byte.toInt() and 0xFF) {
            '{'.code, '['.code -> JsStateEvent.ScopeOpen
            '}'.code, ']'.code -> JsStateEvent.ScopeClose
            ','.code -> JsStateEvent.ValueDelim
            else -> JsStateEvent.Unchanged
        }
    }

    /** Test byte for lexer event */
    private fun testLexerEvent(byte: Byte): LexerEvents {
        return when (byte.toInt() and 0xFF) {
            '"'.code -> LexerEvents.QuoteIncrement
            '\\'.code -> LexerEvents.EscapeIncrement
            in 0x80..0xFF -> LexerEvents.UtfInitiatorOrContinuation
            else -> LexerEvents.Unchanged
        }
    }

    /** Unpack 4-bit value from bitmap at position */
    private fun unpack(bitmap: ByteArray, pos: Int): Pair<JsStateEvent, LexerEvents> {
        val byteIdx = pos / 2
        val isHigh = pos % 2 == 0

        val nibble = if (isHigh) {
            (bitmap[byteIdx].toInt() shr 4) and 0x0F
        } else {
            bitmap[byteIdx].toInt() and 0x0F
        }

        val jsState = when (nibble and 0x03) {
            1 -> JsStateEvent.ScopeOpen
            2 -> JsStateEvent.ScopeClose
            3 -> JsStateEvent.ValueDelim
            else -> JsStateEvent.Unchanged
        }

        val lexerEvent = when ((nibble shr 2) and 0x03) {
            1 -> LexerEvents.QuoteIncrement
            2 -> LexerEvents.EscapeIncrement
            3 -> LexerEvents.UtfInitiatorOrContinuation
            else -> LexerEvents.Unchanged
        }

        return jsState to lexerEvent
    }
}
