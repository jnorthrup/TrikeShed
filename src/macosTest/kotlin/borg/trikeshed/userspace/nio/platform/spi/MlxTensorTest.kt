package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

class MlxTensorTest {
    @Test fun anMlxArrayIsAMetaSeriesOfFloatCells() {
        val session = MlxMatrixOperations().open()
        val tensor = session.tensor(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), shapeOf(2, 3))
        assertEquals(2, tensor.a.size); assertEquals(2, tensor.a[0]); assertEquals(3, tensor.a[1])
        assertEquals(6, tensor.size)
        assertEquals(6f, tensor.b(shapeOf(1, 2)))
        assertEquals(2f, tensor.b(shapeOf(0, 1)))
        val dst = FloatArray(5) { -1f }
        tensor.read(2, dst, 1, 3)
        assertContentEquals(floatArrayOf(-1f, 3f, 4f, 5f, -1f), dst)
        assertFailsWith<IllegalArgumentException> { tensor.b(shapeOf(2, 0)) }
        session.close()
        assertFailsWith<IllegalStateException> { tensor.b(shapeOf(0, 0)) }
    }

    /**
     * Opt-in: layer 0 mlp.gate_proj of the real q4g32 checkpoint, input 2 x 2560 of 0.5 sin(0.37 i). Expected values come
     * from Python MLX 0.32.0 `mx.quantized_matmul` on the same file and input.
     *
     *   source config/jeva-mlx.env.sh && TRIKESHED_TEST_JEVA_MLX=1 ./gradlew --offline macosTest --tests '*MlxTensorTest'
     *
     * Without the flag it is skipped, which is not a pass.
     */
    @Test fun quantizedLinearOnTheRealCheckpointAgreesWithPythonMlx() {
        val model = getenv("TRIKESHED_JEVA_MODEL")?.toKString()
        if (getenv("TRIKESHED_TEST_JEVA_MLX")?.toKString() != "1" || model == null) {
            println("SKIPPED quantizedLinearOnTheRealCheckpointAgreesWithPythonMlx: set TRIKESHED_TEST_JEVA_MLX=1 after sourcing config/jeva-mlx.env.sh")
            return
        }
        val session = MlxMatrixOperations().open()
        try {
            val prefix = "language_model.model.layers.0.mlp.gate_proj."
            val (weight, scales, biases) = session.load("$model/backbone_mlx/model.safetensors",
                prefix + "weight", prefix + "scales", prefix + "biases")
            assertEquals(9216, weight.a[0]); assertEquals(320, weight.a[1])
            assertFailsWith<IllegalArgumentException> { weight.b(shapeOf(0, 0)) }
            val input = session.tensor(FloatArray(2 * 2560) { (0.5 * sin(0.37 * it.toDouble())).toFloat() }, shapeOf(2, 2560))
            val output = session.quantizedLinear(input, weight, scales, biases, groupSize = 32, bits = 4)
            assertEquals(2, output.a[0]); assertEquals(9216, output.a[1])
            val head = floatArrayOf(0.22226941585540771f, -0.2462792843580246f, 0.2646065950393677f, -0.19481192529201508f)
            val tail = floatArrayOf(-0.09961696714162827f, -0.13335837423801422f, -0.11415373533964157f, 0.1087045818567276f)
            for (i in 0 until 4) {
                assertTrue(abs(output.b(shapeOf(0, i)) - head[i]) <= 1e-3f, "row 0 cell $i: ${output.b(shapeOf(0, i))} vs ${head[i]}")
                assertTrue(abs(output.b(shapeOf(1, 9212 + i)) - tail[i]) <= 1e-3f, "row 1 cell ${9212 + i}")
            }
            val cells = FloatArray(output.size).also { output.read(0, it) }
            val sum = cells.sumOf { it.toDouble() }
            val magnitude = cells.sumOf { abs(it.toDouble()) }
            assertTrue(abs(sum - 169.40176119864918) <= 1e-3 * 169.4, "sum $sum")
            assertTrue(abs(magnitude - 2369.4049537016544) <= 1e-4 * 2369.4, "abs sum $magnitude")
        } finally { session.close() }
    }

    /**
     * Opt-in, same gate: tokens 1..5 through the quantized embedding (8-bit, group 64), layer 0's post-attention norm
     * (weight as stored) and its SwiGLU block (4-bit, group 32), all Float32. Expected from Python MLX 0.32.0.
     */
    @Test fun embeddingNormAndSwigluBlockAgreeWithPythonMlx() {
        val model = getenv("TRIKESHED_JEVA_MODEL")?.toKString()
        if (getenv("TRIKESHED_TEST_JEVA_MLX")?.toKString() != "1" || model == null) {
            println("SKIPPED embeddingNormAndSwigluBlockAgreeWithPythonMlx: set TRIKESHED_TEST_JEVA_MLX=1 after sourcing config/jeva-mlx.env.sh")
            return
        }
        val session = MlxMatrixOperations().open()
        try {
            val root = "language_model.model."
            val table = root + "embed_tokens."
            val block = root + "layers.0."
            val tensors = session.load("$model/backbone_mlx/model.safetensors",
                table + "weight", table + "scales", table + "biases", block + "post_attention_layernorm.weight",
                block + "mlp.gate_proj.weight", block + "mlp.gate_proj.scales", block + "mlp.gate_proj.biases",
                block + "mlp.up_proj.weight", block + "mlp.up_proj.scales", block + "mlp.up_proj.biases",
                block + "mlp.down_proj.weight", block + "mlp.down_proj.scales", block + "mlp.down_proj.biases")
            fun linear(input: MlxTensor, at: Int) = session.quantizedLinear(input, tensors[at], tensors[at + 1], tensors[at + 2], 32, 4)
            val embedded = session.embed(intArrayOf(1, 2, 3, 4, 5), tensors[0], tensors[1], tensors[2], 64, 8)
            val normal = session.rmsNorm(embedded, tensors[3], 1e-6f)
            val output = linear(session.times(session.silu(linear(normal, 4)), linear(normal, 7)), 10)
            fun agrees(name: String, tensor: MlxTensor, head: DoubleArray, tail: DoubleArray, sum: Double, magnitude: Double) {
                assertEquals(5, tensor.a[0]); assertEquals(2560, tensor.a[1])
                val cells = FloatArray(tensor.size).also { tensor.read(0, it) }
                for (i in 0 until 3) {
                    assertTrue(abs(cells[i] - head[i]) <= 2e-3 * maxOf(1.0, abs(head[i])), "$name cell $i: ${cells[i]} vs ${head[i]}")
                    assertTrue(abs(cells[cells.size - 3 + i] - tail[i]) <= 2e-3 * maxOf(1.0, abs(tail[i])), "$name tail $i")
                }
                assertTrue(abs(cells.sumOf { it.toDouble() } - sum) <= 1e-3 * maxOf(1.0, abs(sum)), "$name sum")
                assertTrue(abs(cells.sumOf { abs(it.toDouble()) } - magnitude) <= 1e-3 * magnitude, "$name abs sum")
            }
            agrees("embed", embedded, doubleArrayOf(0.0181884765625, -0.0166015625, -0.024169921875),
                doubleArrayOf(0.004974365234375, -0.0052490234375, -0.0027618408203125), 4.041274070739746, 144.89657878875732)
            agrees("norm", normal, doubleArrayOf(0.004517956171184778, -1.0556856393814087, -1.3808571100234985),
                doubleArrayOf(0.3258562982082367, -0.3550303876399994, -0.18239109218120575), 251.86982018250274, 9689.879767740204)
            agrees("mlp", output, doubleArrayOf(-2.5590648651123047, -0.12276513874530792, 0.31545954942703247),
                doubleArrayOf(-0.20452752709388733, -0.06383059918880463, -0.02027229592204094), -19.69680585875176, 948.4428920971695)
        } finally { session.close() }
    }

    /**
     * Opt-in, same gate: layer 3 (full attention) on the normed embeddings of tokens 1..5, Float32, expected from
     * Python MLX 0.32.0 `mx.fast.rope` + `mx.fast.scaled_dot_product_attention(mask="causal")` on the same weights.
     */
    @Test fun fullAttentionLayerAgreesWithPythonMlx() {
        val model = getenv("TRIKESHED_JEVA_MODEL")?.toKString()
        if (getenv("TRIKESHED_TEST_JEVA_MLX")?.toKString() != "1" || model == null) {
            println("SKIPPED fullAttentionLayerAgreesWithPythonMlx: set TRIKESHED_TEST_JEVA_MLX=1 after sourcing config/jeva-mlx.env.sh")
            return
        }
        val session = MlxMatrixOperations().open()
        try {
            val root = "language_model.model."
            val table = root + "embed_tokens."
            val layer = root + "layers.3."
            val names = mutableListOf(table + "weight", table + "scales", table + "biases", layer + "input_layernorm.weight",
                layer + "self_attn.q_norm.weight", layer + "self_attn.k_norm.weight")
            val projections = listOf("q_proj", "k_proj", "v_proj", "o_proj")
            for (name in projections) for (part in listOf("weight", "scales", "biases")) names += "${layer}self_attn.$name.$part"
            val t = session.load("$model/backbone_mlx/model.safetensors", *names.toTypedArray())
            fun matrix(index: Int) = MlxQuantized(t[6 + 3 * index], t[7 + 3 * index], t[8 + 3 * index], 32, 4)
            val embedded = session.embed(intArrayOf(1, 2, 3, 4, 5), t[0], t[1], t[2], 64, 8)
            val normal = session.rmsNorm(embedded, t[3], 1e-6f)
            val result = session.fullAttention(normal, matrix(0), matrix(1), matrix(2), matrix(3), t[4], t[5],
                heads = 16, keyValueHeads = 4, headDim = 256, ropeDimensions = 64, ropeBase = 1e7f, epsilon = 1e-6f)
            assertEquals(5, result.a[0]); assertEquals(2560, result.a[1])
            val cells = FloatArray(result.size).also { result.read(0, it) }
            val head = doubleArrayOf(7.921051979064941, -0.3649379312992096, -0.347748339176178)
            val tail = doubleArrayOf(0.01584160327911377, 0.18825814127922058, 0.18576017022132874)
            for (i in 0 until 3) {
                assertTrue(abs(cells[i] - head[i]) <= 2e-3 * maxOf(1.0, abs(head[i])), "cell $i: ${cells[i]} vs ${head[i]}")
                assertTrue(abs(cells[cells.size - 3 + i] - tail[i]) <= 2e-3 * maxOf(1.0, abs(tail[i])), "tail $i")
            }
            val sum = cells.sumOf { it.toDouble() }
            val magnitude = cells.sumOf { abs(it.toDouble()) }
            assertTrue(abs(sum - 94.06570335198194) <= 1e-3 * 94.07, "sum $sum")
            assertTrue(abs(magnitude - 3219.810519394465) <= 1e-3 * 3219.8, "abs sum $magnitude")
        } finally { session.close() }
    }

    /**
     * Opt-in, same gate: layer 0 (linear attention: causal depthwise conv + gated delta recurrence) on the normed
     * embeddings of tokens 1..5, Float32, expected from Python MLX 0.32.0 with mlx_lm's `gated_delta_ops` on the same weights.
     */
    @Test fun linearAttentionLayerAgreesWithPythonMlx() {
        val model = getenv("TRIKESHED_JEVA_MODEL")?.toKString()
        if (getenv("TRIKESHED_TEST_JEVA_MLX")?.toKString() != "1" || model == null) {
            println("SKIPPED linearAttentionLayerAgreesWithPythonMlx: set TRIKESHED_TEST_JEVA_MLX=1 after sourcing config/jeva-mlx.env.sh")
            return
        }
        val session = MlxMatrixOperations().open()
        try {
            val root = "language_model.model."
            val table = root + "embed_tokens."
            val layer = root + "layers.0."
            val mixer = layer + "linear_attn."
            val names = mutableListOf(table + "weight", table + "scales", table + "biases", layer + "input_layernorm.weight",
                mixer + "in_proj_b.weight", mixer + "in_proj_a.weight", mixer + "conv1d.weight", mixer + "A_log",
                mixer + "dt_bias", mixer + "norm.weight")
            for (name in listOf("in_proj_qkv", "in_proj_z", "out_proj")) for (part in listOf("weight", "scales", "biases")) names += "$mixer$name.$part"
            val t = session.load("$model/backbone_mlx/model.safetensors", *names.toTypedArray())
            fun matrix(index: Int) = MlxQuantized(t[10 + 3 * index], t[11 + 3 * index], t[12 + 3 * index], 32, 4)
            val embedded = session.embed(intArrayOf(1, 2, 3, 4, 5), t[0], t[1], t[2], 64, 8)
            val normal = session.rmsNorm(embedded, t[3], 1e-6f)
            val result = session.linearAttention(normal, matrix(0), matrix(1), t[4], t[5], matrix(2), t[6], t[7], t[8], t[9],
                keyHeads = 16, valueHeads = 32, keyDim = 128, valueDim = 128, kernel = 4, epsilon = 1e-6f)
            assertEquals(5, result.a[0]); assertEquals(2560, result.a[1])
            val cells = FloatArray(result.size).also { result.read(0, it) }
            val head = doubleArrayOf(1.3146456480026245, 0.008735382929444313, -0.0007202122360467911)
            val tail = doubleArrayOf(-0.016086772084236145, 0.008306925185024738, -0.002678480464965105)
            for (i in 0 until 3) {
                assertTrue(abs(cells[i] - head[i]) <= 2e-3 * maxOf(1.0, abs(head[i])), "cell $i: ${cells[i]} vs ${head[i]}")
                assertTrue(abs(cells[cells.size - 3 + i] - tail[i]) <= 2e-3 * maxOf(1.0, abs(tail[i])), "tail $i")
            }
            val sum = cells.sumOf { it.toDouble() }
            val magnitude = cells.sumOf { abs(it.toDouble()) }
            assertTrue(abs(sum - 9.10043042752659) <= 1e-3 * 9.1, "sum $sum")
            assertTrue(abs(magnitude - 156.16798048565397) <= 1e-3 * 156.2, "abs sum $magnitude")
        } finally { session.close() }
    }
}
