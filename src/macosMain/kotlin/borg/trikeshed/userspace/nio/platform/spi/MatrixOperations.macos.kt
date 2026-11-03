package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.JevaWeight
import kotlinx.cinterop.*
import mlxc.*
import platform.posix.memcpy
import platform.posix.size_tVar

/**
 * Float32 GEMM on the MLX GPU stream through mlx-c; tier 1 of docs/mlx-tensor-core.md.
 * The three settings are process-wide MLX controls, applied whenever a session opens. MLX documents [memoryLimit]
 * as a graph-evaluation guideline rather than a hard allocation or host-memory bound; [cacheLimit] controls its free
 * buffer cache and [wiredLimit] controls how much memory MLX keeps resident. Other sessions may change these settings.
 * The default mlx-c error handler terminates the process; checking a returned status does not by itself prevent that.
 */
class MlxMatrixOperations(
    val memoryLimit: Long = 8L shl 30,
    val cacheLimit: Long = 512L shl 20,
    val wiredLimit: Long = 4L shl 30,
) : MatrixOperations {
    init {
        require(memoryLimit >= 0 && cacheLimit >= 0 && wiredLimit >= 0) { "MLX limits must be nonnegative" }
    }

    override fun open(): MlxMatrixSession {
        memScoped {
            val previous = alloc<size_tVar>()
            status(mlx_set_memory_limit(previous.ptr, memoryLimit.toULong()), "set_memory_limit")
            status(mlx_set_cache_limit(previous.ptr, cacheLimit.toULong()), "set_cache_limit")
            status(mlx_set_wired_limit(previous.ptr, wiredLimit.toULong()), "set_wired_limit")
        }
        return MlxMatrixSession(mlx_default_gpu_stream_new(), identity)
    }

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

        /** Device memory MLX holds now, its high-water mark since the last reset, and the freed buffers it caches. */
        class DeviceMemory(val active: Long, val peak: Long, val cache: Long) {
            override fun toString() = "active=${active shr 20}MiB peak=${peak shr 20}MiB cache=${cache shr 20}MiB"
        }

        fun deviceMemory(): DeviceMemory = memScoped {
            val active = alloc<size_tVar>(); val peak = alloc<size_tVar>(); val cache = alloc<size_tVar>()
            status(mlx_get_active_memory(active.ptr), "get_active_memory")
            status(mlx_get_peak_memory(peak.ptr), "get_peak_memory")
            status(mlx_get_cache_memory(cache.ptr), "get_cache_memory")
            DeviceMemory(active.value.toLong(), peak.value.toLong(), cache.value.toLong())
        }
    }
}

/**
 * An MLX array as a cell of the PRELOAD tensor algebra: [a] is the shape, [b] the coordinate oracle. MLX is lazy like
 * the algebra; the graph is evaluated when a cell is first read. Float32, BFloat16 and Float16 arrays read as Float;
 * packed integer arrays (quantized weights) have no scalar cells and refuse to be read. The session owns the handle.
 */
internal class MlxAllocation(internal var value: CValue<mlx_array_>?)

class MlxTensor internal constructor(
    private val session: MlxMatrixSession,
    handle: CValue<mlx_array_>,
    shape: Tensor,
) : JevaWeight {
    internal val allocation = session.allocation(handle)
    internal val handle: CValue<mlx_array_> get() = checkNotNull(allocation.value) { "MLX tensor has been released" }
    override val a: Tensor = IntArray(shape.size) { shape[it] }.toSeries()
    override val size: Int = tensorSize(a)
    override val b: (Tensor) -> Float = { index ->
        cells()[tensorOffset(a, index)].also { session.checkReaders() }
    }

    private var host: Join<MlxAllocation, CPointer<FloatVar>>? = null

    private fun cells(): CPointer<FloatVar> {
        session.gpu()
        val value = handle
        val retained = host
        if (retained != null && retained.a.value != null) return retained.b
        return session.floats(value).also { host = it }.b
    }

    override fun read(offset: Int, dst: FloatArray, start: Int, length: Int, check: () -> Unit) {
        require(offset >= 0 && offset <= size && length >= 0 && length <= size - offset) { "MLX range exceeds tensor" }
        require(start >= 0 && start <= dst.size && length <= dst.size - start) { "MLX range exceeds destination" }
        check()
        if (length == 0) {
            session.gpu()
            checkNotNull(allocation.value) { "MLX tensor has been released" }
            return
        }
        val source = cells()
        try {
            dst.usePinned { memcpy(it.addressOf(start), source + offset.toLong(), length.toULong() * 4u) }
            session.checkReaders()
            check()
        } catch (failure: Throwable) {
            dst.fill(0f, start, start + length)
            throw failure
        }
    }
}

