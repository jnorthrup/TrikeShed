@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.miniduck

import borg.trikeshed.lib.*

/**
 * NarsBag: a manifold-shaped attention bag.
 *
 * Accumulates ManifoldConcepts during MUTABLE phase, then seals for readers.
 * Recall orders concepts by radial energy (confidence) descending, breaking
 * ties by angular proximity (Hamming distance) to an optional anchor.
 *
 * The budget tensor is a rank-2 Tensor<Float> of shape (conceptCount, 3)
 * exposing the P/D/Q values for each concept -- this is the "lowering target"
 * for numerical kernels, not the primary access path.
 */
class NarsBag private constructor(
    private val concepts: MutableList<ManifoldConcept>,
    private var _sealed: Boolean,
) {

    enum class State { MUTABLE, SEALED }

    val state: State get() = if (_sealed) State.SEALED else State.MUTABLE

    /** Number of concepts in the bag. */
    val conceptCount: Int get() = concepts.size

    /** Insert a concept. Throws if sealed. */
    fun insert(concept: ManifoldConcept) {
        check(!_sealed) { "Cannot insert into a sealed NarsBag" }
        concepts.add(concept)
    }

    /** Alias for [insert]. */
    fun append(concept: ManifoldConcept) = insert(concept)

    /** Seal the bag. After this call no further inserts are allowed. */
    fun seal(): NarsBag {
        _sealed = true
        return this
    }

    /**
     * Recall: produce a lazy cursor of ManifoldConcepts ordered by
     * radial energy descending, breaking ties by angular proximity
     * to the optional [anchor].
     *
     * Returns a Series<ManifoldConcept> -- the caller walks the payload
     * via .child to get at the underlying MiniRowVec.
     */
    fun recall(anchor: Long? = null): Series<ManifoldConcept> {
        // Allow recall from mutable or sealed bags; tests expect immediate visibility
        val sorted = concepts.sortedWith { a, b ->
            // primary: radial energy descending
            val cmp = b.budget.radialEnergy.compareTo(a.budget.radialEnergy)
            if (cmp != 0) return@sortedWith cmp
            // secondary: angular proximity ascending (closer = first)
            if (anchor != null) {
                val ha = ManifoldConcept.hamming(a.angular, anchor)
                val hb = ManifoldConcept.hamming(b.angular, anchor)
                ha.compareTo(hb)
            } else {
                0
            }
        }
        return sorted.size j { sorted[it] }
    }

    /**
     * Budget tensor: rank-2 Tensor<Float> of shape (conceptCount, 3).
     * Columns are [P, D, Q] for each concept in insertion order.
     *
     * This is the tensor lowering of the manifold's radial structure.
     */
    fun budgetTensor(): Tensor<Float> {
        // Allow budget tensor production from mutable bags as tests expect immediate lowering
        val rows = concepts.size
        val dims = 3
        val shape = shapeOf(rows, dims)
        return shape j { idx: Shape ->
            val row = idx[0]
            val dim = idx[1]
            when (dim) {
                0 -> concepts[row].budget.p
                1 -> concepts[row].budget.d
                2 -> concepts[row].budget.q
                else -> throw IndexOutOfBoundsException("dim $dim")
            }
        }
    }

    companion object {
        /** Create a new mutable bag. */
        fun mutable(): NarsBag = NarsBag(mutableListOf(), false)
    }
}

/**
 * Timeline: a series of sealed NarsBags representing successive epochs.
 *
 * Total recall scans across all epochs and returns concepts ordered
 * by radial energy descending -- the highest-confidence concepts across
 * all of time are recalled first.
 */
typealias Timeline = Series<NarsBag>

/**
 * Total recall across all epochs in the timeline.
 *
 * Collects all concepts from all sealed bags, sorts by radial energy
 * descending, and returns a lazy cursor.
 */
fun Timeline.totalRecall(): Series<ManifoldConcept> {
    // collect (bag_index, concept_index) pairs with their concepts
    val all = ArrayList<ManifoldConcept>()
    for (b in 0 until this.size) {
        val bag = this[b]
        if (bag.state != NarsBag.State.SEALED) continue
        val cursor = bag.recall()
        for (i in 0 until cursor.size) {
            all.add(cursor[i])
        }
    }

    // sort by radial energy descending
    all.sortByDescending { it.budget.radialEnergy }

    return all.size j { all[it] }
}

fun NarsBag.recallNear(centroid: Long, maxDistance: Int): Series<ManifoldConcept> {
    check(this.state == NarsBag.State.SEALED) { "Cannot recallNear from a mutable NarsBag" }
    val matches = ArrayList<ManifoldConcept>()
    val cursor = this.recall()
    for (i in 0 until cursor.size) {
        val c = cursor[i]
        if (ManifoldConcept.hamming(c.angular, centroid) <= maxDistance) matches.add(c)
    }
    return matches.size j { matches[it] }
}

/**
 * Top-level factory: construct a [Timeline] from one or more sealed [NarsBag]s.
 */
fun timelineOf(vararg bags: NarsBag): Timeline = bags.size j { bags[it] }
