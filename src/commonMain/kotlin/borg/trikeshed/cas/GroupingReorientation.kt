package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.narsese.AngularCodec

/**
 * GroupingReorientation — post-hoc semantic re-orientation over code-prefix groups (Step G).
 *
 * Generalizes the tree's one designed LLM seam — `FunnelResidualMerge.ConflictPost` /
 * `ResolutionRoutine` — from merge conflicts to GROUPING PROPOSALS: relabel / split / merge a
 * code-prefix group. A proposal is a self-contained document (everything a resolver needs
 * without re-probing the index); enactment is deterministic and evidence-preserving: the
 * corrected coordinate re-mints through [AngularCodec], and `reviseInto` merges with prior
 * belief — eviction is attention-zero, never history loss, so relabelling cannot destroy
 * the past.
 *
 * Bridges: `recallNear`-style hamming recall over ring centroids surfaces hamming-near /
 * path-distant pairs; an accepted bridge is a typed link document (cids both ends, kind,
 * provenance) that future ingest and the synthesis chain traverse.
 */
object GroupingReorientation {

    /** A code-prefix group: the ring8 neighborhood and its member doc cids. */
    data class Group(val ring8: Int, val members: List<ContentId>)

    /** Evidence for one proposal — Claim Checks (cids), never inlined content. */
    data class ProposalEvidence(
        val sampleMembers: List<ContentId>,
        val hammingToNeighbors: List<Pair<Int, Int>>, // (other ring8, hamming between ring centroids)
    )

    /**
     * The grouping proposal post — the [FunnelResidualMerge.ConflictPost] analogue.
     * Self-contained: current label, the group, sampled member cids, and the
     * hamming-neighborhood give the resolver everything it needs to decide.
     */
    data class GroupingPost(
        val group: Group,
        val currentLabel: String,
        val proposedLabel: String?,
        val proposedSplit: List<Group>?,
        val proposedMergeWith: Group?,
        val evidence: ProposalEvidence,
        val origin: String,
    )

    /** Deterministic resolution of one [GroupingPost] — same contract as [FunnelResidualMerge.ConflictResolution]. */
    sealed interface GroupingResolution {
        /** Rename the group's label; the code (membership) is unchanged. */
        data class Relabel(val label: String) : GroupingResolution
        /** Split the group at the given ring-8 boundary into two sub-groups. */
        data class Split(val lowRing8: Int, val highRing8: Int) : GroupingResolution
        /** Merge this group with [other]; the surviving code prefix is [intoRing8]. */
        data class Merge(val otherRing8: Int, val intoRing8: Int) : GroupingResolution
        /** Leave the grouping alone. */
        data object Reject : GroupingResolution
    }

    /** Deterministic resolver routine: same post → same resolution, no guessing. */
    fun interface ResolutionRoutine {
        fun resolve(post: GroupingPost): GroupingResolution
    }

    /**
     * Enact a [GroupingResolution] over the CAS: the resolution document IS the
     * relabel/split/merge (label docs are value projections of these documents).
     * Returns the resolution document's cid — content-addressed, reproducible.
     */
    fun enact(cas: CasStore, post: GroupingPost, resolution: GroupingResolution): ContentId {
        val body = buildString {
            append("grouping-resolution-v1\n")
            append("group.ring8=").append(post.group.ring8).append('\n')
            append("group.size=").append(post.group.members.size).append('\n')
            when (resolution) {
                is GroupingResolution.Relabel -> {
                    append("action=relabel\nlabel=").append(resolution.label).append('\n')
                }
                is GroupingResolution.Split -> {
                    append("action=split\nlow=").append(resolution.lowRing8).append('\n')
                    append("high=").append(resolution.highRing8).append('\n')
                }
                is GroupingResolution.Merge -> {
                    append("action=merge\nother=").append(resolution.otherRing8).append('\n')
                    append("into=").append(resolution.intoRing8).append('\n')
                }
                GroupingResolution.Reject -> append("action=reject\n")
            }
            append("origin=").append(post.origin).append('\n')
        }
        return cas.put(body.encodeToByteArray())
    }

    /**
     * Bridge candidates: ring centroids within [maxHamming] of [ring8]'s centroid but
     * never adjacent in the positional path — hamming-near, path-distant.
     */
    fun bridgeCandidates(centroids: Map<Int, Long>, ring8: Int, maxHamming: Int, exclude: Set<Int> = emptySet()): List<Int> {
        val center = centroids[ring8] ?: return emptyList()
        return centroids.entries
            .filter { (r, c) -> r != ring8 && r !in exclude && (c xor center).countOneBits() <= maxHamming }
            .map { it.key }
            .sortedBy { (centroids[it]!! xor center).countOneBits() }
    }

    /** An accepted bridge, as its document bytes (typed link, cids both ends). */
    fun bridgeDocument(from: ContentId, to: ContentId, kind: String, provenance: String): ContentId =
        ContentId.of("bridge-v1|from=${from.value}|to=${to.value}|kind=$kind|by=$provenance".encodeToByteArray())
}
