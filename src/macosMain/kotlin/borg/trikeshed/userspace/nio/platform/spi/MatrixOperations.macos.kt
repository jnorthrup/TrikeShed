package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.JevaWeight
import kotlinx.cinterop.*
import mlxc.*
import platform.posix.memcpy

/** Float32 GEMM on the MLX GPU stream through mlx-c; tier 1 of docs/mlx-tensor-core.md. */
class MlxMatrixOperations : MatrixOperations {
    override fun open(): MlxMatrixSession = MlxMatrixSession(mlx_default_gpu_stream_new(), identity)

    companion object {
        val identity: String by lazy {
            memScoped {
                val text = alloc<mlx_string_>()
                mlx_string_new().place(text.ptr)
                try {
                    status(mlx_version(text.ptr), "version")
                    "mlx-c:matmul:gpu:float32:" + mlx_string_data(text.readValue())!!.toKString()
                } finally { mlx_string_free(text.readValue()) }
            }
        }

        fun status(code: Int, call: String) { check(code == 0) { "mlx-c $call failed: $code" } }
    }
}

/**
 * An MLX array as a cell of the PRELOAD tensor algebra: [a] is the shape, [b] the coordinate oracle. MLX is lazy like
 * the algebra; the graph is evaluated when a cell is first read. Float32, BFloat16 and Float16 arrays read as Float;
 * packed integer arrays (quantized weights) have no scalar cells and refuse to be read. The session owns the handle.
 */
class MlxTensor internal constructor(
    private val session: MlxMatrixSession,
    internal val handle: CValue<mlx_array_>,
    shape: Tensor,
) : JevaWeight {
    override val a: Tensor = IntArray(shape.size) { shape[it] }.toSeries()
    override val size: Int = tensorSize(a)
    override val b: (Tensor) -> Float = { index -> cells()[tensorOffset(a, index)] }

    private val host: CPointer<FloatVar> by lazy { session.floats(handle) }

    private fun cells(): CPointer<FloatVar> { session.gpu(); return host }

    override fun read(offset: Int, dst: FloatArray, start: Int, length: Int, check: () -> Unit) {
        require(offset >= 0 && offset <= size && length >= 0 && length <= size - offset) { "MLX range exceeds tensor" }
        require(start >= 0 && start <= dst.size && length <= dst.size - start) { "MLX range exceeds destination" }
        check()
        if (length == 0) return
        val source = cells()
        dst.usePinned { memcpy(it.addressOf(start), source + offset.toLong(), length.toULong() * 4u) }
        check()
    }
}

