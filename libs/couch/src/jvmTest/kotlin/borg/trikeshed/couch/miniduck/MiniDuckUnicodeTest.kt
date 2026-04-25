package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniDuckUnicodeTest {
    @Test
    fun unicodeEscapesRoundTrip() {
        val smile = "" // unit separator char as a test (non-ASCII)
        val doc = DocRowVec(listOf("greeting"), listOf("hello\u263A"))
        val block = BlockRowVec.mutable()
        block.append(doc)
        val sealed = block.seal()

        val text = MiniDuckBlockCodec.encode(sealed)
        val decoded = MiniDuckBlockCodec.decode(text)

        val first = decoded.child[0]
        val docDecoded = first as DocRowVec
        // expect the decoded string to contain the newline literal or converted char depending on parser
        assertEquals("hello\u263A", docDecoded.cells[0])
    }
}
