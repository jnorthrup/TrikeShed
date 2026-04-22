package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.*

/** Shape: an ordered sequence of dimension sizes */
typealias Shape = Series<Int>

/**
 * Tensor: a Join of a Shape with a function from Shape to T.
 * This is NOT the semantic center of MiniDuck — it is a lowering/execution backend only.
 * The user-facing abstraction is Cursor = Series<MiniRowVec>.
 */
typealias Tensor<T> = Join<Shape, (Shape) -> T>

/**
 * CoTensor: the Riesz dual of Tensor<T>.
 * Structurally: a Tensor co-located with a linear functional that consumes a Tensor and yields T.
 * Evaluation: coTensor.b(coTensor.a) — contract the covector onto its co-located tensor.
 * Geometrically: a covector / 1-form in cotangent space.
 */
typealias CoTensor<T> = Join<Tensor<T>, (Tensor<T>) -> T>

/** Evaluate the CoTensor — apply its functional to its co-located tensor (inner product / contraction). */
fun <T> CoTensor<T>.contract(): T = b(a)

/** Convenience: build a Shape from vararg sizes */
fun shapeOf(vararg dims: Int): Shape = dims.size j { dims[it] }

/** Convenience: build a scalar Tensor (rank-0) */
fun <T> scalarTensor(value: T): Tensor<T> = shapeOf() j { _ -> value }

/** Convenience: build a CoTensor from a tensor and a functional */
infix fun <T> Tensor<T>.co(fn: (Tensor<T>) -> T): CoTensor<T> = this j fn
