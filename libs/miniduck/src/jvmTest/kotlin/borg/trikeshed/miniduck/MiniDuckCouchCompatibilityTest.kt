package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MiniDuckCouchCompatibilityTest {

    @Test
    fun viewRowRoundTrip() {
        val doc = DocRowVec(listOf("id", "name"), listOf("doc1", "Alice"))
        val view = ViewRowVec(id = "doc1", key = "k1", value = 42, docLoader = { doc })
        val block = BlockRowVec.mutable()
        block.append(view)
        val sealed = block.seal()

        val text = MiniDuckBlockCodec.encode(sealed)
        val decoded = MiniDuckBlockCodec.decode(text)

        val childSeries = decoded.child
        assertNotNull(childSeries)
        assertEquals(1, childSeries.size)

        val first = childSeries[0]
        assertTrue(first is ViewRowVec)
        val viewDecoded = first as ViewRowVec
        assertEquals("doc1", viewDecoded.id)
        assertEquals("k1", viewDecoded.key)
        assertEquals(42, viewDecoded.value)

        val docSeries = viewDecoded.child
        assertNotNull(docSeries)
        assertEquals(1, docSeries.size)
        val childRow = docSeries[0]
        assertTrue(childRow is DocRowVec)
        val docDecoded = childRow as DocRowVec
        assertEquals("doc1", docDecoded.cells[0])
        assertEquals("Alice", docDecoded.cells[1])
    }

    @Test
    fun docRowEscapesRoundTrip() {
        val doc = DocRowVec(listOf("text"), listOf("line1\nline2"))
        val block = BlockRowVec.mutable()
        block.append(doc)
        val sealed = block.seal()

        val text = MiniDuckBlockCodec.encode(sealed)
        val decoded = MiniDuckBlockCodec.decode(text)

        val first = decoded.child[0]
        assertTrue(first is DocRowVec)
        val docDecoded = first as DocRowVec
        assertEquals("line1\nline2", docDecoded.cells[0])
    }
}
