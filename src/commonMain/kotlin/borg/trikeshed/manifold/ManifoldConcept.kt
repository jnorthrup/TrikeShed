package borg.trikeshed.manifold

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Tensor
import borg.trikeshed.lib.j
import kotlin.jvm.JvmInline
import kotlin.math.pow

/**
 * BudgetCoord: NARS3 priority-durability-quality triple.
 *
 * Algebra:  p j (d j q) ≈ Join<Float, Twin<Float>>  — three floats as a
 *           binary Join tree, but physically packed into a single Long for
 *           zero-allocation inline-class storage.
 *
 * Pack layout (60 bits used):
 *   bits 40..59 → p (20-bit fixed-point, scaled by 2^20 − 1)
 *   bits 20..39 → d
 *   bits  0..19 → q
 */

inline class BudgetCoord(val packed: Long) {

    /** Priority — unpacked from bits 40..59. */
    val p: Float get() = ((packed ushr 40) and SCALE).toFloat() / SCALE

    /** Durability — unpacked from bits 20..39. */
    val d: Float get() = ((packed ushr 20) and SCALE).toFloat() / SCALE

    /** Quality — unpacked from bits 0..19. */
    val q: Float get() = (packed and SCALE).toFloat() / SCALE

    /** Geometric mean of p,d,q — the "radial" energy of the concept. */
    fun energy(): Float {
        val pv = p; val dv = d; val qv = q
        return if (pv <= 0f || dv <= 0f || qv <= 0f) 0f
        else (pv.toDouble().pow(1.0 / 3.0) *
              dv.toDouble().pow(1.0 / 3.0) *
              qv.toDouble().pow(1.0 / 3.0)).toFloat()
    }

    /** Return the packed Long (identity — storage IS the packed form). */
    fun pack(): Long = packed

    companion object {
        const val SCALE: Long = 0xFFFFFL  // 2^20 - 1

        /** Factory: pack three [0,1] floats into a BudgetCoord. */
        operator fun invoke(p: Float, d: Float, q: Float): BudgetCoord {
            val pBits = (p.coerceIn(0f, 1f) * SCALE).toLong()
            val dBits = (d.coerceIn(0f, 1f) * SCALE).toLong()
            val qBits = (q.coerceIn(0f, 1f) * SCALE).toLong()
            return BudgetCoord((pBits shl 40) or (dBits shl 20) or qBits)
        }

        /** Unpack a previously packed Long into a BudgetCoord. */
        fun unpack(packed: Long): BudgetCoord = BudgetCoord(packed)

        /** Full-budget convenience (p = d = q = 1). */
        fun full(): BudgetCoord = BudgetCoord((SCALE shl 40) or (SCALE shl 20) or SCALE)
    }
}

/**
 * ManifoldConcept<P>: a NARS3 concept located in the manifold.
 *
 *   angular = Long bitfield — identity coordinate.
 *             hamming(a.angular, b.angular) gives semantic distance.
 *   budget  = BudgetCoord — radial energy (priority × durability × quality).
 *   payload = P — the carried data (RowVec, belief, term, etc.).
 *
 * decay() and reinforce() preserve angular identity, adjusting only budget.
 */
class ManifoldConcept<out P>(
    val angular: Long,
    val budget: BudgetCoord,
    val payload: P,
) : RowVec {
    override val a: Int get() = 2
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int ->
            when (i) {
                0 -> angular j { ColumnMeta("angular", IOMemento.IoLong) }
                else -> budget.packed j { ColumnMeta("budget", IOMemento.IoLong) }
            }
        }
    fun decay(factor: Float): ManifoldConcept<P> = ManifoldConcept(
        angular = angular,
        budget = BudgetCoord(
            p = (budget.p * factor).coerceIn(0f, 1f),
            d = (budget.d * factor).coerceIn(0f, 1f),
            q = (budget.q * factor).coerceIn(0f, 1f),
        ),
        payload = payload,
    )

    fun reinforce(boost: Float): ManifoldConcept<P> = ManifoldConcept(
        angular = angular,
        budget = BudgetCoord(
            p = (budget.p + boost).coerceIn(0f, 1f),
            d = (budget.d + boost).coerceIn(0f, 1f),
            q = (budget.q + boost).coerceIn(0f, 1f),
        ),
        payload = payload,
    )
}

