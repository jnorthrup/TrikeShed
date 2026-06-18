@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.parse.json

import borg.trikeshed.parse.json.JsonBitmap.LexerEvents.EscapeIncrement
import borg.trikeshed.parse.json.JsonBitmap.LexerEvents.QuoteIncrement
import borg.trikeshed.parse.json.JsonBitmap.LexerEvents.UtfInitiatorOrContinuation
import borg.trikeshed.lib.CZero.nz
import borg.trikeshed.lib.CZero.z

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

    /** we code a 2+2 bit pixel per input byte which marks the state of the lexer and the js state machine*/
    @OptIn(ExperimentalUnsignedTypes::class)
    fun encode(input: UByteArray): UByteArray {
        val output = UByteArray(input.size)
        for (i in input.indices) {
            val jsStateEvent = JsStateEvent.test(input[i])
            val lexerEvent = LexerEvents.test(input[i])
            val i1 = i / 2
            if (i % 2 == 0) output[i1] = (jsStateEvent or (lexerEvent shl 2) shl 4).toUByte()
            else output[i1] = output[i1] or (jsStateEvent or (lexerEvent shl 2)).toUByte()
        }
        return output
    }

    /** we receive an array of 2+2 bit pixels
     * we have a state machine similar to the simdjson example which uses a
     * state transition bitmap of 2 bits per json byte and a 2 bit mask state machine
     * where odd quotes mask the jsStateEvent and odd escapes mask the quote state changes
     *
     * the quote and escape counter is persistent across next adjacent neighbors effectively incrementint
     * the counters for the next bitplane and inverting the mask for the next bitplane.
     *
     * the counter for escapes only goes up to 1, and the next byte always decrements
     * that counter whether or not that is an escape or not.
     *
     * escapes outside of the odd quotes are ignored.  utf bytes outside of the odd quotes are ignored.
     *
     * any non-0 masking bit forced the jsStateEvent to be Unchanged(0)
     *
     *
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    fun decode(
        /** array of 4-bit bitmaps*/
        input: Array<UByteArray>,
        /** the known size of input bytes, or an estimate by default*/
        inputSize: UInt = input.sumOf { it.size.toUInt() * 2U },
    ):
            /** 2 bits out*/
            Array<UByteArray> {
        var quoteCounter = 0
        var escapeCounter = 0
        var inputX = 0
        var inputY = 0 //4 bits
        var outputX = 0
        var outputY = 0 //2 bits
        var maskedSoFar = 0

        /**
         * we go 0 to inputSize swapping in bitplanes as we go
         *
         * we write results over top of the input array, using half the bits
         *
         */
        do {
            do {
                do {
                    val b: UByte = if ((maskedSoFar % 2).z)
                        (input[inputY][inputX / 2].toUInt() shr 4).toUByte()
                    else
                        input[inputY][inputX / 2] and 0b0000_1111U

                    val maskBits = b.toUInt() shr 2 and 0x3u

                    if ((quoteCounter % 2).nz) {
                        when {
                            (escapeCounter % 2).nz -> escapeCounter = 0
                            (maskBits and EscapeIncrement.ordinal.toUInt()).nz -> escapeCounter = 1
                            (maskBits and UtfInitiatorOrContinuation.ordinal.toUInt()).nz -> {}//matters in super rare caase of initiator on top of quotes not yet impl
                            (maskBits and QuoteIncrement.ordinal.toUInt()).nz -> quoteCounter++
                        }
                    } else
                        if ((maskBits and QuoteIncrement.ordinal.toUInt()).nz) quoteCounter++

                    val jsStateBits = if ((quoteCounter % 2).nz) 0u else b.toUInt() and 0x3u
//write the jsStateBits 2 bit result right-to-left in the input bits so we can reuse the input array
                    val writePos = (4 - (outputX % 4)) * 2 // outputX 0..5 0 -> 6 1 -> 4 2 -> 2 3 -> 0 4 -> 6
                    val writeMask = 0x3u shl writePos
                    val writeValue = jsStateBits shl writePos
                    input[outputY][outputX / 4] =
                        (input[outputY][outputX / 4].toUInt() and writeMask.inv()).toUByte() or writeValue.toUByte()
                    outputX++
                    inputX++
                    maskedSoFar++
                } while (outputX / 4 < input[outputY].size)
                outputX = 0
                outputY++
            } while (inputX / 2 < input[inputY].size)
            inputX = 0
            inputY++
        } while (maskedSoFar.toUInt() < inputSize.toUInt())
        return input
    }
}

