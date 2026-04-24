package borg.trikeshed.couch.miniduck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MiniDuckBlockCodecJvmTest {

    private fun <T> at(series: borg.trikeshed.lib.Series<T>, idx: Int): T {
        val it = series.iterator()
        var i = 0
        while (i < idx) { it.next(); i++ }
        return it.next()
    }

    @Test
    fun roundTrip_simpleDoc() {
        val block = BlockRowVec.mutable()
        val doc = DocRowVec(listOf("x"), listOf(42))
        block.append(doc)
        val sealed = block.seal()

        val text = MiniDuckBlockCodec.encode(sealed)
        val decoded = MiniDuckBlockCodec.decode(text)

        assertEquals(1, decoded.rowCount)
        val row = at(decoded.child, 0)
        assertTrue(row is DocRowVec)
        val decodedDoc = row as DocRowVec
        assertEquals(listOf("x"), decodedDoc.keys)
        assertEquals(listOf(42), decodedDoc.cells)
    }

    @Test
    fun roundTrip_blobAndManifold_andEscaping() {
        val block = BlockRowVec.mutable()

        val blob = BlobRowVec(byteArrayOf(1, 2, 3), "application/octet")
        block.append(blob)

        val concept = ManifoldConcept(0xCAFEBABECAFEL, BudgetCoord.full(), DocRowVec(emptyList(), emptyList()))
        block.append(concept)

        val tricky = DocRowVec(listOf("s"), listOf("line1\nquote\"\\end"))
        block.append(tricky)

        val sealed = block.seal()
        val text = MiniDuckBlockCodec.encode(sealed)
        val decoded = MiniDuckBlockCodec.decode(text)

        assertEquals(3, decoded.rowCount)

        val decodedBlob = at(decoded.child, 0)
        assertTrue(decodedBlob is BlobRowVec)
        assertTrue((decodedBlob as BlobRowVec).bytes.contentEquals(byteArrayOf(1, 2, 3)))

        val decodedConcept = at(decoded.child, 1)
        assertTrue(decodedConcept is ManifoldConcept)
        assertEquals(concept.angular, (decodedConcept as ManifoldConcept).angular)

        val decodedTricky = at(decoded.child, 2)
        assertTrue(decodedTricky is DocRowVec)
        assertEquals("line1\nquote\"\\end", (decodedTricky as DocRowVec).cells[0])
    }
}
