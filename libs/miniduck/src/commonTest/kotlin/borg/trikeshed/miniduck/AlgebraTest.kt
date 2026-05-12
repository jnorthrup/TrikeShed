package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import kotlin.test.*

class AlgebraTest {

    @Test
    fun shapeOfBuildsCorrectly() {
        val s = shapeOf(3, 4, 5)
        assertEquals(3, s.size)
        assertEquals(3, s[0])
        assertEquals(4, s[1])
        assertEquals(5, s[2])
    }

    @Test
    fun scalarTensorRankZero() {
        val t = scalarTensor(42)
        assertEquals(0, t.a.size)   // shape is empty
        assertEquals(42, t.b(shapeOf()))
    }

    @Test
    fun coTensorContractYieldsInnerProduct() {
        // Build a rank-1 tensor: values [1, 2, 3]
        val shape = shapeOf(3)
        val tensor: Tensor<Int> = shape j { idx: Shape -> idx[0] + 1 }

        // Build a covector: sum all elements
        val coTensor: CoTensor<Int> = tensor co { t ->
            (0 until t.a[0]).sumOf { i -> t.b(shapeOf(i)) }
        }

        // contract: apply the functional to the co-located tensor
        // sum of [1,2,3] = 6... but our functional sums indices 0,1,2 via t.b(shapeOf(i)) = i+1
        // 1 + 2 + 3 = 6
        assertEquals(6, coTensor.contract())
    }

    @Test
    fun coTensorIsRieszPair() {
        // The covector's argument type equals its co-located tensor type — Riesz property
        val t = scalarTensor("hello")
        val co: CoTensor<CharSequence> = t co { it.b(shapeOf()) }
        assertEquals("hello", co.contract())
    }

    @Test
    fun coInfixBuildsCoTensor() {
        val t = scalarTensor(99)
        val co = t co { it.b(shapeOf()) }
        assertEquals(99, co.contract())
    }
}
