package borg.trikeshed.splat

import borg.trikeshed.lib.j
import borg.trikeshed.lib.shapeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PowerIterationEigenFinderTest {

    @Test
    fun testExtractSignatureEmpty() {
        val finder = PowerIterationEigenFinder<String>()
        val emptySplat: Splat<String> = 0 j { _ : Int -> error("empty") }

        val tensor = finder.extractSignature(emptySplat)

        // Tensor shape should be [1] indicating 1D array of length 1 (just eigenvalue 0.0)
        assertEquals(1, tensor.a.a) // shape size
        assertEquals(1, tensor.a.b(0)) // extent of first dimension is 1
        assertEquals(0.0, tensor.b(shapeOf(0)))
    }

    @Test
    fun testExtractSignatureSingleElement() {
        val finder = PowerIterationEigenFinder<String>()
        // A splat with one element having prob 0.5
        val splat: Splat<String> = 1 j { _ : Int -> "A" j 0.5 }

        val tensor = finder.extractSignature(splat)

        // Tensor shape should be [2]
        assertEquals(1, tensor.a.a) // 1D tensor shape (Series of Int)
        assertEquals(2, tensor.a.b(0)) // dimension extent is 2

        val eigenvalue = tensor.b(shapeOf(0))
        val eigenvectorComponent = tensor.b(shapeOf(1))

        // In 1D, Rayleigh quotient is essentially the probability itself (0.5), and eigenvector is normalized to 1.0.
        // v = [1.0], vNext = [0.5], dot(v, vNext) = 0.5.
        assertTrue(kotlin.math.abs(eigenvalue - 0.5) < 1e-5)
        assertTrue(kotlin.math.abs(eigenvectorComponent - 1.0) < 1e-5)
    }

    @Test
    fun testExtractSignatureMultipleElements() {
        val finder = PowerIterationEigenFinder<String>(iterations = 50)
        // A splat with two elements having probs 0.8 and 0.2
        val splat: Splat<String> = 2 j { i : Int ->
            if (i == 0) "A" j 0.8 else "B" j 0.2
        }

        val tensor = finder.extractSignature(splat)

        // Shape is [3] (size n=2, +1 for eigenvalue)
        assertEquals(1, tensor.a.a)
        assertEquals(3, tensor.a.b(0))

        val eigenvalue = tensor.b(shapeOf(0))
        val v0 = tensor.b(shapeOf(1))
        val v1 = tensor.b(shapeOf(2))

        // Since the matrix is diagonal [[0.8, 0], [0, 0.2]], the principal eigenvalue is 0.8
        // and the corresponding eigenvector should be [1.0, 0.0]
        assertTrue(kotlin.math.abs(eigenvalue - 0.8) < 1e-3)
        assertTrue(kotlin.math.abs(v0 - 1.0) < 1e-3)
        assertTrue(kotlin.math.abs(v1 - 0.0) < 1e-3)
    }
}
