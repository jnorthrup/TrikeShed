package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

class MlxBackboneTest {
    /**
     * Opt-in: all 32 layers of the real q4g32 checkpoint on 12 token ids, Float32, against mlx_lm's own Qwen3.5 module
     * (`load_model` + `set_dtype(float32)`, Python MLX 0.32.0): the last token's final-normalized hidden state. The same
     * module in its shipped BFloat16 differs from that reference by cosine 0.99994 and max abs 0.13 on the last token.
     *
     *   source config/jeva-mlx.env.sh && TRIKESHED_TEST_JEVA_MLX=1 ./gradlew --offline macosTest --tests '*MlxBackboneTest'
     *
     * Without the flag it is skipped, which is not a pass.
     */
    @Test fun thirtyTwoLayersAgreeWithPythonMlxFloat32() {
        val model = getenv("TRIKESHED_JEVA_MODEL")?.toKString()
        if (getenv("TRIKESHED_TEST_JEVA_MLX")?.toKString() != "1" || model == null) {
            println("SKIPPED thirtyTwoLayersAgreeWithPythonMlxFloat32: set TRIKESHED_TEST_JEVA_MLX=1 after sourcing config/jeva-mlx.env.sh")
            return
        }
        val session = MlxMatrixOperations().open()
        try {
            val backbone = MlxBackbone(session, "$model/backbone_mlx")
            assertEquals(32, backbone.config.layers.size)
            val tokens = intArrayOf(9707, 11, 1879, 0, 1, 2, 3, 4, 5, 6, 7, 8)
            val hidden = backbone.encode(tokens.toSeries())
            assertEquals(12, hidden.a[0]); assertEquals(2560, hidden.a[1])
            val last = FloatArray(2560) { hidden.b(shapeOf(11, it)) }
            val sum = last.sumOf { it.toDouble() }
            val magnitude = last.sumOf { abs(it.toDouble()) }
            val everything = FloatArray(hidden.a[0] * hidden.a[1]) { hidden.b(shapeOf(it / 2560, it % 2560)) }
            println("MLX-KOTLIN last head=${last.take(3)} tail=${last.takeLast(3)} sum=$sum abs=$magnitude " +
                "all sum=${everything.sumOf { it.toDouble() }} abs=${everything.sumOf { abs(it.toDouble()) }}")
            val head = doubleArrayOf(29.04320526123047, 6.330820560455322, -1.768845558166504)
            val tail = doubleArrayOf(0.20152923464775085, -1.8034722805023193, 1.6098819971084595)
            for (i in 0 until 3) {
                assertTrue(abs(last[i] - head[i]) <= 1e-2 * maxOf(1.0, abs(head[i])), "cell $i: ${last[i]} vs ${head[i]}")
                assertTrue(abs(last[2557 + i] - tail[i]) <= 1e-2 * maxOf(1.0, abs(tail[i])), "tail $i: ${last[2557 + i]} vs ${tail[i]}")
            }
            assertTrue(abs(sum - -92.21426361976773) <= 5e-3 * 92.2, "sum $sum")
            assertTrue(abs(magnitude - 3236.5187145146483) <= 5e-3 * 3236.5, "abs sum $magnitude")
            assertTrue(abs(everything.sumOf { abs(it.toDouble()) } - 34674.43544624316) <= 5e-3 * 34674.4, "all-token abs sum")
        } finally { session.close() }
    }
}
