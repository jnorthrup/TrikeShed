package borg.trikeshed.cutedsl

import borg.trikeshed.cursor.Cursor

/**
 * CursorTensor: Bridge between TrikeShed's Cursor/Series algebra and CuTe tensors.
 *
 * This is the key integration point that lets existing cursor-based data pipelines
 * feed directly into CuTe kernels, and kernel results flow back into cursors
 * with full provenance tracking.
 */

enum class GemmLayout { ROW_MAJOR, COL_MAJOR }
enum class AttentionLayout { BATCHED_SEQ_DIM, HEADS_FIRST }
enum class ReduceOp { SUM, MEAN, MAX, MIN, LOG_SUM_EXP }

/**
 * Provenance information for reproducible, observable workflows.
 * Tracks the lineage of tensor transformations for experiment tracking.
 */
data class ProvenanceInfo(
    val source: String,           // e.g., "pandas_import", "cursor_query", "kernel_gemm"
    val transform: String,        // e.g., "cursor_to_tensor", "gemm", "attention"
    val timestamp: Long = System.currentTimeMillis(),
    val history: MutableList<String> = mutableListOf(),
    val metadata: MutableMap<String, String> = mutableMapOf()
) {
    fun withTransform(transform: String): ProvenanceInfo {
        val newHistory = history.toMutableList().apply { add(transform) }
        return copy(transform = transform, history = newHistory, timestamp = System.currentTimeMillis())
    }

    fun withMetadata(key: String, value: String): ProvenanceInfo {
        val newMeta = metadata.toMutableMap().apply { put(key, value) }
        return copy(metadata = newMeta)
    }
}

/**
 * CursorTensor wraps a CuTe-compatible tensor with cursor semantics.
 *
 * It provides:
 * - Layout-aware tensor operations (GEMM, attention, reductions)
 * - Cursor algebra composition (Join, Series, map, filter)
 * - Provenance tracking for blackboard emission
 * - Slice/partition for hierarchical parallelism (block/warp/MMA)
 */
class CursorTensor<T>(
    val shape: IntArray,
    val layout: Layout,
    val data: Array<T>,
    val provenance: ProvenanceInfo? = null,
    val columns: List<String> = emptyList(),
    val facets: Map<String, Any> = emptyMap()  // PointcutFacet metadata
) {
    require(data.size >= layout.size) { "Data size ${data.size} < layout size ${layout.size}" }

    val rank: Int get() = layout.rank
    val size: Int get() = layout.size

    /** Element access via logical coordinates */
    operator fun get(vararg coords: Int): T = data[layout[*coords]]

    operator fun set(vararg coords: Int, value: T) {
        data[layout[*coords]] = value
    }

    /** Convert to CuTe Tile for kernel launch */
    fun asTile(): Tile<T> = Tile(layout, data)

    /** Convert back to Cursor for cursor-pipeline integration */
    fun toCursor(): Cursor {
        // Implementation depends on Cursor API
        // For now, return a mock that can be replaced
        return MockCursor(columns, shape, data as Array<Any>)
    }

    /** Slice a sub-tensor (for block/warp/MMA partitioning) */
    fun slice(
        offsets: IntArray,
        extents: IntArray
    ): CursorTensor<T> {
        val subLayout = Layout(extents, layout.stride)
        val baseOffset = offsets.zip(layout.stride).fold(0) { acc, (o, s) -> acc + o * s }

        val sliceSize = extents.fold(1) { a, b -> a * b }
        val sliceData = Array(sliceSize) { data[baseOffset + it] }

        return CursorTensor(
            shape = extents,
            layout = subLayout,
            data = sliceData,
            provenance = provenance?.withTransform("slice"),
            columns = columns,
            facets = facets
        )
    }

    /** 2D slice convenience */
    fun slice(rowStart: Int, colStart: Int, rows: Int, cols: Int): CursorTensor<T> {
        require(rank >= 2) { "Rank must be >= 2 for 2D slice" }
        val offsets = IntArray(rank) { 0 }
        val extents = shape.copyOf()
        offsets[rank - 2] = rowStart
        offsets[rank - 1] = colStart
        extents[rank - 2] = rows
        extents[rank - 1] = cols
        return slice(offsets, extents)
    }

    /** Broadcast add (e.g., bias across batch) */
    fun broadcastAdd(other: CursorTensor<T>, broadcastDim: Int): CursorTensor<T> {
        require(other.rank <= rank) { "Other rank must be <= self rank" }
        // Implementation: broadcast other across broadcastDim
        // For now placeholder
        return this
    }

    /** Reduction along a dimension (sum, max, log-sum-exp for softmax) */
    fun reduce(op: ReduceOp, dim: Int): CursorTensor<T> {
        require(dim in 0 until rank) { "Dim out of bounds" }

        val outShape = removeAt(shape.copyOf(), dim)

        val outStride = removeAt(layout.stride.copyOf(), dim)

        val outLayout = Layout(outShape, outStride)
        val outSize = outShape.fold(1) { a, b -> a * b }
        val outData = Array<T>(outSize) { data.first() } // Placeholder

        // Real implementation would iterate and reduce
        return CursorTensor(
            shape = outShape,
            layout = outLayout,
            data = outData,
            provenance = provenance?.withTransform("reduce_$op"),
            columns = columns.filterIndexed { i, _ -> i != dim },
            facets = facets
        )
    }

    /** Helper to remove element at index from IntArray */
    private fun removeAt(array: IntArray, index: Int): IntArray {
        val result = IntArray(array.size - 1)
        var j = 0
        for (i in array.indices) {
            if (i != index) {
                result[j++] = array[i]
            }
        }
        return result
    }

    /** Matrix multiply: this [M, K] * other [K, N] -> [M, N] */
    fun gemm(other: CursorTensor<T>): CursorTensor<T> {
        require(this.rank == 2 && other.rank == 2) { "GEMM requires 2D tensors" }
        require(this.shape[1] == other.shape[0]) { "K dimension mismatch: ${this.shape[1]} vs ${other.shape[0]}" }

        val m = this.shape[0]
        val n = other.shape[1]
        val k = this.shape[1]

        val outShape = intArrayOf(m, n)
        val outLayout = Layout.fromCursorShape(outShape)
        val outData = Array(m * n) { this.data.first() } // Placeholder

        return CursorTensor(
            shape = outShape,
            layout = outLayout,
            data = outData,
            provenance = provenance?.withTransform("gemm")
                ?.withMetadata("m", m.toString())
                ?.withMetadata("n", n.toString())
                ?.withMetadata("k", k.toString()),
            columns = listOf("m", "n")
        )
    }

    /** Join along a new dimension (algebraic Join) */
    fun join(other: CursorTensor<T>, dim: Int): CursorTensor<T> {
        // Implementation placeholder
        return this
    }

    /** Lazy map (algebraic Series α) */
    fun map(transform: (T) -> T): CursorTensor<T> {
        val newData = data.map(transform).toTypedArray()
        return CursorTensor(
            shape = shape,
            layout = layout,
            data = newData,
            provenance = provenance?.withTransform("map"),
            columns = columns,
            facets = facets
        )
    }

    /** Emit blackboard observation for experiment tracking */
    fun toBlackboardEntry(
        role: String,
        metadata: Map<String, String> = emptyMap()
    ): BlackboardEntry {
        return BlackboardEntry(
            role = role,
            metadata = metadata,
            tensorShape = shape,
            tensorLayout = layout,
            provenance = provenance,
            columns = columns,
            facets = facets
        )
    }

    /** Create from cursor data */
    companion object {
        fun <T> fromCursor(
            cursor: Cursor,
            layout: Layout? = null,
            provenance: ProvenanceInfo? = null
        ): CursorTensor<T> {
            // Extract shape and data from cursor
            // This is a simplified version - real impl uses cursor API
            val shape = cursor.shape ?: intArrayOf(cursor.size)
            val layoutResolved = layout ?: Layout.fromCursorShape(shape)
            val data = cursor.toDoubleArray() // Placeholder
            return CursorTensor(
                shape = shape,
                layout = layoutResolved,
                data = data as Array<T>,
                provenance = provenance
            )
        }

        fun <T> fromArray(
            data: Array<T>,
            shape: IntArray,
            layout: Layout? = null,
            provenance: ProvenanceInfo? = null
        ): CursorTensor<T> {
            val layoutResolved = layout ?: Layout.fromCursorShape(shape)
            return CursorTensor(shape, layoutResolved, data, provenance)
        }
    }
}

