package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tree-above-CAS invariants (RED-first; see TreeCas).
 *
 * Levels for n=200 leaves at FANOUT=64: [200 leaves, 4 branches, 1 root].
 */
class TreeCasTest {

    private fun doc(n: Int, prefix: String = "T"): String =
        (0 until n).joinToString("\n") { "$prefix${it.toString().padStart(4, '0')} unique salt xyz" }

    @Test
    fun determinism_and_sensitivity() {
        val a = LineCas.spine(doc(200))
        val b = LineCas.spine(doc(200))
        assertEquals(TreeCas.rootOf(a), TreeCas.rootOf(b))

        val mutated = LineCas.spine(doc(200).replace("T0100", "T0mutated"))
        assertNotEquals(TreeCas.rootOf(a), TreeCas.rootOf(mutated))
    }

    @Test
    fun structural_sharing_before_mutation_point() {
        val base = LineCas.spine(doc(200))
        val mutatedText = doc(200).lineSequence()
            .mapIndexed { i, line -> if (i == 100) "$line MUT" else line }
            .joinToString("\n")
        val mut = LineCas.spine(mutatedText)

        val t0 = TreeCas.treeOf(base)
        val t1 = TreeCas.treeOf(mut)

        assertEquals(3, t0.size) // 200 leaves -> 4 branches -> 1 root
        // chunk 0 (lines 0..63) untouched -> identical branch CID (structural sharing)
        assertEquals(t0[1][0], t1[1][0])
        // chunk 1 contains line 100 -> differs
        assertNotEquals(t0[1][1], t1[1][1])
        // root differs
        assertNotEquals(t0[2][0], t1[2][0])
    }

    @Test
    fun block_move_is_coarse() {
        val baseText = doc(200)
        val lines = baseText.lineSequence().toMutableList()
        val block = lines.subList(130, 140).toList() // chunk 2 region
        val moved = lines.toMutableList().apply {
            removeAll(block)
            addAll(70, block) // into chunk 1 region
        }

        val t0 = TreeCas.treeOf(LineCas.spine(baseText))
        val t1 = TreeCas.treeOf(LineCas.spine(moved.joinToString("\n")))

        // chunks entirely before both source and target regions share CIDs
        assertEquals(t0[1][0], t1[1][0])
        // the move is visible at the root
        assertNotEquals(t0[2][0], t1[2][0])
    }

    @Test
    fun closure_and_degenerate_cases() {
        val spine = LineCas.spine(doc(10))
        // MerkleFingerprint IS ContentId — CAS closed under composition
        val root: ContentId = TreeCas.rootOf(spine)
        // but the fanout-k fold differs from spineCid (the fanout-1 linear fold)
        assertNotEquals(LineCas.spineCid(spine), root)

        // single-leaf hierarchy: root IS the leaf CID
        val single = LineCas.spine("only line")
        assertEquals(TreeCas.leafCid(single[0]), TreeCas.rootOf(single))

        // empty spine: CID of empty bytes, matching spineCid's convention
        assertEquals(
            LineCas.spineCid(LineCas.spine("")),
            TreeCas.rootOf(LineCas.spine("")),
        )
    }
}
