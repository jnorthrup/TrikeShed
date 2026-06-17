package borg.trikeshed.cutedsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD Red: CursorTensor Integration
 *
 * Tests for adapting TrikeShed's Cursor/Series algebra to CuTe tensors.
 * This is the key integration point connecting the existing cursor core
 * to the new kernel DSL.
 */
class CursorTensorIntegrationRedTest {

    @Test
    fun `CursorTensor from Cursor with columnar layout`() {
        // Given a cursor with columns [batch, seq, feature]
        val cursor = mockCursor(
            columns = listOf("batch", "seq", "feature"),
            shape = intArrayOf(32, 128, 768) // [batch, seq, hidden]
        )

        // Convert to CuTe tensor with layout optimized for GEMM
        val tensor = CursorTensor.fromCursor(cursor, GemmLayout.ROW_MAJOR)

        assertNotNull(tensor)
        assertEquals(intArrayOf(32, 128, 768), tensor.shape)
        assertEquals(GemmLayout.ROW_MAJOR, tensor.layout)
    }

    @Test
    fun `CursorTensor layout optimization for attention`() {
        val cursor = mockCursor(
            columns = listOf("batch", "head", "seq", "dim"),
            shape = intArrayOf(4, 32, 128, 64) // [batch, heads, seq, head_dim]
        )

        // Attention needs [batch*heads, seq, dim] for batched GEMM
        val tensor = CursorTensor.fromCursor(
            cursor,
            AttentionLayout.BATCHED_SEQ_DIM
        )

        assertEquals(intArrayOf(4 * 32, 128, 64), tensor.shape) // [128, 128, 64]
        assertEquals(AttentionLayout.BATCHED_SEQ_DIM, tensor.layout)
    }

    @Test
    fun `CursorTensor to CuTe Tile for kernel launch`() {
        val cursor = mockCursor(
            columns = listOf("m", "k"),
            shape = intArrayOf(64, 32)
        )

        val tensor = CursorTensor.fromCursor(cursor, GemmLayout.ROW_MAJOR)
        val tile = tensor.asTile()

        assertNotNull(tile)
        assertEquals(intArrayOf(64, 32), tile.layout.shape)
        assertEquals(64 * 32, tile.data.size)
    }

    @Test
    fun `CursorTensor round-trip: cursor -> tensor -> cursor`() {
        val original = mockCursor(
            columns = listOf("x", "y"),
            shape = intArrayOf(16, 16)
        )

        val tensor = CursorTensor.fromCursor(original, GemmLayout.ROW_MAJOR)
        val restored = tensor.toCursor()

        assertEquals(original.shape, restored.shape)
        assertEquals(original.columns, restored.columns)
        // Data should be preserved
        for (i in 0 until original.size) {
            assertEquals(original.get(i), restored.get(i), 1e-6)
        }
    }

    @Test
    fun `CursorTensor slice for block/warp partitioning`() {
        val cursor = mockCursor(
            columns = listOf("m", "n"),
            shape = intArrayOf(128, 128)
        )

        val tensor = CursorTensor.fromCursor(cursor, GemmLayout.ROW_MAJOR)

        // Slice for warp-level tile (64x64)
        val warpTile = tensor.slice(0, 0, 64, 64)
        assertEquals(intArrayOf(64, 64), warpTile.shape)

        // Slice for MMA tile (16x16)
        val mmaTile = warpTile.slice(0, 0, 16, 16)
        assertEquals(intArrayOf(16, 16), mmaTile.shape)
    }

    @Test
    fun `CursorTensor broadcast for bias/activation`() {
        val cursor = mockCursor(
            columns = listOf("batch", "feature"),
            shape = intArrayOf(32, 512)
        )

        val biasCursor = mockCursor(
            columns = listOf("feature"),
            shape = intArrayOf(512)
        )

        val tensor = CursorTensor.fromCursor(cursor, GemmLayout.ROW_MAJOR)
        val biasTensor = CursorTensor.fromCursor(biasCursor, GemmLayout.ROW_MAJOR)

        // Broadcast bias across batch dimension
        val result = tensor.broadcastAdd(biasTensor, broadcastDim = 0)

        assertEquals(intArrayOf(32, 512), result.shape)
    }

    @Test
    fun `CursorTensor reduction for loss/softmax`() {
        val cursor = mockCursor(
            columns = listOf("batch", "seq", "vocab"),
            shape = intArrayOf(4, 128, 32000)
        )

        val tensor = CursorTensor.fromCursor(cursor, GemmLayout.ROW_MAJOR)

        // Reduce over vocab for log-sum-exp (softmax denominator)
        val reduced = tensor.reduce(ReduceOp.LOG_SUM_EXP, dim = 2)

        assertEquals(intArrayOf(4, 128), reduced.shape)
    }

