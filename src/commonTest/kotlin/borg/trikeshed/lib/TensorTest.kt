package borg.trikeshed.lib

import kotlin.test.*

class TensorTest {
    @Test
    fun rowMajorCoordinatesRoundTripAndScalarHasOneCell() {
        val shape: Tensor = shapeOf(2, 3, 4)
        assertEquals(24, tensorSize(shape))
        assertEquals(23, tensorOffset(shape, shapeOf(1, 2, 3)))
        assertEquals(12, tensorOffset(shape, shapeOf(1, 0, 0)))
        for (offset in 0 until 24) {
            val index = tensorIndex(shape, offset)
            assertEquals(3, index.size)
            assertEquals(offset / 12, index[0])
            assertEquals(offset / 4 % 3, index[1])
            assertEquals(offset % 4, index[2])
            assertEquals(offset, tensorOffset(shape, index))
        }
        val scalar: MetaSeries<Tensor, Double> = scalarTensor(2.5)
        assertEquals(0, scalar.a.size)
        assertEquals(1, tensorSize(scalar.a))
        assertEquals(0, tensorIndex(scalar.a, 0).size)
        assertEquals(2.5, scalar.b(shapeOf()))
        assertFailsWith<IllegalArgumentException> { scalar.b(shapeOf(0)) }
    }

    @Test
    fun primitiveStorageIsBorrowedAndShapeMetadataIsStable() {
        val dims = intArrayOf(2, 2)
        val floats = floatArrayOf(1f, 2f, 3f, 4f)
        val tensor: MetaSeries<Tensor, Float> = tensorOf(dims.toSeries(), floats)
        dims[0] = 9
        floats[2] = 7f
        assertEquals(2, tensor.a[0])
        assertEquals(7f, tensor.b(shapeOf(1, 0)))
        val doubles = doubleArrayOf(1.25, 2.5)
        val doubleTensor: MetaSeries<Tensor, Double> = tensorOf(shapeOf(2), doubles)
        doubles[1] = 9.75
        assertEquals(9.75, doubleTensor.b(shapeOf(1)))
        val bytes = byteArrayOf(1, -2)
        val byteTensor: MetaSeries<Tensor, Byte> = tensorOf(shapeOf(2), bytes)
        bytes[0] = 7
        assertEquals(7.toByte(), byteTensor.b(shapeOf(0)))
    }

    @Test
    fun arrayValuedCellsRemainDistinctFromScalarStorage() {
        val left = doubleArrayOf(1.0, 2.0)
        val right = doubleArrayOf(3.0, 4.0, 5.0)
        val cells = arrayOf(left, right)
        val tensor: MetaSeries<Tensor, DoubleArray> = tensorOf(shapeOf(2), cells)
        assertEquals(2, tensorSize(tensor.a))
        assertSame(left, tensor.b(shapeOf(0)))
        assertSame(right, tensor.b(shapeOf(1)))
        left[1] = 8.0
        assertEquals(8.0, tensor.b(shapeOf(0))[1])
        val bytes = byteArrayOf(6, 7, 8)
        val scalar: MetaSeries<Tensor, ByteArray> = scalarTensor(bytes)
        assertEquals(1, tensorSize(scalar.a))
        assertSame(bytes, scalar.b(shapeOf()))
        val replacement = doubleArrayOf(9.0)
        cells[1] = replacement
        assertSame(replacement, tensor.b(shapeOf(1)))
    }

    @Test
    fun constructionProjectionAndContractionStayLazy() {
        var reads = 0
        val values: Series<Double> = 6 j { index: Int -> reads++; index.toDouble() }
        val tensor = tensorOf(shapeOf(2, 3), values)
        val projected: MetaSeries<Tensor, Double> = tensor α { it * 2 }
        val covector = projected co { it.b(shapeOf(1, 2)) }
        assertEquals(0, reads)
        assertSame(tensor.a, projected.a)
        assertEquals(10.0, covector.contract())
        assertEquals(1, reads)
    }

    @Test
    fun invalidShapeStorageAndCoordinatesAreRejected() {
        assertFailsWith<IllegalArgumentException> { tensorSize(-1 j { _: Int -> 1 }) }
        assertFailsWith<IllegalArgumentException> { tensorSize(shapeOf(2, -1)) }
        assertFailsWith<IllegalArgumentException> { tensorSize(shapeOf(Int.MAX_VALUE, 2)) }
        assertFailsWith<IllegalArgumentException> { tensorOf(shapeOf(2, 3), FloatArray(5)) }
        assertFailsWith<IllegalArgumentException> { tensorOf(shapeOf(), FloatArray(0)) }
        val shape = shapeOf(2, 3)
        assertFailsWith<IllegalArgumentException> { tensorOffset(shape, shapeOf(1)) }
        assertFailsWith<IllegalArgumentException> { tensorOffset(shape, shapeOf(-1, 0)) }
        assertFailsWith<IllegalArgumentException> { tensorOffset(shape, shapeOf(2, 0)) }
        assertFailsWith<IllegalArgumentException> { tensorOffset(shape, shapeOf(0, 3)) }
        assertFailsWith<IllegalArgumentException> { tensorIndex(shape, -1) }
        assertFailsWith<IllegalArgumentException> { tensorIndex(shape, 6) }
    }

    @Test
    fun zeroExtentsHaveNoCellsRegardlessOfAxisOrder() {
        assertEquals(0, tensorSize(shapeOf(Int.MAX_VALUE, Int.MAX_VALUE, 0)))
        assertEquals(0, tensorSize(shapeOf(0, Int.MAX_VALUE, Int.MAX_VALUE)))
        assertFailsWith<IllegalArgumentException> { tensorSize(shapeOf(0, -1)) }
        val tensor = tensorOf(shapeOf(2, 0, 3), ByteArray(0))
        assertEquals(0, tensorSize(tensor.a))
        assertFailsWith<IllegalArgumentException> { tensor.b(shapeOf(0, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { tensorIndex(tensor.a, 0) }
        assertFailsWith<IllegalArgumentException> { tensorOffset(shapeOf(Int.MAX_VALUE, Int.MAX_VALUE, 0), shapeOf(0, 0, 0)) }
    }
}
