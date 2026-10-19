package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.collections.associative.toAny
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.userspace.nio.platform.spi.JvmMatrixOperations
import kotlin.math.abs
import kotlin.test.*

class JevaBackboneTest {
    /** Independent CPU outputs from the installed Transformers 5.17 Qwen3_5TextModel, not a second Kotlin implementation. */
    @Test fun transformersHybridDecoderParity() = parity(JevaMatrix.reference)

    @Test fun acceleratedHybridDecoderParityAcrossProjectionAndAttentionTiles() {
        if (!java.lang.System.getProperty("os.name").orEmpty().lowercase().contains("mac")) return
        val provider = assertNotNull(JvmMatrixOperations.available(), "macOS qualification requires Accelerate")
        // Deliberately uneven projection tiles exercise partial rows/columns as well as the 64-query attention boundary.
        val matrix = JevaMatrix(provider.open(), tileRows = 3, batchRows = 5)
        try { parity(matrix) } finally { matrix.close() }
    }

    fun parity(matrix: JevaMatrix) {
        // The fixture is read where it lies: Confix indexes the CBOR bytes, and each tensor's values are its array's cells.
        val doc = confixDoc(requireNotNull(javaClass.getResourceAsStream("/jeva/qwen3_5-float32.cbor"))
            .use { it.readBytes() }, Syntax.CBOR)
        fun cell(vararg path: Any) = requireNotNull(doc.docAt(*path)) { "fixture has no ${path.toList()}" }
        val config = JevaBackboneConfig.parse(jsonOf(CanonicalCbor.item(cell("config")).toAny()))
        val input = cell("tokens").cellKids
        val weights = LinkedHashMap<String, MetaSeries<Tensor, Float>>()
        val saved = cell("weights").cellKids
        for (i in 0 until saved.size / 2) {
            val fields = saved[2 * i + 1]
            val s = requireNotNull(fields["shape"]).cellKids
            val v = requireNotNull(fields["values"]).cellKids
            weights[saved[2 * i].reify().toString()] = tensorOf(shapeOf(*IntArray(s.size) { (s[it].reify() as Number).toInt() }),
                FloatArray(v.size) { (v[it].reify() as Number).toFloat() })
        }
        val requested = mutableSetOf<String>()
        val reads = mutableMapOf<String, Int>()
        val backbone = JevaBackbone(config, { name ->
            requested.add(name)
            val tensor = weights.getValue(name)
            tensor.a j { index: Tensor ->
                reads[name] = (reads[name] ?: 0) + 1
                tensor.b(index)
            }
        }, matrix = matrix)
        for (length in intArrayOf(1, 7, 31, 32, 33, 63, 64, 65, 70, 127, 128, 129)) {
            reads.clear()
            val actual = backbone.encode(length j { (input[it].reify() as Number).toInt() })
            assertEquals(length, actual.a[0])
            assertEquals(8, actual.a[1])
            val rows = cell("expected", length.toString()).cellKids
            var error = 0f
            for (token in 0 until length) {
                val row = rows[token].cellKids
                for (column in 0 until 8) {
                    val value = actual.b(shapeOf(token, column))
                    assertTrue(value.isFinite())
                    error = maxOf(error, abs(value - (row[column].reify() as Number).toFloat()))
                }
            }
            assertTrue(error < 3e-5f, "Float32 Qwen3.5 parity at $length tokens: max absolute error $error")
            for ((name, weight) in weights) if (weight.a.size == 2 && !name.endsWith("embed_tokens.weight")) {
                assertEquals(tensorSize(weight.a), reads[name], "$name must be decoded once for all $length tokens")
            }
        }
        assertEquals(weights.keys, requested, "The complete text backbone uses the exact saved tensor names")
    }

    @Test fun checkpointConfigAndUnsupportedArchitecture() {
        val config = JevaBackboneConfig()
        assertEquals(2560, config.hiddenSize)
        assertEquals(64, config.rotaryDim)
        assertEquals(24, config.layers.view.count { it == "linear_attention" })
        assertEquals(8, config.layers.view.count { it == "full_attention" })
        assertFailsWith<IllegalArgumentException> { JevaBackboneConfig(attentionHeads = 3, keyValueHeads = 2) }
        assertFailsWith<IllegalArgumentException> { JevaBackboneConfig(linearValueHeads = 3, linearKeyHeads = 2) }
        assertFailsWith<IllegalArgumentException> { JevaBackboneConfig(layers = s_["sliding_attention"]) }
        assertFailsWith<IllegalArgumentException> { JevaBackboneConfig.parse("{\"model_type\":\"other\"}") }
        val malformed = JevaBackbone(config, { tensorOf(shapeOf(1), floatArrayOf(0f)) })
        assertFailsWith<IllegalArgumentException> { malformed.encode(s_[1]) }
        assertFailsWith<IllegalArgumentException> { malformed.encode(emptySeriesOf()) }
    }
}
