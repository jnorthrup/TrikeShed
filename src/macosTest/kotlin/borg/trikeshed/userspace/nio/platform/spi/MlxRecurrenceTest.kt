package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.common.Files
import borg.trikeshed.lib.*
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.*

class MlxRecurrenceTest {
    @Test fun completedChunksRetainOneOutputAndReleaseTheirPreviousState() {
        // Two Q4 matrices with 32 input features. Their unpacked weights are all one.
        val header = """{"aw":{"dtype":"U32","shape":[34,4],"data_offsets":[0,544]},"as":{"dtype":"F32","shape":[34,1],"data_offsets":[544,680]},"bw":{"dtype":"U32","shape":[32,4],"data_offsets":[680,1192]},"bs":{"dtype":"F32","shape":[32,1],"data_offsets":[1192,1320]}}""".encodeToByteArray()
        val bytes = ByteArray(8 + header.size + 1320)
        for (i in 0 until 8) bytes[i] = (header.size.toLong() ushr (i * 8)).toByte()
        header.copyInto(bytes, 8)
        bytes.fill(0x11, 8 + header.size + 680, 8 + header.size + 1192)
        val directory = Files.createTempDir("mlx-recurrence-")
        val path = "$directory/weights.safetensors"
        val session = MlxMatrixOperations().open()
        try {
            Files.write(path, bytes)
            val (aw, ascales, bw, bscales) = session.load(path, "aw", "as", "bw", "bs")
            val qkv = MlxQuantized(aw, ascales, session.tensor(FloatArray(34) { 1f }, shapeOf(34, 1)), 32, 4)
            val projection = MlxQuantized(bw, session.tensor(FloatArray(32) { 1f }, shapeOf(32, 1)), bscales, 32, 4)
            val counts = ArrayList<Int>()
            val result = session.linearAttention(
                session.tensor(FloatArray(19 * 32) { 1f }, shapeOf(19, 32)),
                qkv, projection, session.zeros(1, 32), session.zeros(1, 32), projection,
                session.tensor(FloatArray(34 * 4) { if (it % 4 == 3) 1f else 0f }, shapeOf(34, 4, 1)),
                session.zeros(1), session.zeros(1), session.tensor(FloatArray(32) { 1f }, shapeOf(32)),
                1, 1, 1, 32, 4, 1f,
            ) { counts += session.mark() }
            assertEquals(3, counts.size)
            // Full and partial chunks each retain one evaluated output and no obsolete state.
            assertEquals(1, counts[1] - counts[0])
            assertEquals(1, counts[2] - counts[1])
            val actual = FloatArray(result.size)
            result.read(0, actual, 0, actual.size) {}
            // Scalar oracle: q/k/v and gate are constant across rows, but the gated state evolves.
            val activation = 32.0 / (1.0 + exp(-32.0))
            val key = activation / sqrt(activation * activation + 1e-6)
            var state = 0.0
            for (row in 0 until 19) {
                val retained = state * 0.5
                state = retained + (activation - retained * key) * 0.5 * key
                val value = state * key
                val expected = (32.0 * activation * value / sqrt(value * value + 1.0)).toFloat()
                for (column in 0 until 32) assertEquals(expected, actual[row * 32 + column], 0.02f, "row $row column $column")
            }
        } finally { session.close(); Files.deleteRecursively(directory) }
    }
}
