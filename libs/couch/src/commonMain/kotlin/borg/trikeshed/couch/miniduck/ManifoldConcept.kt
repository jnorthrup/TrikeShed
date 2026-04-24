package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * ManifoldConcept: a concept on the NARS manifold.
 *
 * Angular coordinate: Long address on the Hamming hypersphere F_2^63.
 *   Two concepts are "nearby" if their Hamming distance is small.
 *   Angular identity is stable -- it encodes semantic identity, not attention.
 *
 * Radial coordinate: BudgetCoord encoding (priority, durability, quality).
 *   Radial energy = average confidence.
 *   High energy = close to origin = deeply imprinted.
 *   Decay = radial drift outward. Reinforcement = radial compression inward.
 *
 * This is a MiniRowVec: scalar surface is [angular, packed_budget, radial_energy].
 * The payload is exposed via child as a single-element Series.
 */
class ManifoldConcept(
    val angular: Long,
    val budget: BudgetCoord,
    private val payload: MiniRowVec,
) : MiniRowVec() {

    /** Scalar surface: [angular, packed_budget_as_int, radial_energy_bits]. */
    override val size: Int get() = 3

    override fun get(index: Int): Any? = when (index) {
        0 -> angular
        1 -> budget.packed
        2 -> budget.radialEnergy
        else -> throw IndexOutOfBoundsException(index.toString())
    }

    /** Child is the payload wrapped as a single-element Series. */
    override val child: Series<MiniRowVec>
        get() = 1 j { payload }

    /**
     * Decay: multiply all budget dimensions by factor.
     * Returns a new concept -- angular unchanged, radial energy reduced.
     */
    fun decay(factor: Float): ManifoldConcept = ManifoldConcept(
        angular = angular,
        budget = BudgetCoord(
            p = budget.p * factor,
            d = budget.d * factor,
            q = budget.q * factor,
        ),
        payload = payload,
    )

    /**
     * Reinforce: boost budget dimensions toward 1.0 by factor.
     * Returns a new concept -- angular unchanged, radial energy increased.
     */
    fun reinforce(factor: Float): ManifoldConcept = ManifoldConcept(
        angular = angular,
        budget = BudgetCoord(
            p = min(1f, budget.p + factor * (1f - budget.p)),
            d = min(1f, budget.d + factor * (1f - budget.d)),
            q = min(1f, budget.q + factor * (1f - budget.q)),
        ),
        payload = payload,
    )

    companion object {
        /**
         * Hamming distance between two angular addresses.
         * This is the geodesic distance on the Hamming hypersphere --
         * the number of bits that differ.
         */
        fun hamming(a: Long, b: Long): Int {
            var xor = a xor b
            var count = 0
            while (xor != 0L) {
                count++
                xor = xor and (xor - 1L) // clear lowest set bit
            }
            return count
        }

        /**
         * Angular walk: flip the specified bits on the hypersphere.
         * Produces a new angular address that is Hamming-distance |bits|
         * from the origin.
         */
        fun angularWalk(origin: Long, bits: Set<Int>): Long {
            var result = origin
            for (bit in bits) {
                result = result xor (1L shl bit)
            }
            return result
        }
    }
}
