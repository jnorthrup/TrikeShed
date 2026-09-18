package borg.trikeshed.splat

import borg.trikeshed.lib.Tensor
import borg.trikeshed.lib.Shape

/**
 * Finds an eigenvalue signature among Gaussian splat inputs.
 */
interface EigenFinder<T> {
    fun extractSignature(splat: Splat<T>): Tensor<Double>
}
