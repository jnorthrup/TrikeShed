package borg.trikeshed.platform.tensor

/**
 * Core tensor types and operations
 */

/**
 * Data type for tensor elements
 */
enum class DType(val sizeBytes: Int) {
    F32(4),
    F64(8),
    I32(4),
    I64(8),
    U8(1),
    U32(4),
    U64(8),
    Bool(1)
}

/**
 * Shape of a tensor
 */
class TensorShape(private val dims: List<Int>) {
    companion object {
        fun create(dims: List<Int>): TensorShape = TensorShape(dims)
    }

    fun rank(): Int = dims.size
    fun dims(): List<Int> = dims
    fun numel(): Int = dims.fold(1) { acc, d -> acc * d }
    fun isScalar(): Boolean = dims.isEmpty()
}

/**
 * Basic tensor structure
 */
class Tensor private constructor(
    private val data: ByteArray,
    val shape: TensorShape,
    val dtype: DType,
    val strides: List<Int>
) {
    companion object {
        /**
         * Create a new tensor with uninitialized data
         */
        fun uninit(shape: TensorShape, dtype: DType): Tensor {
            val numel = shape.numel()
            val size = numel * dtype.sizeBytes
            val data = ByteArray(size)
            val strides = computeStrides(shape, dtype)

            return Tensor(data, shape, dtype, strides)
        }

        /**
         * Create a tensor filled with zeros
         */
        fun zeros(shape: TensorShape, dtype: DType): Tensor = uninit(shape, dtype)

        /**
         * Create a tensor filled with ones
         */
        fun ones(shape: TensorShape, dtype: DType): Tensor {
            val tensor = uninit(shape, dtype)
            // Simplified - handles F32 only
            if (dtype == DType.F32) {
                val ones = FloatArray(shape.numel()) { 1.0f }
                // Copy float data into byte array
                val byteBuffer = ByteArray(ones.size * 4)
                ones.forEachIndexed { i, f ->
                    val bytes = f.toBits().toByteArray()
                    bytes.copyInto(byteBuffer, i * 4)
                }
            }
            return tensor
        }

        /**
         * Compute strides for row-major layout
         */
        private fun computeStrides(shape: TensorShape, dtype: DType): List<Int> {
            val strides = mutableListOf<Int>()
            var stride = dtype.sizeBytes

            shape.dims().reversed().forEach { dim ->
                strides.add(stride)
                stride *= dim
            }

            return strides.reversed()
        }
    }

    fun shape(): TensorShape = shape
    fun dtype(): DType = dtype
    fun strides(): List<Int> = strides
    fun asBytes(): ByteArray = data.copyOf()
    fun asBytesMut(): ByteArray = data

    override fun toString(): String {
        return "Tensor(shape=${shape.dims()}, dtype=$dtype, sizeBytes=${data.size})"
    }
}
