package borg.trikeshed.couch.miniduck

import kotlin.math.max
import kotlin.math.min

/**
 * BudgetCoord: compressed angular-radial coordinate for NARS manifold.
 *
 * Packs three confidence dimensions (priority, durability, quality) into a single UInt
 * using 10-bit fixed-point per dimension (30 bits total).
 *
 * Radial energy = (p + d + q) / 3 -- the average confidence, used as the
 * radial coordinate on the manifold. Higher energy = closer to origin = deeper imprint.
 *
 * Angular coordinate is separate (Long NUID address) -- see ManifoldConcept.
 */
class BudgetCoord private constructor(
    val packed: UInt,
    val p: Float,
    val d: Float,
    val q: Float,
) : Comparable<BudgetCoord> {

    /** Average confidence = radial energy on the manifold. 1.0 = origin (deepest imprint). */
    val radialEnergy: Float get() = (p + d + q) / 3f

    override fun compareTo(other: BudgetCoord): Int =
        radialEnergy.compareTo(other.radialEnergy)

    companion object {
        private const val MASK10 = 0x3FF
        private const val BITS = 10
        private const val SCALE = 1023f

        /** Create from three float components, clamped to [0,1]. */
        operator fun invoke(p: Float, d: Float, q: Float): BudgetCoord {
            val cp = p.coerceIn(0f, 1f)
            val cd = d.coerceIn(0f, 1f)
            val cq = q.coerceIn(0f, 1f)
            val packed = (((cp * SCALE).toInt() and MASK10) shl (2 * BITS)) or
                    (((cd * SCALE).toInt() and MASK10) shl BITS) or
                    ((cq * SCALE).toInt() and MASK10)
            return BudgetCoord(packed.toUInt(), cp, cd, cq)
        }

        /** Full budget: maximum imprint at origin. */
        fun full(): BudgetCoord = invoke(1f, 1f, 1f)

        /** Unpack a UInt into a BudgetCoord. */
        fun unpack(packed: UInt): BudgetCoord {
            val ip = ((packed.toInt() shr (2 * BITS)) and MASK10)
            val id = ((packed.toInt() shr BITS) and MASK10)
            val iq = (packed.toInt() and MASK10)
            return BudgetCoord(
                packed,
                ip / SCALE,
                id / SCALE,
                iq / SCALE,
            )
        }
    }
}
