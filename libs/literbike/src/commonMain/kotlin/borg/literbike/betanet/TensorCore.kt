package borg.literbike.betanet

/**
 * Lightweight tensor view utilities for prototype experiments.
 * No external dependencies. Focus: zero-copy mapping of byte buffers into multi-dim tensors.
 * Ported from literbike/src/betanet/tensor_core.rs.
 */

/**
 * Userspace backend selection for emulations.
 */
enum class UserspaceBackend {
    Mlir,
    Mlcore,
    Default;

    companion object {
        /**
         * Choose a backend by inspecting a filesystem or module path.
         */
        fun fromPath(path: String): UserspaceBackend {
            val lower = path.lowercase()
            return when {
                "mlir" in lower -> Mlir
                "mlcore" in lower -> Mlcore
                else -> Default
            }
        }
    }
}

/**
 * A simple, zero-copy view into a contiguous buffer interpreted as a D-dimensional tensor of T.
 */
class TensorView<T : Number>(
    private val data: List<T>,
    private val dims: List<Int>,
    private val strides: List<Int>
) {
    companion object {
        /**
         * Create a tensor view from a list and dimensions.
         */
        fun <T : Number> fromSlice(data: List<T>, dims: List<Int>): TensorView<T>? {
            val prod = dims.fold(1) { acc, d -> acc * d }
            if (prod != data.size) return null

            val strides = IntArray(dims.size) { 1 }
            for (i in dims.size - 2 downTo 0) {
                strides[i] = strides[i + 1] * dims[i + 1]
            }

            return TensorView(data, dims, strides.toList())
        }
    }

    /** Rank of the tensor */
    fun rank(): Int = dims.size

    /** Get element by multi-index */
    fun get(idx: List<Int>): T? {
        if (idx.size != rank()) return null
        var linear = 0
        for ((i, v) in idx.withIndex()) {
            if (v >= dims[i]) return null
            linear += v * strides[i]
        }
        return data.getOrNull(linear)
    }
}

/**
 * Owned tensor for u64 operations - supports contraction.
 */
class OwnedTensor(
    val data: List<Long>,
    val dims: List<Int>,
    private val strides: List<Int>
) {
    companion object {
        fun fromVec(data: List<Long>, dims: List<Int>): OwnedTensor? {
            val prod = dims.fold(1) { acc, d -> acc * d }
            if (prod != data.size) return null

            val strides = IntArray(dims.size) { 1 }
            for (i in dims.size - 2 downTo 0) {
                strides[i] = strides[i + 1] * dims[i + 1]
            }

            return OwnedTensor(data, dims, strides.toList())
        }
    }

    fun asView(): TensorView<Long> = TensorView.fromSlice(data, dims)!!
}

/**
 * Extension for u64 tensor views - contract axis sum.
 */
fun TensorView<Long>.contractAxisSum(axis: Int): OwnedTensor? {
    if (axis >= rank()) return null

    val outDims = dims.toMutableList()
    outDims.removeAt(axis)
    val outLen = outDims.fold(1) { acc, d -> acc * d }
    val out = LongArray(outLen) { 0L }

    // Iterate over every element in the input and accumulate
    val total = data.size
    for (linear in 0 until total) {
        // Convert linear -> multi-index
        var rem = linear
        val idx = IntArray(rank())
        for (i in 0 until rank()) {
            idx[i] = rem / strides[i]
            rem = rem % strides[i]
        }

        // Compute output linear index by skipping the axis
        var outLinear = 0
        var mul = 1
        for (i in rank() - 1 downTo 0) {
            if (i == axis) continue
            val outPos = idx[i]
            val od = if (i < axis) dims[i] else dims[i]
            outLinear += outPos * mul
            mul *= od
        }
        out[outLinear] += data[linear]
    }

    return OwnedTensor.fromVec(out.toList(), outDims)
}
