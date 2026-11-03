package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.platform.spi.MatrixSession
import kotlin.test.*

class JevaInferenceOwnershipTest {
    class Matrix : MatrixSession {
        override val identity = "fixture"
        var closes = 0
        var fail = false
        override fun gemm(m: Int, n: Int, k: Int, a: FloatArray, aOffset: Int, lda: Int,
                          b: FloatArray, bOffset: Int, ldb: Int, transposeB: Boolean,
                          c: FloatArray, cOffset: Int, ldc: Int) = error("not used")
        override fun close() { closes++; if (fail) error("matrix close") }
    }

    class Tensors : JevaTensors {
        var closes = 0
        var fail = false
        override val mappedBytes = 0L
        override fun get(name: String): MetaSeries<Tensor, Float> =
            if (name.endsWith(".weight")) tensorOf(shapeOf(1, 1), _a[0f]) else tensorOf(shapeOf(), _a[0f])
        override fun close() { closes++; if (fail) error("head close") }
    }

    class Encoder(override val matrix: Matrix) : EncoderSession {
        var closes = 0
        override val hiddenSize = 1
        override val identity = "fixture"
        override fun encode(tokens: Series<Int>, check: () -> Unit, progress: (Int, Int) -> Unit): MetaSeries<Tensor, Float> = error("not used")
        override fun close() { closes++; matrix.close() }
    }

    fun inference(weights: JevaTensors, matrix: Matrix, encoder: Encoder): JevaInference {
        val decision = JevaDecision(JevaDecisionConfig(projectionDim = 1, numHeads = 1, ffnRatio = 0.0),
            weights::get, JevaMatrix(matrix))
        val model = JevaModel(1, JevaPooling.LAST, decision, weights::get)
        return JevaInference(weights, JevaTokenizer(emptyMap(), emptySeriesOf()), encoder, model, JevaConfig(), "fixture")
    }

    @Test fun deviceEncoderClosesItsSharedMatrixOnce() {
        val matrix = Matrix()
        val encoder = Encoder(matrix)
        val head = Tensors()
        inference(JevaInference.DeviceTensors(head, encoder), matrix, encoder).close()
        assertEquals(1, head.closes)
        assertEquals(1, encoder.closes)
        assertEquals(1, matrix.closes)
    }

    @Test fun cpuWeightsAndMatrixCloseIndependentlyEvenWhenMatrixFails() {
        val matrix = Matrix().also { it.fail = true }
        val head = Tensors()
        val failure = assertFailsWith<IllegalStateException> { inference(head, matrix, Encoder(matrix)).close() }
        assertEquals("matrix close", failure.message)
        assertEquals(1, matrix.closes)
        assertEquals(1, head.closes)
    }

    @Test fun deviceEncoderStillClosesWhenHeadFails() {
        val matrix = Matrix()
        val encoder = Encoder(matrix)
        val head = Tensors().also { it.fail = true }
        val failure = assertFailsWith<IllegalStateException> {
            inference(JevaInference.DeviceTensors(head, encoder), matrix, encoder).close()
        }
        assertEquals("head close", failure.message)
        assertEquals(1, head.closes)
        assertEquals(1, encoder.closes)
        assertEquals(1, matrix.closes)
    }

    @Test fun deviceCleanupRetainsBothFailures() {
        val matrix = Matrix().also { it.fail = true }
        val encoder = Encoder(matrix)
        val head = Tensors().also { it.fail = true }
        val failure = assertFailsWith<IllegalStateException> {
            inference(JevaInference.DeviceTensors(head, encoder), matrix, encoder).close()
        }
        assertEquals("head close", failure.message)
        assertEquals("matrix close", failure.suppressed.single().message)
        assertEquals(1, head.closes)
        assertEquals(1, encoder.closes)
        assertEquals(1, matrix.closes)
    }
}
