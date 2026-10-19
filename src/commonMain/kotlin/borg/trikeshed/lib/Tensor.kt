package borg.trikeshed.lib

/** Dimension extents, or a coordinate in that domain; values live in MetaSeries<Tensor, T>. */
typealias Tensor = Series<Int>

typealias Shape = Tensor

/** Build a Shape from vararg dimension extents */
fun shapeOf(vararg dims: Int): Shape = dims.size j { i: Int -> dims[i] }

/** Empty shape for scalar (rank-0) tensors */
fun shapeOf(): Shape = 0 j { _: Int -> throw IllegalStateException("empty shape") }

/** Number of scalar or array-valued cells; an empty shape denotes one scalar cell. */
fun tensorSize(shape: Tensor): Int {
    require(shape.size >= 0) { "Negative tensor rank" }
    var empty = false
    for (extent in shape.view) {
        require(extent >= 0) { "Negative tensor extent" }
        empty = empty || extent == 0
    }
    if (empty) return 0
    var count = 1
    for (extent in shape.view) {
        require(count <= Int.MAX_VALUE / extent) { "Tensor size exceeds Int indices" }
        count *= extent
    }
    return count
}

/** Checked row-major coordinate to flat index. */
fun tensorOffset(shape: Tensor, index: Tensor): Int {
    require(tensorSize(shape) > 0) { "Empty tensor has no coordinates" }
    require(index.size == shape.size) { "Tensor index rank differs from its shape" }
    var offset = 0
    for (axis in 0 until shape.size) {
        val extent = shape[axis]
        val coordinate = index[axis]
        require(coordinate in 0 until extent) { "Tensor coordinate exceeds its shape" }
        offset = offset * extent + coordinate
    }
    return offset
}

/** Checked flat index to row-major coordinate. */
fun tensorIndex(shape: Tensor, offset: Int): Tensor {
    require(offset in 0 until tensorSize(shape)) { "Tensor offset exceeds its shape" }
    var remaining = offset
    val index = IntArray(shape.size)
    for (axis in shape.size - 1 downTo 0) {
        val extent = shape[axis]
        index[axis] = remaining % extent
        remaining /= extent
    }
    return index.toSeries()
}

/** Borrow cells without evaluating them; only the extent metadata is snapshotted. */
fun <T> tensorOf(shape: Tensor, values: Series<T>): MetaSeries<Tensor, T> {
    require(shape.size >= 0) { "Negative tensor rank" }
    val extents = IntArray(shape.size) { shape[it] }.toSeries()
    require(tensorSize(extents) == values.size) { "Tensor storage differs from its shape" }
    return extents j { index: Tensor -> values[tensorOffset(extents, index)] }
}

fun tensorOf(shape: Tensor, values: FloatArray): MetaSeries<Tensor, Float> = tensorOf(shape, values.toSeries())
fun tensorOf(shape: Tensor, values: DoubleArray): MetaSeries<Tensor, Double> = tensorOf(shape, values.toSeries())
fun tensorOf(shape: Tensor, values: ByteArray): MetaSeries<Tensor, Byte> = tensorOf(shape, values.toSeries())

/** Each array entry remains one T, including when T itself is a primitive array. */
fun <T> tensorOf(shape: Tensor, values: Array<T>): MetaSeries<Tensor, T> = tensorOf(shape, values.toSeries())

/** Create a scalar (rank-0) tensor from a single value */
fun <T> scalarTensor(value: T): MetaSeries<Tensor, T> = tensorOf(shapeOf(), 1 j { _: Int -> value })

/**
 * Covector (Riesz dual) over a co-located tensor.
 * Wraps a zero-arg functional that contracts to produce a value.
 */
class CoTensor<T>(private val fn: () -> T) {
    /** Evaluate the covector to produce the contracted result */
    fun contract(): T = fn()
}

/**
 * Infix operator: create a CoTensor from a tensor and a covector functional.
 * The functional receives the tensor and returns the contracted value.
 */
infix fun <T, R> MetaSeries<Tensor, T>.co(fn: (MetaSeries<Tensor, T>) -> R): CoTensor<R> = CoTensor { fn(this) }