/** One GPU stream and the arrays made on it; close frees every owned handle, then the stream. */
class MlxMatrixSession internal constructor(
    private var stream: CValue<mlx_stream_>?,
    override val identity: String,
) : MatrixSession {
    private val owned = ArrayList<CValue<mlx_array_>>()

    internal fun gpu(): CValue<mlx_stream_> = checkNotNull(stream) { "Matrix session is closed" }

    private fun own(handle: CValue<mlx_array_>): CValue<mlx_array_> = handle.also { owned += it }

    /** Handles made inside [block] are freed when it returns; the result must be copied out of the device before then. */
    fun <R> scoped(block: () -> R): R {
        val mark = owned.size
        try { return block() } finally {
            while (owned.size > mark) mlx_array_free(owned.removeAt(owned.lastIndex))
        }
    }

    private fun make(name: String, call: (CPointer<mlx_array_>) -> Int): CValue<mlx_array_> = memScoped {
        val out = alloc<mlx_array_>().apply { ctx = null }
        MlxMatrixOperations.status(call(out.ptr), name)
        own(out.readValue())
    }

    private fun shapeOf(handle: CValue<mlx_array_>): Tensor {
        val rank = mlx_array_ndim(handle).toInt()
        val extents = mlx_array_shape(handle)
        return IntArray(rank) { extents!![it] }.toSeries()
    }

    /** Float32 cells of any float array, row-contiguous and evaluated. */
    internal fun floats(handle: CValue<mlx_array_>): CPointer<FloatVar> {
        val stream = gpu()
        val kind = mlx_array_dtype(handle)
        require(kind == mlx_dtype.MLX_FLOAT32 || kind == mlx_dtype.MLX_BFLOAT16 || kind == mlx_dtype.MLX_FLOAT16) {
            "MLX array has no Float cells: $kind"
        }
        val wide = if (kind == mlx_dtype.MLX_FLOAT32) handle
            else make("astype") { mlx_astype(it, handle, mlx_dtype.MLX_FLOAT32, stream) }
        val dense = make("contiguous") { mlx_contiguous(it, wide, false, stream) }
        MlxMatrixOperations.status(mlx_array_eval(dense), "eval")
        return checkNotNull(mlx_array_data_float32(dense)) { "mlx-c returned no data" }
    }

    /** Copy host cells to the device; the result is owned by this session. */
    fun tensor(values: FloatArray, shape: Tensor): MlxTensor {
        require(tensorSize(shape) == values.size) { "Tensor storage differs from its shape" }
        val handle = memScoped {
            val extents = allocArray<IntVar>(shape.size).also { for (i in 0 until shape.size) it[i] = shape[i] }
            values.usePinned { mlx_array_new_data(it.addressOf(0), extents, shape.size, mlx_dtype.MLX_FLOAT32) }
        }
        return MlxTensor(this, own(handle), shape)
    }

    /**
     * Named tensors of one safetensors file, read by MLX itself on first evaluation. This bypasses the NIO file facade;
     * whether that stays is the open decision in docs/mlx-tensor-core.md section 5.
     */
    fun load(path: String, vararg names: String): List<MlxTensor> {
        gpu()
        // Load::eval_gpu is not implemented in MLX; the file read runs on a CPU stream, consumers run on the GPU stream.
        val reader = mlx_default_cpu_stream_new()
        return memScoped {
            val tensors = alloc<mlx_map_string_to_array_>()
            mlx_map_string_to_array_new().place(tensors.ptr)
            val metadata = alloc<mlx_map_string_to_string_>()
            mlx_map_string_to_string_new().place(metadata.ptr)
            try {
                MlxMatrixOperations.status(mlx_load_safetensors(tensors.ptr, metadata.ptr, path, reader), "load")
                names.map { name ->
                    val handle = make("get $name") { mlx_map_string_to_array_get(it, tensors.readValue(), name) }
                    MlxTensor(this@MlxMatrixSession, handle, shapeOf(handle))
                }
            } finally {
                mlx_map_string_to_array_free(tensors.readValue())
                mlx_map_string_to_string_free(metadata.readValue())
                mlx_stream_free(reader)
            }
        }
    }

    /** input [rows, in] against a packed weight [out, in * bits / 32] with its scales and biases: [rows, out]. */
    fun quantizedLinear(input: MlxTensor, weight: MlxTensor, scales: MlxTensor, biases: MlxTensor,
                        groupSize: Int, bits: Int): MlxTensor {
        val stream = gpu()
        val group = cValue<mlx_optional_int_> { value = groupSize; has_value = true }
        val width = cValue<mlx_optional_int_> { value = bits; has_value = true }
        val handle = make("quantized_matmul") {
            mlx_quantized_matmul(it, input.handle, weight.handle, scales.handle, biases.handle, true, group, width, "affine", stream)
        }
        return MlxTensor(this, handle, shapeOf(handle))
    }

    private fun derived(name: String, call: (CPointer<mlx_array_>) -> Int): MlxTensor {
        val handle = make(name, call)
        return MlxTensor(this, handle, shapeOf(handle))
    }

    private fun widened(handle: CValue<mlx_array_>): CValue<mlx_array_> {
        val stream = gpu()
        return if (mlx_array_dtype(handle) == mlx_dtype.MLX_FLOAT32) handle
            else make("astype") { mlx_astype(it, handle, mlx_dtype.MLX_FLOAT32, stream) }
    }

    /** Rows of a quantized embedding table named by token ids, dequantized to Float32: [tokens, width]. */
    fun embed(tokens: IntArray, weight: MlxTensor, scales: MlxTensor, biases: MlxTensor, groupSize: Int, bits: Int): MlxTensor {
        require(tokens.isNotEmpty()) { "No tokens" }
        val stream = gpu()
        val ids = own(memScoped {
            val extent = allocArray<IntVar>(1).also { it[0] = tokens.size }
            tokens.usePinned { mlx_array_new_data(it.addressOf(0), extent, 1, mlx_dtype.MLX_INT32) }
        })
        fun rows(table: MlxTensor) = make("take") { mlx_take_axis(it, table.handle, ids, 0, stream) }
        val group = cValue<mlx_optional_int_> { value = groupSize; has_value = true }
        val width = cValue<mlx_optional_int_> { value = bits; has_value = true }
        val none = cValue<mlx_array_> { ctx = null }
        val wide = cValue<mlx_optional_dtype_> { value = mlx_dtype.MLX_FLOAT32; has_value = true }
        return derived("dequantize") {
            mlx_dequantize(it, rows(weight), rows(scales), rows(biases), group, width, "affine", none, wide, stream)
        }
    }

    /** Row-major [tokens, in] against a dense weight [out, in]: [tokens, out]. */
    fun dense(input: MlxTensor, weight: MlxTensor): MlxTensor {
        val stream = gpu()
        val transposed = make("transpose") { mlx_transpose(it, widened(weight.handle), stream) }
        return derived("matmul") { mlx_matmul(it, input.handle, transposed, stream) }
    }

    fun zeros(vararg extents: Int): MlxTensor = tensor(FloatArray(tensorSize(extents.toSeries())), extents.toSeries())

    private fun binary(name: String, left: MlxTensor, right: MlxTensor,
                       op: (CPointer<mlx_array_>, CValue<mlx_array_>, CValue<mlx_array_>, CValue<mlx_stream_>) -> Int): MlxTensor {
        val stream = gpu()
        return derived(name) { op(it, left.handle, right.handle, stream) }
    }

    private fun unary(name: String, input: MlxTensor,
                      op: (CPointer<mlx_array_>, CValue<mlx_array_>, CValue<mlx_stream_>) -> Int): MlxTensor {
        val stream = gpu()
        return derived(name) { op(it, input.handle, stream) }
    }

    fun add(left: MlxTensor, right: MlxTensor): MlxTensor = binary("add", left, right) { r, a, b, s -> mlx_add(r, a, b, s) }
    fun minus(left: MlxTensor, right: MlxTensor): MlxTensor = binary("subtract", left, right) { r, a, b, s -> mlx_subtract(r, a, b, s) }
    fun exp(input: MlxTensor): MlxTensor = unary("exp", input) { r, a, s -> mlx_exp(r, a, s) }
    fun negate(input: MlxTensor): MlxTensor = unary("negative", input) { r, a, s -> mlx_negative(r, a, s) }

    /** log(1 + e^x), stable for large x. */
    fun softplus(input: MlxTensor): MlxTensor =
        binary("logaddexp", input, zeros(*IntArray(input.a.size) { input.a[it] })) { r, a, b, s -> mlx_logaddexp(r, a, b, s) }

    fun scale(input: MlxTensor, factor: Float): MlxTensor {
        val stream = gpu()
        val scalar = own(mlx_array_new_float(factor))
        return derived("scale") { mlx_multiply(it, input.handle, scalar, stream) }
    }

    fun sum(input: MlxTensor, axis: Int): MlxTensor {
        val stream = gpu()
        return derived("sum") { mlx_sum_axis(it, input.handle, axis, false, stream) }
    }

    /** Each element repeated consecutively along [axis]. */
    fun repeat(input: MlxTensor, repeats: Int, axis: Int): MlxTensor {
        val stream = gpu()
        return derived("repeat") { mlx_repeat_axis(it, input.handle, repeats, axis, stream) }
    }

    /** Cells [from, until) along [axis]. */
    fun slice(input: MlxTensor, axis: Int, from: Int, until: Int): MlxTensor {
        val stream = gpu()
        val rank = input.a.size
        return derived("slice") { out ->
            memScoped {
                val start = allocArray<IntVar>(rank).also { for (i in 0 until rank) it[i] = if (i == axis) from else 0 }
                val stop = allocArray<IntVar>(rank).also { for (i in 0 until rank) it[i] = if (i == axis) until else input.a[i] }
                val strides = allocArray<IntVar>(rank).also { for (i in 0 until rank) it[i] = 1 }
                mlx_slice(out, input.handle, start, rank.toULong(), stop, rank.toULong(), strides, rank.toULong(), stream)
            }
        }
    }

    fun concatenate(parts: List<MlxTensor>, axis: Int): MlxTensor {
        val stream = gpu()
        return memScoped {
            val vector = alloc<mlx_vector_array_>()
            mlx_vector_array_new().place(vector.ptr)
            try {
                for (part in parts) MlxMatrixOperations.status(mlx_vector_array_append_value(vector.readValue(), part.handle), "append")
                derived("concatenate") { mlx_concatenate_axis(it, vector.readValue(), axis, stream) }
            } finally { mlx_vector_array_free(vector.readValue()) }
        }
    }

    /** 1-D convolution without padding: input [batch, length, channels], weight [channels, kernel, channels / groups]. */
    fun conv1d(input: MlxTensor, weight: MlxTensor, groups: Int): MlxTensor {
        val stream = gpu()
        return derived("conv1d") { mlx_conv1d(it, input.handle, widened(weight.handle), 1, 0, 1, groups, stream) }
    }

    /** Root-mean-square norm over the last axis with the stored weight used as-is (the checkpoint is pre-shifted). */
    fun rmsNorm(input: MlxTensor, weight: MlxTensor?, epsilon: Float): MlxTensor {
        val stream = gpu()
        val none = cValue<mlx_array_> { ctx = null }
        return derived("rms_norm") {
            mlx_fast_rms_norm(it, input.handle, weight?.let { w -> widened(w.handle) } ?: none, epsilon, stream)
        }
    }

    fun silu(input: MlxTensor): MlxTensor = times(input, sigmoid(input))

    fun sigmoid(input: MlxTensor): MlxTensor {
        val stream = gpu()
        return derived("sigmoid") { mlx_sigmoid(it, input.handle, stream) }
    }

    fun reshape(input: MlxTensor, vararg extents: Int): MlxTensor {
        val stream = gpu()
        require(tensorSize(extents.toSeries()) == input.size) { "Reshape changes the cell count" }
        return derived("reshape") { out ->
            memScoped {
                val shape = allocArray<IntVar>(extents.size).also { for (i in extents.indices) it[i] = extents[i] }
                mlx_reshape(out, input.handle, shape, extents.size.toULong(), stream)
            }
        }
    }

    fun swapAxes(input: MlxTensor, first: Int, second: Int): MlxTensor {
        val stream = gpu()
        return derived("swapaxes") { mlx_swapaxes(it, input.handle, first, second, stream) }
    }

    /** Equal parts along [axis]. */
    fun split(input: MlxTensor, parts: Int, axis: Int): List<MlxTensor> {
        val stream = gpu()
        return memScoped {
            val vector = alloc<mlx_vector_array_>()
            mlx_vector_array_new().place(vector.ptr)
            try {
                MlxMatrixOperations.status(mlx_split(vector.ptr, input.handle, parts, axis, stream), "split")
                List(parts) { index ->
                    val handle = make("split part") { mlx_vector_array_get(it, vector.readValue(), index.toULong()) }
                    MlxTensor(this@MlxMatrixSession, handle, shapeOf(handle))
                }
            } finally { mlx_vector_array_free(vector.readValue()) }
        }
    }

    /** Rotary embedding of the leading [dimensions] of the last axis, positions counted from [offset] along axis -2. */
    fun rope(input: MlxTensor, dimensions: Int, base: Float, offset: Int = 0): MlxTensor {
        val stream = gpu()
        val theta = cValue<mlx_optional_float_> { value = base; has_value = true }
        val none = cValue<mlx_array_> { ctx = null }
        return derived("rope") { mlx_fast_rope(it, input.handle, dimensions, false, theta, 1f, offset, none, stream) }
    }

    /** Causal scaled dot-product attention over [batch, heads, tokens, width]; key/value heads group the query heads. */
    fun attention(queries: MlxTensor, keys: MlxTensor, values: MlxTensor, scale: Float): MlxTensor {
        val stream = gpu()
        val none = cValue<mlx_array_> { ctx = null }
        return derived("attention") {
            mlx_fast_scaled_dot_product_attention(it, queries.handle, keys.handle, values.handle, scale, "causal", none, none, stream)
        }
    }

    fun times(left: MlxTensor, right: MlxTensor): MlxTensor {
        val stream = gpu()
        return derived("multiply") { mlx_multiply(it, left.handle, right.handle, stream) }
    }

    override fun gemm(m: Int, n: Int, k: Int, a: FloatArray, aOffset: Int, lda: Int,
                      b: FloatArray, bOffset: Int, ldb: Int, transposeB: Boolean,
                      c: FloatArray, cOffset: Int, ldc: Int) {
        val gpu = gpu()
        require(m >= 0 && n >= 0 && k >= 0) { "Negative matrix extent" }
        val bRows = if (transposeB) n else k
        val bColumns = if (transposeB) k else n
        span(a, aOffset, m, k, lda); span(b, bOffset, bRows, bColumns, ldb); span(c, cOffset, m, n, ldc)
        if (m == 0 || n == 0) return
        if (k == 0) {
            repeat(m) { row -> c.fill(0f, cOffset + row * ldc, cOffset + row * ldc + n) }
            return
        }
        val handles = ArrayList<CValue<mlx_array_>>(4)
        try {
            memScoped {
                val left = upload(a, aOffset, m, k, lda).also { handles += it }
                var right = upload(b, bOffset, bRows, bColumns, ldb).also { handles += it }
                if (transposeB) {
                    val flipped = alloc<mlx_array_>().apply { ctx = null }
                    MlxMatrixOperations.status(mlx_transpose(flipped.ptr, right, gpu), "transpose")
                    right = flipped.readValue().also { handles += it }
                }
                val product = alloc<mlx_array_>().apply { ctx = null }
                MlxMatrixOperations.status(mlx_matmul(product.ptr, left, right, gpu), "matmul")
                val result = product.readValue().also { handles += it }
                MlxMatrixOperations.status(mlx_array_eval(result), "eval")
                val data = checkNotNull(mlx_array_data_float32(result)) { "mlx-c returned no data" }
                c.usePinned { out ->
                    repeat(m) { row ->
                        memcpy(out.addressOf(cOffset + row * ldc), data + row.toLong() * n, n.toULong() * 4u)
                    }
                }
            }
        } finally { handles.forEach { mlx_array_free(it) } }
    }

    override fun close() {
        owned.forEach { mlx_array_free(it) }
        owned.clear()
        stream?.let { mlx_stream_free(it) }
        stream = null
    }

    private fun upload(values: FloatArray, offset: Int, rows: Int, columns: Int, stride: Int): CValue<mlx_array_> {
        val packed = if (stride == columns) values else FloatArray(rows * columns).also { dense ->
            repeat(rows) { row -> values.copyInto(dense, row * columns, offset + row * stride, offset + row * stride + columns) }
        }
        val from = if (stride == columns) offset else 0
        return memScoped {
            val shape = allocArray<IntVar>(2).also { it[0] = rows; it[1] = columns }
            packed.usePinned { mlx_array_new_data(it.addressOf(from), shape, 2, mlx_dtype.MLX_FLOAT32) }
        }
    }

    private fun span(values: FloatArray, offset: Int, rows: Int, columns: Int, stride: Int) {
        require(offset >= 0 && offset <= values.size && stride >= maxOf(1, columns)) { "Invalid matrix offset or stride" }
        val count = if (rows == 0 || columns == 0) 0L else (rows.toLong() - 1) * stride + columns
        require(count <= values.size.toLong() - offset) { "Matrix span exceeds its array" }
    }
}
