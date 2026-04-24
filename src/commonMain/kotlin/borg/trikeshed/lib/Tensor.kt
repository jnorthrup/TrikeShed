@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib

/**
 * Algebraic tensor types: Join-based shape/tensor/cotensor.
 *
 * Tensor  = Join<Shape, (Shape) -> T>   -- a shape plus an indexing function
 * Shape   = Series<Int>                  -- dimension extents
 * CoTensor = a covector (Riesz dual) that contracts over a co-located tensor
 */

/** Shape of a tensor: dimension extents as a Series<Int> */
typealias Shape = Series<Int>

/** Algebraic tensor: shape paired with an indexing function from multi-index to value */
typealias Tensor<T> = Join<Shape, (Shape) -> T>

/** Build a Shape from vararg dimension extents */
fun shapeOf(vararg dims: Int): Shape = dims.size j { i: Int -> dims[i] }

/** Empty shape for scalar (rank-0) tensors */
fun shapeOf(): Shape = 0 j { _: Int -> throw IllegalStateException("empty shape") }

/** Create a scalar (rank-0) tensor from a single value */
fun <T> scalarTensor(value: T): Tensor<T> = shapeOf() j { _: Shape -> value }

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
infix fun <T, R> Tensor<T>.co(fn: (Tensor<T>) -> R): CoTensor<R> = CoTensor { fn(this) }
