package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.common.Files
import borg.trikeshed.lib.*
import kotlin.test.*

class MlxQuantizedAdmissionTest {
    @Test fun tensorCapturesShapeBeforeValidatingAndUploading() {
        val session = MlxMatrixOperations().open()
        try {
            var reads = 0
            val shape: Tensor = 1 j { if (reads++ == 0) 1 else 2 }
            val tensor = session.tensor(_a[42f], shape)
            assertEquals(1, reads)
            assertEquals(1, tensor.a[0])
            assertContentEquals(_a[42f], FloatArray(1).also { tensor.read(0, it) })
        } finally { session.close() }
    }

    @Test fun closedSessionRefusesNewAllocations() {
        val session = MlxMatrixOperations().open()
        session.close()
        assertFailsWith<IllegalStateException> { session.tensor(_a[1f], shapeOf(1)) }
        assertFailsWith<IllegalStateException> { session.zeros(1) }
        assertFailsWith<IllegalStateException> { session.mark() }
        assertFailsWith<IllegalStateException> { session.settle(0) }
        var entered = false
        assertFailsWith<IllegalStateException> { session.scoped { entered = true } }
        assertFalse(entered)
        session.close()
    }

    @Test fun affineMetadataMustBeIntegralAndSupported() {
        val defaults = _m["group_size" j 32, "bits" j 4]
        val (group, bits) = mlxQuantization(defaults, null)
        assertEquals(32, group)
        assertEquals(4, bits)
        assertEquals(8, mlxQuantization(defaults, _m["bits" j 8]).b)
        assertFailsWith<IllegalArgumentException> { mlxQuantization(defaults, _m["bits" j 4.5]) }
        assertFailsWith<IllegalArgumentException> { mlxQuantization(defaults, _m["group_size" j 17]) }
        assertFailsWith<IllegalArgumentException> { mlxQuantization(defaults, _m["mode" j "mxfp4"]) }
    }

    @Test fun invalidPackedShapesAndTokenIdsAreRejectedBeforeDeviceCalls() {
        val header = """{"w":{"dtype":"U32","shape":[2,4],"data_offsets":[0,32]},"s":{"dtype":"F32","shape":[2,1],"data_offsets":[32,40]},"b":{"dtype":"F32","shape":[2,1],"data_offsets":[40,48]}}""".encodeToByteArray()
        val bytes = ByteArray(8 + header.size + 48)
        for (i in 0 until 8) bytes[i] = (header.size.toLong() ushr (i * 8)).toByte()
        header.copyInto(bytes, 8)
        val directory = Files.createTempDir("mlx-quantized-admission-")
        val path = "$directory/weights.safetensors"
        val session = MlxMatrixOperations().open()
        try {
            Files.write(path, bytes)
            val (weight, scales, biases) = session.load(path, "w", "s", "b")
            val input = session.zeros(1, 32)
            val result = session.quantizedLinear(input, weight, scales, biases, 32, 4)
            val values = FloatArray(result.size)
            result.read(0, values)
            assertContentEquals(_a[0f, 0f], values)
            assertFailsWith<IllegalArgumentException> { session.quantizedLinear(input, weight, scales, biases, 31, 4) }
            assertFailsWith<IllegalArgumentException> { session.quantizedLinear(input, weight, scales, biases, 32, 7) }
            assertFailsWith<IllegalArgumentException> { session.quantizedLinear(session.zeros(1, 31), weight, scales, biases, 32, 4) }
            assertFailsWith<IllegalArgumentException> { session.quantizedLinear(input, weight, session.zeros(2, 2), biases, 32, 4) }
            assertFailsWith<IllegalArgumentException> { session.quantizedLinear(input, weight, session.zeros(2, 2), session.zeros(2, 2), 32, 4) }
            assertFailsWith<IllegalArgumentException> { session.quantizedLinear(input, session.zeros(2, 4), scales, biases, 32, 4) }
            assertFailsWith<IllegalArgumentException> { session.embed(_a[-1], weight, scales, biases, 32, 4) }
            assertFailsWith<IllegalArgumentException> { session.embed(_a[2], weight, scales, biases, 32, 4) }
        } finally {
            session.close()
            Files.deleteRecursively(directory)
        }
    }
}
