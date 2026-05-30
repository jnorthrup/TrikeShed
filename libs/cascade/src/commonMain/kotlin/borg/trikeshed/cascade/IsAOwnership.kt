package borg.trikeshed.cascade

import borg.trikeshed.cursor.IsAEdge
import borg.trikeshed.cursor.IsALattice
import borg.trikeshed.cursor.TypeToken
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/* ── IsA Grouping Lattice ────────────────────────────────────────── *
 *
 * Hierarchical grouping as a TypeSubsumption lattice:
 *
 *   Entity IS-A Group3 IS-A Group0
 *   Entity IS-A Group2 IS-A Group0
 *   Entity IS-A Group1 IS-A Group0
 *   Entity IS-A Group0 (direct)
 *
 * Transitive closure gives all valid query paths through the cascade.
 * Each cascade view level is an IsAEdge traversal.
 */

object IsAOwnership {

    // ── Type pool indices ─────────────────────────────────────────────
    const val POOL_GROUP_0   = 0
    const val POOL_GROUP_1   = 1
    const val POOL_GROUP_2   = 2
    const val POOL_GROUP_3   = 3
    const val POOL_ENTITY    = 4

    val T_G0     = TypeToken(POOL_GROUP_0)
    val T_G1     = TypeToken(POOL_GROUP_1)
    val T_G2     = TypeToken(POOL_GROUP_2)
    val T_G3     = TypeToken(POOL_GROUP_3)
    val T_ENTITY = TypeToken(POOL_ENTITY)

    val NAMES = arrayOf("Group0", "Group1", "Group2", "Group3", "Entity")

    // ── IS-A edges ────────────────────────────────────────────────────

    private val EDGES = arrayOf(
        T_G1     edgeTo T_G0,    // Group1 IS-A Group0
        T_G2     edgeTo T_G0,    // Group2 IS-A Group0
        T_G3     edgeTo T_G0,    // Group3 IS-A Group0
        T_ENTITY edgeTo T_G3,    // Entity IS-A Group3
        T_ENTITY edgeTo T_G2,    // Entity IS-A Group2
        T_ENTITY edgeTo T_G1,    // Entity IS-A Group1
        T_ENTITY edgeTo T_G0,    // Entity IS-A Group0 (direct)
    )

    /** The grouping lattice as an IsALattice. */
    val lattice: IsALattice = IsALattice(EDGES.size j { i -> EDGES[i] })

    /** Which TypeToken is the leading key in each cascade view. */
    val VIEW_TYPE_TOKENS = arrayOf(
        T_ENTITY, // Level 1: byEntity
        T_G3,     // Level 2: byGroup3
        T_G2,     // Level 3: byGroup2
        T_G1,     // Level 4: byGroup1
        T_G0,     // Level 5: byGroup0
    )

    /** All ancestors of Entity — every valid view root. */
    val entityAncestors: Series<TypeToken> get() = lattice.supertypes(T_ENTITY)

    /** Does [root] own Entity transitively? */
    fun ownsEntity(root: TypeToken): Boolean {
        val ancestors = entityAncestors
        for (i in 0 until ancestors.a) {
            if (ancestors.b(i).poolIdx == root.poolIdx) return true
        }
        return false
    }
}