    @Test
    fun `CursorTensor with provenance tracking`() {
        val cursor = mockCursor(
            columns = listOf("m", "k"),
            shape = intArrayOf(64, 32)
        )

        val tensor = CursorTensor.fromCursor(
            cursor,
            GemmLayout.ROW_MAJOR,
            provenance = ProvenanceInfo(
                source = "pandas_import",
                transform = "cursor_to_tensor",
                timestamp = System.currentTimeMillis()
            )
        )

        assertNotNull(tensor.provenance)
        assertEquals("pandas_import", tensor.provenance.source)
        assertEquals("cursor_to_tensor", tensor.provenance.transform)

        // Provenance should propagate through operations
        val gemmResult = tensor.gemm(mockCursor(
            columns = listOf("k", "n"),
            shape = intArrayOf(32, 64)
        ).toTensor())

        assertNotNull(gemmResult.provenance)
        assertTrue(gemmResult.provenance.history.contains("gemm"))
    }

    @Test
    fun `CursorTensor algebraic composition with Join/Series`() {
        // CursorTensor should compose with TrikeShed's Join/Series algebra
        val cursorA = mockCursor(columns = listOf("m", "k"), shape = intArrayOf(32, 32))
        val cursorB = mockCursor(columns = listOf("k", "n"), shape = intArrayOf(32, 32))

        val tensorA = CursorTensor.fromCursor(cursorA, GemmLayout.ROW_MAJOR)
        val tensorB = CursorTensor.fromCursor(cursorB, GemmLayout.ROW_MAJOR)

        // Join: combine along new dimension
        val joined = tensorA.join(tensorB, dim = 0)
        assertEquals(intArrayOf(2, 32, 32), joined.shape)

        // Series: lazy map over tensor elements
        val scaled = tensorA.map { it * 2.0 }
        assertEquals(2.0, scaled.get(0, 0), 1e-6)
    }

    @Test
    fun `CursorTensor blackboard observation emission`() {
        // Integration with blackboard for experiment tracking
        val cursor = mockCursor(columns = listOf("m", "n"), shape = intArrayOf(64, 64))
        val tensor = CursorTensor.fromCursor(cursor, GemmLayout.ROW_MAJOR)

        // Emit blackboard observation with tensor metadata
        val observation = tensor.toBlackboardEntry(
            role = "kernel_input",
            metadata = mapOf("kernel" to "gemm", "precision" to "fp16")
        )

        assertNotNull(observation)
        assertEquals("kernel_input", observation.role)
        assertEquals("gemm", observation.metadata["kernel"])
    }
}

/**
 * Placeholder types and mock for RED phase
 */
enum class GemmLayout { ROW_MAJOR, COL_MAJOR }
enum class AttentionLayout { BATCHED_SEQ_DIM, HEADS_FIRST }
enum class ReduceOp { SUM, MEAN, MAX, MIN, LOG_SUM_EXP }

data class ProvenanceInfo(
    val source: String,
    val transform: String,
    val timestamp: Long,
    val history: MutableList<String> = mutableListOf()
)

data class CursorTensor(
    val shape: IntArray,
    val layout: Any, // GemmLayout | AttentionLayout
    val data: DoubleArray,
    val provenance: ProvenanceInfo? = null,
    val columns: List<String> = emptyList()
) {
    val size: Int get() = shape.reduce { a, b -> a * b }

    fun asTile(): Tile<Double> {
        val layoutObj = Layout.fromCursorShape(shape)
        return Tile(layoutObj, data)
    }

    fun toCursor(): MockCursor = MockCursor(columns, shape, data)

    fun slice(rowStart: Int, colStart: Int, rows: Int, cols: Int): CursorTensor {
        throw UnsupportedOperationException("slice not implemented")
    }

    fun broadcastAdd(other: CursorTensor, broadcastDim: Int): CursorTensor {
        throw UnsupportedOperationException("broadcastAdd not implemented")
    }

    fun reduce(op: ReduceOp, dim: Int): CursorTensor {
        throw UnsupportedOperationException("reduce not implemented")
    }

    fun gemm(other: CursorTensor): CursorTensor {
        throw UnsupportedOperationException("gemm not implemented")
    }

    fun join(other: CursorTensor, dim: Int): CursorTensor {
        throw UnsupportedOperationException("join not implemented")
    }

    fun map(transform: (Double) -> Double): CursorTensor {
        throw UnsupportedOperationException("map not implemented")
    }

    fun get(row: Int, col: Int): Double = data[row * shape[1] + col]

    fun toBlackboardEntry(role: String, metadata: Map<String, String>): BlackboardEntry {
        throw UnsupportedOperationException("toBlackboardEntry not implemented")
    }

    companion object {
        fun fromCursor(
            cursor: MockCursor,
            layout: Any,
            provenance: ProvenanceInfo? = null
        ): CursorTensor {
            return CursorTensor(
                shape = cursor.shape,
                layout = layout,
                data = cursor.data,
                provenance = provenance,
                columns = cursor.columns
            )
        }
    }
}

data class BlackboardEntry(
    val role: String,
    val metadata: Map<String, String>,
    val tensorShape: IntArray,
    val tensorLayout: Any
)

data class MockCursor(
    val columns: List<String>,
    val shape: IntArray,
    val data: DoubleArray
) {
    val size: Int get() = shape.reduce { a, b -> a * b }
    fun get(i: Int): Double = data[i]
}

fun mockCursor(columns: List<String>, shape: IntArray): MockCursor {
    val size = shape.reduce { a, b -> a * b }
    return MockCursor(columns, shape, DoubleArray(size) { Math.random() })
}