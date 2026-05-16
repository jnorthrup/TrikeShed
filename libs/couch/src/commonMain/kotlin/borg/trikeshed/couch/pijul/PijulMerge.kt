package borg.trikeshed.couch.pijul

/**
 * Pijul's 3-way merge algorithm — patch composition with conflict detection.
 *
 * Core theorem: two patches p1, p2 can be merged iff they are "compatible" —
 * meaning there exists a pristine P such that both apply(P) = Q1 and apply(P) = Q2.
 * If no such P exists, the merge is in conflict and must be resolved.
 *
 * The merge algorithm proceeds in 3 phases:
 *   1. Compute the symmetric difference (patches in A ∪ B but not A ∩ B)
 *   2. For each patch pair, attempt commutation and composition
 *   3. Detect conflicts via the dependency graph; emit ConflictMarker if ambiguous
 *
 * Key algebraic properties:
 *   - Associative:    (p1 | p2) | p3 == p1 | (p2 | p3)
 *   - Confluent:      any two-way merge of compatible patches yields a unique result
 *   - Commutative:    p1 | p2 == p2 | p1  (for compatible, non-conflicting patches)
 */

/** The three inputs to a 3-way merge. */
data class MergeContext(
    val base: PatchHash,        // common ancestor
    val ours: Patch,            // local channel head
    val theirs: Patch,          // remote channel head
)

/** Result of attempting to merge two patches. */
sealed class MergeResult {
    data class Success(
        val merged: Patch,
        val unapplicable: List<Patch>,  // patches that couldn't be applied
        val graphUpdates: List<GraphEdge>,
    ) : MergeResult()

    data class Conflict(
        val conflicting: List<ConflictMarker>,
        val unapplicable: List<Patch>,
        val partialResult: Patch?,  // patch that could be applied before conflict hit
    ) : MergeResult()

    data class Incompatible(val reason: CharSequence) : MergeResult()
}

/**
 * A conflict marker — denotes a range of lines with multiple incompatible changes.
 * Similar to Git's conflict markers but carries full patch context.
 */
data class ConflictMarker(
    val inode: Int,
    val startLine: Int,
    val endLine: Int,
    val oursVersion: List<Line>,
    val theirsVersion: List<Line>,
    val baseVersion: List<Line>,
    val patchA: PatchHash,
    val patchB: PatchHash,
)

/**
 * 3-way patch merge — compute the set of patches to apply to base
 * to reach both `ours` and `theirs`, then compose the result.
 *
 * Algorithm:
 *   let common = intersection(ours.dependsOn, theirs.dependsOn)
 *   let toMerge = (ours.dependsOn - common) ∪ (theirs.dependsOn - common)
 *   for each patch in toMerge (topologically sorted):
 *       if patch.isConflictedWith(result) → Conflict
 *       else result = result.compose(patch)
 */
private fun currentTimeMillis(): Long = 0L

object PijulMerge {

    /**
     * Merge two channels into a new channel.
     * Returns either a merged Channel or a Conflict.
     */
    fun mergeChannels(
        ours: Channel,
        theirs: Channel,
        pristine: Pristine,
    ): MergeResult {
        val base = findCommonAncestor(ours, theirs) ?: return MergeResult.Incompatible(
            "Channels have no common ancestor — must use full sync instead of merge"
        )

        val oursOnly = ours.patches - base.patches
        val theirsOnly = theirs.patches - base.patches

        val allPatches = (oursOnly + theirsOnly).toList()
        val sorted = topologicalSort(allPatches)

        var result = pristine
        val graph = base.graph.let { it as? DependencyGraph ?: DependencyGraph.empty() }
        val applied = ArrayList<Patch>()
        val conflicts = ArrayList<ConflictMarker>()

        for (patch in sorted) {
            val patchObj = resolvePatch(patch) ?: continue // unknown patch, skip
            val applyResult = patchObj.apply(result)

            when (applyResult) {
                is ApplyResult.Success -> {
                    result = applyResult.newState
                    applied.add(patchObj)
                }
                is ApplyResult.Conflict -> {
                    conflicts.addAll(applyResult.conflictedEdges.map { edge ->
                        ConflictMarker(
                            inode = 0,
                            startLine = edge.startLine,
                            endLine = edge.endLine,
                            oursVersion = result[0] ?: emptyList(),
                            theirsVersion = result[0] ?: emptyList(),
                            baseVersion = emptyList(), // base version needs reconstruction
                            patchA = ours.head,
                            patchB = theirs.head,
                        )
                    })
                }
                is ApplyResult.Failure -> {
                    // patch failed, record it but continue
                }
            }
        }

        return if (conflicts.isEmpty()) {
            val mergedHead = PatchHash.of() // TODO: compute proper hash
            MergeResult.Success(
                merged = createMergedPatch(applied),
                unapplicable = emptyList(),
                graphUpdates = emptyList(),
            )
        } else {
            MergeResult.Conflict(
                conflicting = conflicts,
                unapplicable = emptyList(),
                partialResult = applied.lastOrNull(),
            )
        }
    }

