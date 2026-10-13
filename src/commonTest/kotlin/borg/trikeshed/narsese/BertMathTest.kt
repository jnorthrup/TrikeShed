package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.math.abs
import kotlin.test.*

class BertMathTest {
    private fun close(expected: FloatArray, actual: FloatArray, tolerance: Float = 2e-6f) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertTrue(actual[i].isFinite() && abs(expected[i] - actual[i]) <= tolerance,
            "coordinate $i: expected ${expected[i]}, got ${actual[i]}")
    }

    @Test fun matrixAndTensorBorrowRowMajorStorageAndEnforceBothAxes() {
        val storage = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val matrix = BertMath.matrix(storage, 2, 3)
        val tensor = matrix.tensor
        assertEquals(2, matrix.a.a)
        assertEquals(3, matrix.a.b)
        assertEquals(listOf(2, 3), tensor.a.view.toList())
        assertEquals(6f, matrix[1, 2])
        storage[4] = 99f
        assertEquals(99f, matrix.b[4])
        assertEquals(99f, tensor.b(shapeOf(1, 1)))
        assertFailsWith<IllegalArgumentException> { matrix[-1, 0] }
        assertFailsWith<IllegalArgumentException> { matrix[0, 3] }
        assertFailsWith<IllegalArgumentException> { tensor.b(shapeOf(1)) }
        assertFailsWith<IllegalArgumentException> { tensor.b(shapeOf(2, 0)) }
        assertFailsWith<IllegalArgumentException> { BertMath.matrix(storage, 3, 3) }
    }

    @Test fun linearUsesOutputMajorWeightsWithoutMutatingInput() {
        val input = floatArrayOf(1f, 2f, 3f, -4f, .25f, 2f)
        val output = BertMath.linear(input, 2, 3, floatArrayOf(2f, 0f, -1f, 1f, 2f, 0f), 2,
            floatArrayOf(.5f, -.25f))
        close(floatArrayOf(-.5f, 4.75f, -9.5f, -3.75f), output, 0f)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, -4f, .25f, 2f), input)
    }

    @Test fun linearRetainsColumnsAfterEightWayReduction() {
        // Independent torch.nn.functional.linear CPU F32 fixture; dyadic inputs make it exact.
        val input = FloatArray(22) { (it % 13 - 6) / 8f }
        val weight = FloatArray(33) { (it * 7 % 17 - 8) / 16f }
        val bias = FloatArray(3) { (it - 1) / 32f }
        close(floatArrayOf(.2109375f, -.265625f, .1875f, -.5625f, .5703125f, -.5546875f),
            BertMath.linear(input, 2, 11, weight, 3, bias), 0f)
    }

    @Test fun disjointLinearRowRangesComposeWithoutTouchingOtherRows() {
        val input = FloatArray(44) { (it % 13 - 6) / 8f }
        val weight = FloatArray(33) { (it * 7 % 17 - 8) / 16f }
        val bias = FloatArray(3) { (it - 1) / 32f }
        val whole = BertMath.linear(input, 4, 11, weight, 3, bias)
        val output = FloatArray(12) { Float.NaN }
        BertMath.linear(input, 4, 11, weight, 3, bias, output, 2 j 4)
        assertTrue(output.take(6).all { it.isNaN() }, "Only admitted rows are written")
        for (i in 6 until output.size) assertEquals(whole[i], output[i])
        BertMath.linear(input, 4, 11, weight, 3, bias, output, 0 j 2)
        assertContentEquals(whole, output)
        BertMath.linear(input, 4, 11, weight, 3, bias, output, 4 j 4)
        assertContentEquals(whole, output, "An empty row range has no writes")
    }

    @Test fun linearRowRangeAndOutputShapeFailuresPrecedeWrites() {
        val input = floatArrayOf(1f, 2f, 3f, 4f)
        val weight = floatArrayOf(2f, 3f)
        val bias = floatArrayOf(.5f)
        val output = floatArrayOf(77f, 77f)
        for (range in s_[-1 j 1, 2 j 1, 0 j 3].view) {
            assertFailsWith<IllegalArgumentException> { BertMath.linear(input, 2, 2, weight, 1, bias, output, range) }
            assertContentEquals(floatArrayOf(77f, 77f), output)
        }
        assertFailsWith<IllegalArgumentException> { BertMath.linear(input, 2, 2, weight, 1, bias, FloatArray(1), 0 j 1) }
        assertFailsWith<IllegalArgumentException> { BertMath.linear(input, 2, 2, weight, 1, bias, FloatArray(3), 0 j 1) }
        BertMath.linear(FloatArray(0), 0, 2, weight, 1, bias, FloatArray(0), 0 j 0)
    }

    @Test fun residualLayerNormMatchesIndependentTorchCpuFixture() {
        close(floatArrayOf(-.705384612f, -1.408077002f, 1.004711628f, -1.310828328f, 1.380127668f, .610382259f),
            BertMath.layerNorm(floatArrayOf(1f, 2f, 3f, -4f, .25f, 2f), 2, 3, floatArrayOf(1f, 2f, .5f),
                floatArrayOf(.1f, -.2f, .3f), 1e-5f, floatArrayOf(.25f, -.5f, 1f, 2f, 1f, -1f)))
        close(floatArrayOf(.1f, .2f, .3f), BertMath.layerNorm(floatArrayOf(10f, 10f, 10f), 1, 3,
            floatArrayOf(1f, 2f, 3f), floatArrayOf(.1f, .2f, .3f), 1e-12f), 0f)
    }

    @Test fun erfGeluMatchesTorchExactRatherThanTanhForm() {
        // torch.nn.functional.gelu(..., approximate="none"), CPU F32.
        val input = floatArrayOf(-10f, -6f, -3f, -2f, -1f, -.5f, 0f, .1f, .5f, 1f, 2f, 3f, 6f, 10f)
        close(floatArrayOf(0f, 0f, -.0040498674f, -.0455001f, -.15865526f, -.1542688f, 0f, .05398279f,
            .34573123f, .8413447f, 1.9544997f, 2.9959502f, 6f, 10f), BertMath.gelu(input))
    }

    @Test fun attentionMatchesTorchWithHeadConcatenationAndMaskedKeys() {
        val q = floatArrayOf(1f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, -1f)
        val k = floatArrayOf(1f, 1f, 1f, 0f, -1f, 1f, 0f, 1f, 0f, 1f, 1f, 1f)
        val v = floatArrayOf(1f, 2f, 10f, 20f, 3f, 4f, 30f, 40f, 5f, 6f, 50f, 60f)
        close(floatArrayOf(2.4160402f, 3.4160402f, 34.066727f, 44.066727f, 3f, 4f, 30f, 40f,
            2.4160402f, 3.4160402f, 24.160402f, 34.160404f), BertMath.attention(q, k, v, 3, 4, 2), 2e-5f)
        close(floatArrayOf(2.3209538f, 3.3209538f, 36.790462f, 46.790462f, 3f, 4f, 30f, 40f,
            2.3209538f, 3.3209538f, 23.20954f, 33.209537f),
            BertMath.attention(q, k, v, 3, 4, 2, booleanArrayOf(true, false, true)), 2e-5f)
        // BERT is bidirectional: the first query reads the later value too.
        close(floatArrayOf(2f, 2f), BertMath.attention(FloatArray(2), FloatArray(2), floatArrayOf(1f, 3f), 2, 1, 1), 0f)
    }

    @Test fun softmaxRemainsFiniteForLargeScores() {
        close(floatArrayOf(10f, 10f), BertMath.attention(floatArrayOf(1000f, 1000f), floatArrayOf(1000f, 999f),
            floatArrayOf(10f, 20f), 2, 1, 1), 0f)
    }

    @Test fun shapeFailuresAreRejectedBeforeNumericLoops() {
        assertFailsWith<IllegalArgumentException> { BertMath.linear(FloatArray(2), 1, 3, FloatArray(3), 1, FloatArray(1)) }
        assertFailsWith<IllegalArgumentException> { BertMath.layerNorm(FloatArray(3), 1, 3, FloatArray(3), FloatArray(3), 0f) }
        assertFailsWith<IllegalArgumentException> { BertMath.layerNorm(FloatArray(3), 1, 3, FloatArray(3), FloatArray(3), 1e-5f, FloatArray(2)) }
        assertFailsWith<IllegalArgumentException> { BertMath.attention(FloatArray(4), FloatArray(4), FloatArray(4), 1, 4, 3) }
        assertFailsWith<IllegalArgumentException> { BertMath.attention(FloatArray(2), FloatArray(2), FloatArray(2), 2, 1, 1, booleanArrayOf(false, false)) }
        assertFailsWith<IllegalArgumentException> { BertMath.attention(FloatArray(513), FloatArray(513), FloatArray(513), 513, 1, 1) }
        assertContentEquals(FloatArray(0), BertMath.attention(FloatArray(0), FloatArray(0), FloatArray(0), 0, 4, 2))
    }
}