/** Factory for payload-free concepts (payload = Unit). */
fun ManifoldConcept(angular: Long, budget: BudgetCoord): ManifoldConcept<Unit> =
    ManifoldConcept(angular, budget, Unit)

/** Hamming distance between two angular coordinates (bit-level semantic distance). */
fun hamming(a: Long, b: Long): Int = (a xor b).countOneBits()

/**
 * NarsBag: a priority-ordered bag of ManifoldConcepts.
 *
 * Follows the BlockRowVec sealing pattern:
 *   - mutable()  → MutableNarsBag (insert freely)
 *   - seal()     → SealedNarsBag  (immutable view, new instance)
 *
 * recall()     returns all concepts as Series<Any>, sorted by energy descending.
 * recallNear() returns concepts within hamming maxDistance of a centroid.
 * budgetTensor() returns a Tensor<Float> of shape [n, 3] (p,d,q per concept).
 */
sealed class NarsBag {
    enum class State { MUTABLE, SEALED }

    abstract val state: State
    abstract fun recall(): Series<Any>
    abstract fun recallNear(centroid: Long, maxDistance: Int): Series<Any>
    abstract fun budgetTensor(): Tensor<Float>

    companion object {
        fun mutable(): MutableNarsBag = MutableNarsBag()
    }
}

class MutableNarsBag : NarsBag() {
    override val state: State = State.MUTABLE
    val concepts: MutableList<ManifoldConcept<*>> = mutableListOf()

    fun insert(concept: ManifoldConcept<*>) {
        concepts.add(concept)
    }

    /** Seal: returns a new SealedNarsBag with a snapshot sorted by energy descending. */
    fun seal(): NarsBag {
        val sorted = concepts.sortedByDescending { it.budget.energy() }
        return SealedNarsBag(sorted)
    }

    override fun recall(): Series<Any> = sortedSeries(concepts.sortedByDescending { it.budget.energy() })
    override fun recallNear(centroid: Long, maxDistance: Int): Series<Any> =
        sortedSeries(
            concepts.filter { hamming(it.angular, centroid) <= maxDistance }
                .sortedBy { hamming(it.angular, centroid) },
        )

    override fun budgetTensor(): Tensor<Float> = buildBudgetTensor(concepts)
}

class SealedNarsBag(private val concepts: List<ManifoldConcept<*>>) : NarsBag() {
    override val state: State = State.SEALED
    override fun recall(): Series<Any> = sortedSeries(concepts)
    override fun recallNear(centroid: Long, maxDistance: Int): Series<Any> =
        sortedSeries(
            concepts.filter { hamming(it.angular, centroid) <= maxDistance }
                .sortedBy { hamming(it.angular, centroid) },
        )

    override fun budgetTensor(): Tensor<Float> = buildBudgetTensor(concepts)
}

// ── recall Series helpers ─────────────────────────────────────────────────────

/** size as .first — lets tests use recall().first for the count */
val Series<*>.first: Int get() = a

private fun sortedSeries(list: List<*>): Series<Any> = list.size j { i -> list[i] as Any }

private fun buildBudgetTensor(concepts: List<ManifoldConcept<*>>): Tensor<Float> {
    val n = concepts.size
    val shape: Series<Int> = 2 j { i: Int -> if (i == 0) n else 3 }
    val accessor: (Series<Int>) -> Float = { s ->
        val ci = s.b(0); val di = s.b(1)
        val c = concepts[ci]
        when (di) {
            0 -> c.budget.p; 1 -> c.budget.d; else -> c.budget.q
        }
    }
    return shape j accessor
}

// ── totalRecall across a timeline of bags ─────────────────────────────────────

/**
 * Merge all concepts from a Series<NarsBag> timeline, return as a single
 * Series<Any> sorted by energy descending.
 */
fun Series<NarsBag>.totalRecall(): Series<Any> {
    val all = mutableListOf<ManifoldConcept<*>>()
    for (i in 0 until this.a) {
        val bag = this.b(i)
        val r = bag.recall()
        for (j in 0 until r.a) all.add(r.b(j) as ManifoldConcept<*>)
    }
    val sorted = all.sortedByDescending { it.budget.energy() }
    return sorted.size j { i -> sorted[i] as Any }
}
