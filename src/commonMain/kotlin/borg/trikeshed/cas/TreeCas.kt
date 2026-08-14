package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Tree-above-CAS — the fanout-k generalization of [LineCas.spineCid].
 *
 * LineCas already runs one CAS operator at every row:
 *
 *   row 0 (leaf)     contentCid = sha256(trimmed line bytes)
 *   row 1 (link)     linkedKey = stamp ‖ ':' ‖ contentHex
 *   row 2 (spine)    spineCid = sha256('\n'-joined linkedKeys)   — fanout-1 fold
 *
 * Every row is the same operator: sha256 over canonical bytes of the row
 * above's outputs. [spineCid] is the degenerate fanout-1 tree; [TreeCas]
 * lifts it to fanout FANOUT, giving a Merkle hierarchy OVER the CAS:
 *
 *   leaves      = one CID per LineNode.linkedKey
 *   branches    = one CID per FANOUT children ('\n'-joined, same separator
 *                 convention as spineCid so the two folds stay comparable)
 *   root        = CID of the branch row (single row once ≥1 branch exists)
 *
 * Because the operator is the CAS hash itself, the tree adds NO new identity
 * machinery — `MerkleFingerprint = ContentId` (CAS closed under composition;
 * git blob→tree is the existence proof). spineCid remains the canonical
 * whole-document fingerprint; the tree is a parallel dimension for subtree
 * identity and coarse-grained change location.
 *
 * What the hierarchy buys (retained from the spec exercise, landed here):
 *   - refinement ladder: funnel O(1) content probe → subtree CID O(log N)
 *     structural probe → spineCid O(1) whole-doc equality
 *   - structural sharing: chunks untouched by an edit keep their branch CID,
 *     so a moved block is ONE coarse atom, not N re-graded fine atoms
 *   - root recompute after an edit is O(changed path), not O(N)
 *
 * Interior branch nodes carry no trim/grade semantics — they are pure
 * structure. FANOUT is the tunable (same family as
 * [LineCas.NEIGHBOR_HEX_LEN]).
 */
object TreeCas {

    /** Children per branch node. 64 keeps a 3-level tree for ~16k lines. */
    const val FANOUT: Int = 64

    /**
     * Build the Merkle hierarchy over a spine.
     *
     * Returns level 0 = leaf CIDs (one per line), level 1..k = branch rows,
     * last level = the single root. A spine of ≤ FANOUT lines collapses to
     * [leafRow] + root == leaf row's single fold. Level indexing matches the
     * test contract: `tree[0]` leaves, `tree[last]` the root row (size 1
     * unless the level itself is a single node).
     */
    fun treeOf(spine: LineSpine): Series<Series<ContentId>> {
        if (spine.size == 0) {
            // CID of empty bytes — same convention as LineCas.spineCid's empty case.
            return emptyTree()
        }
        var row: Series<ContentId> = spine.size j { i: Int -> leafCid(spine[i]) }
        val rows = ArrayList<Series<ContentId>>()
        rows.add(row)
        while (row.size > 1) {
            row = foldRow(row)
            rows.add(row)
        }
        return rows.size j { i: Int -> rows[i] }
    }

    /** Root fingerprint of the hierarchy — the tree analogue of spineCid. */
    fun rootOf(spine: LineSpine): ContentId {
        if (spine.size == 0) return ContentId.of(ByteArray(0))
        val tree = treeOf(spine)
        val rootRow = tree[tree.size - 1]
        return rootRow[0]
    }

    /** Leaf atom identity: the line's linkedKey folded once by the CAS hash. */
    fun leafCid(node: LineNode): ContentId = ContentId.of(node.linkedKey.encodeToByteArray())

    /**
     * Fold one row into the next coarser row ('\n'-joined child bytes per
     * group of FANOUT, matching spineCid's separator convention).
     */
    private fun foldRow(row: Series<ContentId>): Series<ContentId> {
        val groups = (row.size + FANOUT - 1) / FANOUT
        return groups j { g: Int ->
            val sb = StringBuilder(FANOUT * 72)
            val start = g * FANOUT
            val end = minOf(row.size, start + FANOUT)
            for (i in start until end) {
                if (i > start) sb.append('\n')
                sb.append(row[i].hex)
            }
            ContentId.of(sb.toString().encodeToByteArray())
        }
    }

    private fun emptyTree(): Series<Series<ContentId>> = 1 j { _: Int ->
        1 j { _: Int -> ContentId.of(ByteArray(0)) }
    }
}
