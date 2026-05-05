package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniDuckUnicodeTest {
    @Test
    fun unicodeEscapesRoundTrip() {
        val smile = "hello\u263A"
        val doc = DocRowVec(listOf("greeting"), listOf(smile))
        val block = BlockRowVec.mutable()
        block.append(doc)
        val sealed = block.seal()

        val text = MiniDuckBlockCodec.encode(sealed)
        val decoded = MiniDuckBlockCodec.decode(text)

        val first = decoded.child[0]
        val docDecoded = first as DocRowVec
        assertEquals(smile, docDecoded.cells[0])
    }
}
