package borg.trikeshed.parse.confix

import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfixCborTest {
    @Test
    fun cborArrayIndexing() {
        // CBOR array: [1,2,3] encoded as 0x83 0x01 0x02 0x03
        val bytes = byteArrayOf(0x83.toByte(), 0x01, 0x02, 0x03)
        val src = cborSource(bytes)
        val ctx = contextOf(src.syntax, src.src)
        val resolved = Path.resolve(ctx, path(1))
        assertNotNull(resolved)
        val v = Combinators.reify(resolved, src.syntax)
        // Expecting numeric value 2 (reified as Double)
        assertEquals(2.0, v)
    }

    @Test
    fun cborTextRoundtrip() {
        // CBOR text string: major type 3, length 2, bytes 'h','i' -> 0x62 0x68 0x69
        val bytes = byteArrayOf(0x62.toByte(), 'h'.code.toByte(), 'i'.code.toByte())
        val src = cborSource(bytes)
        val ctx = contextOf(src.syntax, src.src)
        val v = Combinators.reify(ctx, src.syntax)
        // Expecting decoded text 'hi'
        assertEquals("hi", v)
    }
}