/** Blackboard entry for experiment tracking */
data class BlackboardEntry(
    val role: String,
    val metadata: Map<String, String>,
    val tensorShape: IntArray,
    val tensorLayout: Layout,
    val provenance: ProvenanceInfo? = null,
    val columns: List<String> = emptyList(),
    val facets: Map<String, Any> = emptyMap()
)

/** Mock cursor for compilation - replace with real borg.trikeshed.cursor.Cursor */
interface Cursor {
    val shape: IntArray?
    val size: Int
    val columns: List<String>
    fun toDoubleArray(): DoubleArray
    fun get(index: Int): Any
}

/** Mock implementation */
data class MockCursor(
    override val columns: List<String>,
    override val shape: IntArray,
    val data: Array<Any>
) : Cursor {
    override val size: Int get() = data.size
    override fun toDoubleArray(): DoubleArray = data.map { it.toString().toDouble() }.toDoubleArray()
    override fun get(index: Int): Any = data[index]
}

/**
 * Kernel transforms as algebraic operations.
 * These compose with Join/Series and can be optimized.
 */
interface KernelTransform<in A, out B> {
    fun transform(input: A): B
}

/** GEMM as a composable transform */
class GemmTransform(
    val m: Int, val n: Int, val k: Int,
    val mmaAtom: MmaAtom = MmaAtoms.MMA_16_16_16_F16
) : KernelTransform<Pair<CursorTensor<Double>, CursorTensor<Double>>, CursorTensor<Double>> {

    override fun transform(input: Pair<CursorTensor<Double>, CursorTensor<Double>>): CursorTensor<Double> {
        val (a, b) = input
        return a.gemm(b)
    }
}

/** Attention Q*K^T transform */
class AttentionQKTTransform(
    val seqLen: Int,
    val headDim: Int,
    val causal: Boolean = true
) : KernelTransform<Triple<CursorTensor<Double>, CursorTensor<Double>, CursorTensor<Double>>, CursorTensor<Double>> {

    override fun transform(input: Triple<CursorTensor<Double>, CursorTensor<Double>, CursorTensor<Double>>): CursorTensor<Double> {
        val (q, k, s) = input
        // s = q * k^T with causal mask
        return s
    }
}

/** Attention Softmax(S)*V transform */
class AttentionSVTransform(
    val seqLen: Int,
    val headDim: Int
) : KernelTransform<Pair<CursorTensor<Double>, CursorTensor<Double>>, CursorTensor<Double>> {

    override fun transform(input: Pair<CursorTensor<Double>, CursorTensor<Double>>): CursorTensor<Double> {
        val (s, v) = input
        // o = softmax(s) * v
        return v // Placeholder
    }
}