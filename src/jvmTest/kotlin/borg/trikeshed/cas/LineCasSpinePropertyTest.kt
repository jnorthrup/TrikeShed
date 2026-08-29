package borg.trikeshed.cas

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Spine-stability properties for [LineCas]: same content → same spineCid, and
 * a single-line change perturbs ONLY the NeighborStamp neighborhood — the
 * mutated node's contentCid, the previous node's nextHex, and the next node's
 * prevHex (each a NEIGHBOR_HEX_LEN prefix of the new contentCid, per
 * [LineCas.neighborHex]). Every other node keeps a byte-identical linkedKey,
 * which is what makes moved/unchanged blocks cheap for the funnel pipeline.
 */
class LineCasSpinePropertyTest {

    private fun doc(n: Int, prefix: String = "S"): List<String> =
        (0 until n).map { "$prefix${it.toString().padStart(4, '0')} unique salt xyz" }

    /** Trim + blank-line drop make spineCid a content fingerprint, not a formatting one. */
    @Test
    fun sameContentSameSpineCidUnderFormattingNoise() {
        val lines = doc(30)
        val plain = lines.joinToString("\n")
        val noisy = lines.joinToString("\n") { "  $it\t" } + "\n\n   \n"
        assertEquals(
            LineCas.spineCid(LineCas.spine(plain)),
            LineCas.spineCid(LineCas.spine(noisy)),
        )
    }

    /**
     * Neighborhood-perturbation property, 5 seeded rounds: mutating line `at`
     * changes exactly node `at`'s contentCid plus the two adjacent stamps —
     * nodes at-1 / at+1 keep their content and their far-side hex, and every
     * node outside [at-1, at+1] keeps its full linkedKey. The mutated node
     * itself keeps its stamp (its neighbors did not change).
     */
    @Test
    fun singleLineChangePerturbsOnlyItsNeighborhood() {
        val rnd = Random(42)
        val n = 32
        val lines = doc(n)
        val base = LineCas.spine(lines.joinToString("\n"))
        assertEquals(n, base.size)

        repeat(5) { round ->
            val at = rnd.nextInt(2, n - 2)
            val mutated = lines.toMutableList().apply { this[at] = "MUT$round line body" }
            val spine = LineCas.spine(mutated.joinToString("\n"))
            assertEquals(base.size, spine.size)

            val newCid = spine[at].contentCid
            assertNotEquals(base[at].contentCid, newCid, "mutated line $at must change contentCid")
            assertEquals(base[at].stamp, spine[at].stamp, "mutated node keeps its neighborhood context")

            for (j in 0 until n) {
                when (j) {
                    at -> Unit // asserted above
                    at - 1 -> {
                        assertEquals(base[j].contentCid, spine[j].contentCid)
                        assertEquals(base[j].prevHex, spine[j].prevHex)
                        assertEquals(
                            LineCas.neighborHex(newCid), spine[j].nextHex,
                            "left neighbor's nextHex is the new cid's prefix",
                        )
                    }
                    at + 1 -> {
                        assertEquals(base[j].contentCid, spine[j].contentCid)
                        assertEquals(base[j].nextHex, spine[j].nextHex)
                        assertEquals(
                            LineCas.neighborHex(newCid), spine[j].prevHex,
                            "right neighbor's prevHex is the new cid's prefix",
                        )
                    }
                    else -> assertEquals(
                        base[j].linkedKey, spine[j].linkedKey,
                        "line $j is outside the neighborhood of $at — linkedKey must be identical",
                    )
                }
            }
            // The document fingerprint is sensitive to the one changed linkedKey.
            assertNotEquals(LineCas.spineCid(base), LineCas.spineCid(spine))
        }
    }

    /**
     * Edge semantics from [LineCas.neighborHex]: a missing neighbor stamps as
     * the [LineCas.EDGE_HEX] sentinel, not a hash of empty — so mutating an
     * edge line perturbs only its single inboard neighbor.
     */
    @Test
    fun edgeLinesCarryEdgeSentinel() {
        val spine = LineCas.spine("first\nmiddle\nlast")
        assertEquals(LineCas.EDGE_HEX, spine[0].prevHex)
        assertEquals(LineCas.EDGE_HEX, spine[spine.size - 1].nextHex)

        val single = LineCas.spine("only")
        assertEquals(LineCas.EDGE_HEX, single[0].prevHex)
        assertEquals(LineCas.EDGE_HEX, single[0].nextHex)

        val mut = LineCas.spine("FIRST2\nmiddle\nlast")
        assertEquals(spine[2].linkedKey, mut[2].linkedKey, "far line untouched by edge mutation")
        assertEquals(spine[1].contentCid, mut[1].contentCid)
        assertEquals(LineCas.neighborHex(mut[0].contentCid), mut[1].prevHex)
        assertEquals(LineCas.EDGE_HEX, mut[0].prevHex, "edge stays edge after mutation")
    }
}
