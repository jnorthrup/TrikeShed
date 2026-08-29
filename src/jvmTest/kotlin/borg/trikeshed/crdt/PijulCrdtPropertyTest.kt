package borg.trikeshed.crdt

import borg.trikeshed.patch.Blake3Hash
import borg.trikeshed.pijul.Change
import borg.trikeshed.pijul.Patch
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Property suite for [PijulCrdt] — the commutation/convergence laws its KDoc
 * claims ("Patches that touch different regions of the same file commute with
 * no conflict resolution"), checked against the implementation. The
 * escape-velocity premise (docs/escape-velocity.md) stands on these laws.
 *
 * Coordinate convention, read from code (not from the KDoc): Change.Insert.pos
 * and Change.Delete.pos are CHARACTER offsets into the rendered document —
 * findAttachIndex/findRangeIndices binary-search cumulativeLen, which
 * accumulates content.length per vertex. The attach rule is "after the LAST
 * vertex whose start offset <= pos"; an insert never splits a vertex.
 *
 * Counterexamples stay in this file under @Ignore — each one is the record of
 * a law that does NOT hold as implemented, with the code-level cause traced in
 * its KDoc. Unignore after patches address stable vertex identities instead of
 * live rendered offsets.
 */
class PijulCrdtPropertyTest {

    private fun patchOf(tag: String, vararg changes: Change): Patch =
        Patch(Blake3Hash.hash(tag.encodeToByteArray()), changes.toList(), emptyList())

    /** Seed a fresh CRDT with [lines], one vertex per line, at true char offsets. */
    private fun seeded(lines: List<String>): PijulCrdt {
        val crdt = PijulCrdt()
        var offset = 0
        val changes = lines.map { line ->
            val c = Change.Insert(offset, line + "\n")
            offset += line.length + 1
            c
        }
        crdt.apply(Patch(Blake3Hash.hash("seed".encodeToByteArray()), changes, emptyList()))
        return crdt
    }

    private val base = listOf("A", "B", "C", "D", "E")

    /** Char-offset seeding renders the lines back in order — the baseline sanity gate. */
    @Test
    fun seedRendersInOrder() {
        assertEquals("A\nB\nC\nD\nE\n", seeded(base).render())
    }

    /**
     * Determinism — the half of convergence that DOES hold: replicas receiving
     * the same patch sequence in the SAME order render identically. 24 seeded
     * random single-change patches, two replicas, fixed seed.
     */
    @Test
    fun sameOrderReplicasRenderIdentically() {
        val rnd = Random(42)
        val patches = (0 until 24).map { n ->
            val pos = rnd.nextInt(0, 12)
            if (rnd.nextBoolean()) patchOf("r$n", Change.Insert(pos, "n$n\n"))
            else patchOf("r$n", Change.Delete(pos, 1 + rnd.nextInt(2)))
        }
        val r1 = seeded(base).run { for (p in patches) apply(p); render() }
        val r2 = seeded(base).run { for (p in patches) apply(p); render() }
        assertEquals(r1, r2)
    }

    /**
     * KNOWN DIVERGENT — kept as the counterexample record for the class KDoc's
     * commutation claim.
     *
     * Two independent single-insert patches at distinct char offsets applied in
     * both orders do NOT render identically. Cause (code-level): findAttachIndex
     * binary-searches the LIVE cumulative offsets, so whichever patch applies
     * first shifts the char coordinates the second patch's pos is interpreted
     * against. Positions address rendered offsets, not stable vertex identities,
     * so two patches commute only when neither shifts the other's attach vertex.
     *
     * Traced on base A\nB\nC\nD\nE (2-char lines, starts 0,2,4,6,8):
     *   p1 = Insert(2,"X\n"), p2 = Insert(6,"Y\n")
     *   p1 then p2 → "A\nB\nX\nC\nY\nD\nE\n"  (X shifted D's start to 8; p2 lands after C)
     *   p2 then p1 → "A\nB\nX\nC\nD\nY\nE\n"  (p2 lands after D at its unshifted start 6)
     */
    @Ignore
    @Test
    fun independentInsertsCommute() {
        val p1 = patchOf("p1", Change.Insert(2, "X\n"))
        val p2 = patchOf("p2", Change.Insert(6, "Y\n"))
        val ab = seeded(base).run { apply(p1); apply(p2); render() }
        val ba = seeded(base).run { apply(p2); apply(p1); render() }
        assertEquals(ab, ba)

        // Seeded sweep: every pair of independent inserts must commute.
        val rnd = Random(42)
        repeat(20) { n ->
            val posA = rnd.nextInt(0, 10)
            var posB = rnd.nextInt(0, 10)
            if (posB == posA) posB = (posA + 3) % 10
            val pa = patchOf("a$n", Change.Insert(posA, "a$n\n"))
            val pb = patchOf("b$n", Change.Insert(posB, "b$n\n"))
            val r1 = seeded(base).run { apply(pa); apply(pb); render() }
            val r2 = seeded(base).run { apply(pb); apply(pa); render() }
            assertEquals(r1, r2, "insert@$posA / insert@$posB must commute")
        }
    }

    /**
     * KNOWN DIVERGENT — N replicas receiving the same patch SET in different
     * interleavings do not converge; each interleaving reads its positions
     * against a differently-shifted document (same cause as
     * [independentInsertsCommute], and worse for deletes: the delete range
     * covers whichever vertices currently occupy those chars).
     *
     * Traced: on [0,1,2] the Delete(6,2) tombstones the freshly inserted Q;
     * on [2,1,0] it tombstones master's D → "A\nP\nB\nC\nD\nE\n" vs
     * "A\nP\nB\nC\nQ\nE\n".
     */
    @Ignore
    @Test
    fun replicasConvergeAcrossInterleavings() {
        val patches = listOf(
            patchOf("q0", Change.Insert(0, "P\n")),
            patchOf("q1", Change.Insert(4, "Q\n")),
            patchOf("q2", Change.Delete(6, 2)),
        )
        val perms = listOf(
            listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
        )
        val renders = perms.map { perm ->
            seeded(base).run { for (i in perm) apply(patches[i]); render() }
        }.toSet()
        assertEquals(1, renders.size, "all interleavings must converge to one render, got $renders")
    }

    /**
     * Tombstone contract, stated from code (class KDoc + findRangeIndices):
     *
     *  1. Deletion tombstones content without removing the vertex — the vertex
     *     stays in the alive order at zero length ("preserving graph stability").
     *  2. Delete granularity is the WHOLE vertex: findRangeIndices selects every
     *     vertex whose [start, end) char range intersects [pos, pos+length), and
     *     nothing ever splits a vertex — a 1-char overlap kills the entire atom.
     */
    @Test
    fun deleteTombstonesWholeOverlappedVertices() {
        // Aligned delete: [2,4) covers exactly B.
        assertEquals("A\nC\nD\nE\n", seeded(base).run { apply(patchOf("d0", Change.Delete(2, 2))); render() })
        // Partial overlap: [1,2) touches only A's trailing newline — whole A dies.
        assertEquals("B\nC\nD\nE\n", seeded(base).run { apply(patchOf("d1", Change.Delete(1, 1))); render() })
        // Boundary-spanning [3,5) clips B and C — both whole atoms die for 2 chars deleted.
        assertEquals("A\nD\nE\n", seeded(base).run { apply(patchOf("d2", Change.Delete(3, 2))); render() })
    }

    /**
     * Delete-then-edit, per the implementation's contract: the tombstoned vertex
     * keeps its slot at zero length, so it shares its start offset with the next
     * live vertex; findAttachIndex picks the LAST vertex whose start <= pos, so
     * an insert addressed at a tombstoned offset attaches AFTER the next live
     * vertex — the new content lands past the surviving right neighbor, not in
     * the dead line's place.
     */
    @Test
    fun insertAtTombstonedOffsetAttachesAfterNextLiveVertex() {
        val r = seeded(listOf("A", "B", "C")).run {
            apply(patchOf("del", Change.Delete(2, 2)))     // tombstone B
            apply(patchOf("ins", Change.Insert(2, "X\n"))) // addressed at B's old start
            render()
        }
        assertEquals("A\nC\nX\n", r)
    }

    /**
     * KNOWN DIVERGENT — a delete and a concurrent insert into the deleted
     * region do not commute:
     *
     *   ins then del → "A\nX\nC\n"  (X attached after live B, then B tombstoned)
     *   del then ins → "A\nC\nX\n"  (B already zero-length: X attaches after C,
     *                                the last vertex sharing start offset 2)
     */
    @Ignore
    @Test
    fun deleteAndConcurrentInsertCommute() {
        val del = patchOf("del", Change.Delete(2, 2))
        val ins = patchOf("ins", Change.Insert(2, "X\n"))
        val small = listOf("A", "B", "C")
        val insThenDel = seeded(small).run { apply(ins); apply(del); render() }
        val delThenIns = seeded(small).run { apply(del); apply(ins); render() }
        assertEquals(insThenDel, delThenIns)
    }
}