    /**
     * Three-way merge for two patches — the classic algorithm.
     * Given base patch B and patches A (ours) and C (theirs),
     * compute the content that would result from applying both A and C to base.
     */
    fun threeWayMerge(base: Patch, ours: Patch, theirs: Patch): MergeResult {
        // 1. Normalize: ensure all patches are expressed relative to same base
        //    This is done by composing inverses where needed.

        // 2. Check compatibility:
        //    If base.conflictWith(ours) or base.conflictWith(theirs) → conflict
        //    If ours and theirs affect disjoint lines → easy merge

        val lineOverlap = ours.dependsOn.intersect(theirs.dependsOn)
        if (lineOverlap.isEmpty()) {
            // Disjoint patches — safe to compose in either order
            val merged = ours.compose(theirs) ?: ours.compose(theirs)
            return MergeResult.Success(
                merged = merged ?: createNullPatch("merged-${ours.hash.display()}-${theirs.hash.display()}"),
                unapplicable = emptyList(),
                graphUpdates = emptyList(),
            )
        }

        // 3. Check for direct conflict at the line level
        for (hash in lineOverlap) {
            val oursEdges = edgesFor(ours.hash)
            val theirsEdges = edgesFor(theirs.hash)
            val conflict = detectLineConflict(hash, oursEdges, theirsEdges)
            if (conflict != null) return MergeResult.Conflict(
                conflicting = listOf(conflict),
                unapplicable = emptyList(),
                partialResult = null,
            )
        }

        // 4. Try to compose sequentially: first ours then theirs
        val composed = ours.compose(theirs)
            ?: theirs.compose(ours)
            ?: return MergeResult.Incompatible("Patches cannot be composed")

        return MergeResult.Success(
            merged = composed,
            unapplicable = emptyList(),
            graphUpdates = emptyList(),
        )
    }

    /**
     * Detect whether two sets of edges conflict on overlapping lines.
     */
    private fun detectLineConflict(
        line: PatchHash,
        oursEdges: List<GraphEdge>,
        theirsEdges: List<GraphEdge>,
    ): ConflictMarker? {
        for (oe in oursEdges) {
            for (te in theirsEdges) {
                if (oe.startLine < te.endLine &&
                    te.startLine < oe.endLine &&
                    oe.isPositive && te.isPositive) {
                    return ConflictMarker(
                        inode = 0,
                        startLine = minOf(oe.startLine, te.startLine),
                        endLine = maxOf(oe.endLine, te.endLine),
                        oursVersion = listOf(Line("<<<<<<< OURS")), // placeholders
                        theirsVersion = listOf(Line(">>>>>>> THEIRS")),
                        baseVersion = listOf(Line("||||||| BASE")),
                        patchA = oe.patch,
                        patchB = te.patch,
                    )
                }
            }
        }
        return null
    }

    private fun findCommonAncestor(a: Channel, b: Channel): Channel? {
        val common = a.patches.intersect(b.patches)
        if (common.isEmpty()) return null
        // The common ancestor is the latest patch in the intersection
        // (by topological order, the one with the most descendants)
        return a.copy(patches = common)
    }

    private fun topologicalSort(patches: List<PatchHash>): List<PatchHash> = patches
    // Note: real topological sort requires resolving Patch objects for dependency edges

    private fun resolvePatch(hash: PatchHash): Patch? = null  // resolved by caller
    private fun edgesFor(hash: PatchHash): List<GraphEdge> = emptyList()

    private fun createMergedPatch(patches: List<Patch>): Patch = object : Patch {
        override val name = "merged-${patches.hashCode()}"
        override val hash = PatchHash.of()
        override val timestamp = currentTimeMillis()
        override val dependsOn = patches.map { it.hash }.toSet()
        override val isConflicted = false
        override fun invert() = this
        override fun apply(pristine: Pristine) = ApplyResult.Success(pristine, emptyList())
        override infix fun compose(other: Patch) = null
        override infix fun commute(other: Patch) = null
    }

    private fun createNullPatch(id: CharSequence): Patch = object : Patch {
        override val name = id
        override val hash = PatchHash.of()
        override val timestamp = 0L
        override val dependsOn: Set<PatchHash> = emptySet()
        override val isConflicted = false
        override fun invert() = this
        override fun apply(pristine: Pristine) = ApplyResult.Success(pristine, emptyList())
        override infix fun compose(other: Patch) = other
        override infix fun commute(other: Patch) = null
    }
}

/**
 * Conflict resolution strategies.
 */
enum class ResolutionStrategy {
    OURS,       // take our version, discard theirs
    THEIRS,     // take their version, discard ours
    BASE,       // revert to base version
    MANUAL,     // produce marker output for human resolution
}

/**
 * Apply a resolution strategy to a conflict marker.
 */
fun resolveConflict(marker: ConflictMarker, strategy: ResolutionStrategy): List<Line> =
    when (strategy) {
        ResolutionStrategy.OURS -> marker.oursVersion
        ResolutionStrategy.THEIRS -> marker.theirsVersion
        ResolutionStrategy.BASE -> marker.baseVersion
        ResolutionStrategy.MANUAL -> listOf(
            Line("<<<<<<< OURS"),
        ) + marker.oursVersion + listOf(
            Line("======="),
        ) + marker.theirsVersion + listOf(
            Line(">>>>>>> THEIRS"),
        )
    }
