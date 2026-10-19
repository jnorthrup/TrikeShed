package borg.trikeshed.narsese

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.lib.get
import borg.trikeshed.manifold.ManifoldK
import kotlin.test.*

class BertAlgebraTest {
    @Test
    fun parameterMetaSeriesBorrowsStorageWithRankedCoordinates() {
        val values = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val indexed: MetaSeries<Shape, BertScalar> = (shapeOf(2, 3) j values).indexed
        values[4] = -5f
        assertEquals(-5f, indexed.b(shapeOf(1, 1)))
        assertFailsWith<IllegalArgumentException> { indexed.b(shapeOf(1)) }
        assertFailsWith<IllegalArgumentException> { indexed.b(shapeOf(2, 0)) }
        assertFailsWith<IllegalArgumentException> { indexed.b(shapeOf(0, 3)) }
    }

    @Test
    fun offsetsRetainUtf16BoundsAsAnAtomicTwin() {
        val token = BertToken("bank", "😀 bank", 3, 7)
        val offsets: Twin<Int> = assertNotNull(token.offsets)
        assertEquals(3, offsets.a)
        assertEquals(7, offsets.b)
        assertNull(token.copy(start = null, end = null).offsets)
        assertFailsWith<IllegalArgumentException> { token.copy(end = null).offsets }
        assertFailsWith<IllegalArgumentException> { token.copy(start = null).offsets }
        assertFailsWith<IllegalArgumentException> { token.copy(end = 8).offsets }
    }

    @Test
    fun coordinatesAndTensorBorrowStorageAndEnforceTensorShape() {
        val values = floatArrayOf(1f, 2f, 3f)
        val vector = BertVector(BertToken("bank", "bank"), BertSpan.Exact, values)
        val coordinates = assertNotNull(vector.coordinates)
        val tensor = assertNotNull(vector.tensor)
        assertEquals(3, coordinates.size)
        assertEquals(1, tensor.a.size)
        assertEquals(3, tensor.a[0])
        values[1] = -4f
        assertEquals(-4f, coordinates[1])
        assertEquals(-4f, tensor.b(shapeOf(1)))
        assertFailsWith<IllegalArgumentException> { tensor.b(shapeOf()) }
        assertFailsWith<IllegalArgumentException> { tensor.b(shapeOf(0, 1)) }
        assertFailsWith<IllegalArgumentException> { tensor.b(shapeOf(-1)) }
        assertFailsWith<IllegalArgumentException> { tensor.b(shapeOf(3)) }
        assertNull(vector.copy(span = BertSpan.Missing, values = null).coordinates)
        assertNull(vector.copy(span = BertSpan.Missing, values = null).tensor)
    }

    @Test
    fun cursorRetainsArrayMetadataAndDefersVectorReads() {
        val values = floatArrayOf(1f, 2f)
        val present = BertVector(BertToken("bank", "The bank is open."), BertSpan.Exact, values)
        val missing = BertVector(BertToken("bank", "No target here."), BertSpan.Missing, null)
        var reads = 0
        val vectors: Series<BertVector> = 2 j { i: Int ->
            reads++
            if (i == 0) present else missing
        }
        val cursor: Cursor = vectors.asCursor()
        assertEquals(0, reads)
        assertEquals(2, cursor.size)
        val first = cursor[0]
        val second = cursor[1]
        assertEquals(2, reads)
        assertEquals(4, first.size)
        assertEquals("bank", first[0].a)
        assertEquals("The bank is open.", first[1].a)
        assertEquals(BertSpan.Exact.name, first[2].a)
        assertEquals(BertSpan.Missing.name, second[2].a)
        for (row in s_[first, second].view) {
            val meta = row[3].b()
            assertEquals("values", meta.name)
            assertEquals(IOMemento.IoArray, meta.type)
            val child = assertNotNull(meta.child)
            assertEquals("value", child.name)
            assertEquals(IOMemento.IoFloat, child.type)
        }
        val coordinates = assertIs<Series<Float>>(first[3].a)
        values[0] = 7f
        assertEquals(7f, coordinates[0])
        assertNull(second[3].a)
    }

    @Test
    fun manifoldRetainsCallerCoordinatesAndBorrowedTensorPayload() {
        val values = floatArrayOf(2f, 5f)
        val vector = BertVector(BertToken("bank", "bank"), BertSpan.Exact, values)
        val angular = 0x1020304050607080L
        val budget = BudgetCoord(0.25f, 0.5f, 0.75f)
        val row = vector.asManifold(angular, budget)
        assertEquals(angular, row.b(ManifoldK.Angular))
        assertEquals(budget, row.b(ManifoldK.Budget))
        val tensor = assertIs<MetaSeries<Tensor, Float>>(row.b(ManifoldK.Payload))
        assertSame(tensor, row.b(ManifoldK.Payload))
        assertEquals(2, tensor.a[0])
        values[1] = -9f
        assertEquals(-9f, tensor.b(shapeOf(1)))
        val missing = vector.copy(span = BertSpan.Missing, values = null).asManifold(angular, budget)
        assertNull(missing.b(ManifoldK.Payload))
    }
}