/** One GPU stream and the arrays made on it; close frees every owned handle, then the stream. */
class MlxMatrixSession internal constructor(
    private var stream: CValue<mlx_stream_>?,
    override val identity: String,
) : MatrixSession {
    private val owned = ArrayList<MlxAllocation>()
    private val readers = ArrayList<Join<CValue<mlx_io_reader_>, MlxVerifiedReader>>()
    val fileIdentity: String get() { checkReaders(); return readers.single().b.identity }
    internal fun checkReaders() { for ((_, reader) in readers) reader.check() }

    internal fun gpu(): CValue<mlx_stream_> {
        checkReaders()
        return checkNotNull(stream) { "Matrix session is closed" }
    }

    private fun own(handle: CValue<mlx_array_>): CValue<mlx_array_> = handle.also { owned += MlxAllocation(it) }

    /** The latest owned result is the handle returned by the immediately preceding MLX call. */
    internal fun allocation(handle: CValue<mlx_array_>): MlxAllocation {
        val record = checkNotNull(owned.lastOrNull()) { "MLX array is not owned by this session" }
        check(record.value?.useContents { ctx } == handle.useContents { ctx }) { "MLX array is not the latest owned result" }
        return record
    }

    private fun free(record: MlxAllocation) {
        val handle = checkNotNull(record.value) { "MLX array has already been released" }
        record.value = null
        mlx_array_free(handle)
    }

    /** Position in the owned handles; pair with [settle]. */
    fun mark(): Int { gpu(); return owned.size }

    /** Release an evaluated tensor once no subsequent graph needs it. */
    internal fun release(tensor: MlxTensor) {
        val index = owned.indexOfLast { it === tensor.allocation }
        check(index >= 0) { "Tensor is not owned by this session" }
        free(owned.removeAt(index))
    }

    /**
     * Ends the lazy graph here: evaluates [keep], then frees every handle made since [mark] that is not one of them.
     * Without it a long sequence keeps every intermediate of every step alive until the scope closes. Tensors made
     * since [mark] other than [keep] must not be used afterwards.
     */
    fun settle(mark: Int, vararg keep: MlxTensor) {
        gpu()
        require(mark in 0..owned.size) { "Invalid MLX allocation mark" }
        for (tensor in keep) {
            require(owned.any { it === tensor.allocation }) { "Tensor is not owned by this session" }
            MlxMatrixOperations.status(mlx_array_eval(tensor.handle), "eval")
            checkReaders()
        }
        val kept = keep.map { tensor -> tensor.allocation }
        val survivors = ArrayList<MlxAllocation>()
        while (owned.size > mark) {
            val allocation = owned.removeAt(owned.lastIndex)
            if (kept.any { it === allocation }) survivors += allocation else free(allocation)
        }
        owned.addAll(survivors.reversed())
    }

    /** Handles made inside [block] are freed when it returns; the result must be copied out of the device before then. */
    fun <R> scoped(block: () -> R): R {
        gpu()
        val mark = owned.size
        try { return block() } finally {
            while (owned.size > mark) free(owned.removeAt(owned.lastIndex))
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
    internal fun floats(handle: CValue<mlx_array_>): Join<MlxAllocation, CPointer<FloatVar>> {
        val stream = gpu()
        val kind = mlx_array_dtype(handle)
        require(kind == mlx_dtype.MLX_FLOAT32 || kind == mlx_dtype.MLX_BFLOAT16 || kind == mlx_dtype.MLX_FLOAT16) {
            "MLX array has no Float cells: $kind"
        }
        val wide = if (kind == mlx_dtype.MLX_FLOAT32) handle
            else make("astype") { mlx_astype(it, handle, mlx_dtype.MLX_FLOAT32, stream) }
        val dense = make("contiguous") { mlx_contiguous(it, wide, false, stream) }
        val allocation = allocation(dense)
        MlxMatrixOperations.status(mlx_array_eval(dense), "eval")
        checkReaders()
        val data = checkNotNull(mlx_array_data_float32(dense)) { "mlx-c returned no data" }
        checkReaders()
        return allocation j data
    }

    /** Copy host cells to the device; the result is owned by this session. */
    fun tensor(values: FloatArray, shape: Tensor): MlxTensor {
        gpu()
        // A shape is an index oracle; capture it once before validating and
        // crossing the native boundary so it cannot change the admitted span.
        val captured = IntArray(shape.size) { shape[it] }.toSeries()
        require(tensorSize(captured) == values.size) { "Tensor storage differs from its shape" }
        val handle = memScoped {
            val extents = allocArray<IntVar>(captured.size).also { for (i in 0 until captured.size) it[i] = captured[i] }
            values.usePinned { mlx_array_new_data(it.addressOf(0), extents, captured.size, mlx_dtype.MLX_FLOAT32) }
        }
        return MlxTensor(this, own(handle), captured)
    }

    /**
     * Named tensors of one safetensors file. Lazy MLX reads are verified against the admitted block manifest.
     */
    fun load(path: String, vararg names: String, check: () -> Unit = {}): List<MlxTensor> {
        gpu()
        check()
        val verified = MlxVerifiedReader(path, check)
        try { verified.header(names, check) } catch (error: Throwable) {
            try { verified.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
        val input = verified.handle()
        readers += input j verified
        // Load::eval_gpu is not implemented in MLX; the file read runs on a CPU stream, consumers run on the GPU stream.
        val reader = mlx_default_cpu_stream_new()
        return memScoped {
            val tensors = alloc<mlx_map_string_to_array_>()
            mlx_map_string_to_array_new().place(tensors.ptr)
            val metadata = alloc<mlx_map_string_to_string_>()
            mlx_map_string_to_string_new().place(metadata.ptr)
            try {
                MlxMatrixOperations.status(mlx_load_safetensors_reader(tensors.ptr, metadata.ptr, input, reader), "load")
                verified.check()
                check()
                names.map { name ->
                    check()
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

    /** Complete verified CPU reads before a GPU graph can wait on the file reader. */
    fun materialize(tensors: Iterable<MlxTensor>, check: () -> Unit = {}) {
        gpu()
        for (tensor in tensors) {
            check()
            MlxMatrixOperations.status(mlx_array_eval(tensor.handle), "eval weight")
            checkReaders()
            check()
        }
    }

    /** input [rows, in] against a packed weight [out, in * bits / 32] with its scales and biases: [rows, out]. */
    fun quantizedLinear(input: MlxTensor, weight: MlxTensor, scales: MlxTensor, biases: MlxTensor,
                        groupSize: Int, bits: Int): MlxTensor {
        val width = quantizedWidth(weight, scales, biases, groupSize, bits)
        require(input.a.size > 0 && input.a[input.a.size - 1] == width) { "Quantized input width differs from weight" }
        val stream = gpu()
        val group = cValue<mlx_optional_int_> { value = groupSize; has_value = true }
        val bitWidth = cValue<mlx_optional_int_> { value = bits; has_value = true }
        val handle = make("quantized_matmul") {
            mlx_quantized_matmul(it, input.handle, weight.handle, scales.handle, biases.handle, true, group, bitWidth, "affine", stream)
        }
        return MlxTensor(this, handle, shapeOf(handle))
    }

    /** Check the affine packed layout before entering mlx-c, whose default handler can terminate the process. */
    internal fun quantizedWidth(weight: MlxTensor, scales: MlxTensor, biases: MlxTensor,
                                groupSize: Int, bits: Int): Int {
        require(mlxAffine(groupSize, bits)) { "Unsupported affine quantization" }
        require(weight.a.size == 2 && scales.a.size == 2 && biases.a.size == 2 &&
            weight.a[0] > 0 && weight.a[1] > 0 && scales.a[1] > 0 &&
            scales.a[0] == weight.a[0] && biases.a[0] == scales.a[0] && biases.a[1] == scales.a[1]) {
            "Invalid quantized weight, scale or bias shape"
        }
        val width = scales.a[1].toLong() * groupSize
        require(width <= Int.MAX_VALUE && width * bits == weight.a[1].toLong() * 32L) {
            "Quantized packed width differs from scales"
        }
        require(mlx_array_dtype(weight.handle) == mlx_dtype.MLX_UINT32) { "Quantized weight must be uint32" }
        require(floating(scales) && floating(biases)) { "Quantized scales and biases must be floating point" }
        return width.toInt()
    }

    internal fun floating(tensor: MlxTensor): Boolean = when (mlx_array_dtype(tensor.handle)) {
        mlx_dtype.MLX_FLOAT32, mlx_dtype.MLX_FLOAT16, mlx_dtype.MLX_BFLOAT16 -> true
        else -> false
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
        quantizedWidth(weight, scales, biases, groupSize, bits)
        require(tokens.all { it in 0 until weight.a[0] }) { "Embedding token outside vocabulary" }
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

    fun zeros(vararg extents: Int): MlxTensor {
        gpu()
        return tensor(FloatArray(tensorSize(extents.toSeries())), extents.toSeries())
    }

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
                checkReaders()
                val data = checkNotNull(mlx_array_data_float32(result)) { "mlx-c returned no data" }
                checkReaders()
                try {
                    c.usePinned { out ->
                        repeat(m) { row ->
                            memcpy(out.addressOf(cOffset + row * ldc), data + row.toLong() * n, n.toULong() * 4u)
                        }
                    }
                    checkReaders()
                } catch (failure: Throwable) {
                    repeat(m) { row -> c.fill(0f, cOffset + row * ldc, cOffset + row * ldc + n) }
                    throw failure
                }
            }
        } finally { handles.forEach { mlx_array_free(it) } }
    }

    override fun close() {
        owned.forEach(::free)
        owned.clear()
        readers.forEach { (reader, _) -> mlx_io_reader_free(reader) }
        readers.clear()
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
