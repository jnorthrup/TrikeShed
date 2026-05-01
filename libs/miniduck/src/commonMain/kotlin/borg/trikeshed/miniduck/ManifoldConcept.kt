package borg.trikeshed.miniduck

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series2
import borg.trikeshed.isam.meta.IOMemento

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
    val payload: RowVec = DocRowVec(emptyList(), emptyList()),
) : RowVec(), Series2<Any?, () -> ColumnMeta> {

    override val size: Int get() = 3
    override val a: Int get() = 3

    override fun get(index: Int): Any? = when (index) {
        0 -> angular
        1 -> budget.packed
        2 -> budget.radialEnergy
        else -> throw IndexOutOfBoundsException(index.toString())
    }

    override val b: (Int) -> Join<Any?, () -> ColumnMeta> get() = fun(index: Int): Join<Any?, () -> ColumnMeta> {
        return when (index) {
            0 -> (angular as Any?) j { ColumnMeta("angular", IOMemento.IoLong) }
            1 -> (budget.packed as Any?) j { ColumnMeta("packed_budget", IOMemento.IoInt) }
            2 -> (budget.radialEnergy as Any?) j { ColumnMeta("radial_energy", IOMemento.IoInt) }
            else -> throw IndexOutOfBoundsException(index.toString())
        }
    }

    /** Child is the payload wrapped as a single-element Series. */
    override val child: Series<RowVec>
        get() = 1 j { WrappedRowVec(payload.toRowVec()) }

    /**
     * Decay: multiply all budget dimensions by factor.
     * Returns a new concept -- angular unchanged, radial energy reduced.
     */
    fun decay(factor: Float): ManifoldConcept = ManifoldConcept(
        angular = angular,
        budget = BudgetCoord.invoke(
            budget.p * factor,
            budget.d * factor,
            budget.q * factor,
        ),
        payload = payload,
    )

    /**
     * Reinforce: boost budget dimensions toward 1.0 by factor.
     * Returns a new concept -- angular unchanged, radial energy increased.
     */
    fun reinforce(factor: Float): ManifoldConcept = ManifoldConcept(
        angular = angular,
        budget = BudgetCoord.invoke(
            minOf(1f, budget.p + factor * (1f - budget.p)),
            minOf(1f, budget.d + factor * (1f - budget.d)),
            minOf(1f, budget.q + factor * (1f - budget.q)),
        ),
        payload = payload,
    )

    companion object {
        /**
         * Hamming distance between two angular addresses.
         * Geodesic distance on the Hamming hypersphere -- number of differing bits.
         */
        fun hamming(a: Long, b: Long): Int {
            var xor = a xor b
            var count = 0
            while (xor != 0L) {
                count++
                xor = xor and (xor - 1L)
            }
            return count
        }

        /**
         * Angular walk: flip the specified bits on the hypersphere.
         * Produces a new angular address at Hamming-distance |bits| from origin.
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

/** Top-level Hamming distance -- delegates to ManifoldConcept.hamming. */
fun hamming(a: Long, b: Long): Int = ManifoldConcept.hamming(a, b)
