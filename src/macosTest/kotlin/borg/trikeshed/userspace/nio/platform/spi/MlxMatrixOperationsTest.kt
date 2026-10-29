package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.JevaMatrix
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class MlxMatrixOperationsTest {
    @Test fun rectanglesRespectTranspositionOffsetsStridesAndMutableInputs() {
        for (transpose in listOf(false, true)) {
            val session = MlxMatrixOperations().open()
            try {
                assertTrue(session.identity.startsWith("mlx-c:matmul:gpu:float32:"), session.identity)
                val a = FloatArray(15) { -999f }
                floatArrayOf(1f, 2f, 3f).copyInto(a, 2)
                floatArrayOf(4f, 5f, 6f).copyInto(a, 7)
                val b = FloatArray(25) { -999f }
                val bOffset = if (transpose) 1 else 3
                val ldb = if (transpose) 5 else 6
                for (row in 0 until 3) for (col in 0 until 4) {
                    b[bOffset + if (transpose) col * ldb + row else row * ldb + col] = (7 + row * 4 + col).toFloat()
                }
                val beforeA = a.copyOf(); val beforeB = b.copyOf()
                val c = FloatArray(18) { -999f }
                session.gemm(2, 4, 3, a, 2, 5, b, bOffset, ldb, transpose, c, 3, 7)
                val expected = floatArrayOf(74f, 80f, 86f, 92f, 173f, 188f, 203f, 218f)
                for (row in 0 until 2) for (col in 0 until 4) assertEquals(expected[row * 4 + col], c[3 + row * 7 + col])
                for (i in c.indices) if (i !in 3..6 && i !in 10..13) assertEquals(-999f, c[i], "output padding $i")
                assertContentEquals(beforeA, a); assertContentEquals(beforeB, b)
                a[2] = 2f
                session.gemm(2, 4, 3, a, 2, 5, b, bOffset, ldb, transpose, c, 3, 7)
                assertContentEquals(floatArrayOf(81f, 88f, 95f, 102f), c.copyOfRange(3, 7))
            } finally { session.close() }
        }
    }

    @Test fun spansRejectOverflowAndZeroInnerDimensionWritesOnlyCells() {
        val session = MlxMatrixOperations().open()
        try {
            assertFailsWith<IllegalArgumentException> {
                session.gemm(Int.MAX_VALUE, 1, 1, FloatArray(1), 0, Int.MAX_VALUE, FloatArray(1), 0, 1, false, FloatArray(1), 0, 1)
            }
            assertFailsWith<IllegalArgumentException> {
                session.gemm(1, 1, 2, FloatArray(2), 0, 1, FloatArray(2), 0, 1, false, FloatArray(1), 0, 1)
            }
            assertFailsWith<IllegalArgumentException> {
                session.gemm(1, 1, 1, FloatArray(1), -1, 1, FloatArray(1), 0, 1, false, FloatArray(1), 0, 1)
            }
            session.gemm(0, 1, 1, FloatArray(0), 0, 1, FloatArray(1), 0, 1, false, FloatArray(0), 0, 1)
            val c = FloatArray(8) { 9f }
            session.gemm(2, 3, 0, FloatArray(0), 0, 1, FloatArray(0), 0, 3, false, c, 0, 4)
            assertContentEquals(floatArrayOf(0f, 0f, 0f, 9f, 0f, 0f, 0f, 9f), c)
        } finally { session.close() }
        session.close()
        assertFailsWith<IllegalStateException> {
            session.gemm(1, 1, 1, floatArrayOf(1f), 0, 1, floatArrayOf(1f), 0, 1, false, FloatArray(1), 0, 1)
        }
    }

    @Test fun tokenAndWeightTileBoundariesAgreeWithReference() {
        val session = MlxMatrixOperations().open()
        val matrix = JevaMatrix(session)
        try {
            val random = Random(73019)
            val input = FloatArray(257 * 17) { random.nextFloat() * 2 - 1 }
            val weight = tensorOf(shapeOf(129, 17), FloatArray(129 * 17) { random.nextFloat() * 2 - 1 })
            val bias = tensorOf(shapeOf(129), FloatArray(129) { random.nextFloat() - .5f })
            val expected = JevaMatrix().linear(weight, input, 257, bias)
            val actual = matrix.linear(weight, input, 257, bias)
            assertEquals(expected.size, actual.size)
            for (i in expected.indices) assertTrue(abs(expected[i] - actual[i]) <= 2e-5f + 2e-5f * abs(expected[i]),
                "GEMM tile boundary cell $i: ${expected[i]} vs ${actual[i]}")
        } finally { matrix.close() }
    }
}
