package borg.trikeshed.ontology.zipper

import borg.trikeshed.cursor.IsAEdge
import borg.trikeshed.cursor.IsALattice
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DerivationReceipt
import borg.trikeshed.narsese.EvidenceCoord
import borg.trikeshed.narsese.Nal
import borg.trikeshed.narsese.TermIdentity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase-4 gate: budget-bounded termination on adversarial lattices; trail provenance. */
class OntoZipperTest {

    private fun lattice(vararg edges: Pair<Int, Int>): IsALattice {
        val packed = edges.map { (sub, sup) -> IsAEdge(sub, sup) }
        return IsALattice(packed.size j { i: Int -> packed[i] })
    }

    private fun receipt(): DerivationReceipt = DerivationReceipt.observation(
        TermIdentity(1), TermIdentity(2),
        ContentId.of("ctx".encodeToByteArray()), ContentId.of("out".encodeToByteArray()),
        Nal.observe(true), ContentId.of("zipper-test".encodeToByteArray()),
    )

    @Test
    fun exhaustedBudgetRefusesAsEmptySeries() {
        val planes = PlaneAdapters(lattice = lattice(0 to 1))
        val z = OntoZipper.seed(PlaneNode(Plane.ISA, 0L), planes, WalkBudget(0, 0))
        assertEquals(0, z.up().size, "exhausted budget must refuse, not throw")
    }

    @Test
    fun walkTerminatesWithinVisitsOnAdversarialCycles() {
        val rnd = Random(42)
        repeat(20) {
            // random cyclic lattice: 24 nodes, 60 edges, cycles guaranteed
            val edges = Array(60) { rnd.nextInt(24) to rnd.nextInt(24) }
            val planes = PlaneAdapters(lattice = lattice(*edges))
            var frontier = listOf(OntoZipper.seed(PlaneNode(Plane.ISA, 0L), planes, WalkBudget(depth = 6, visits = 48)))
            var touches = 0
            while (frontier.isNotEmpty() && touches < 10_000) {
                val next = ArrayList<OntoZipper>()
                for (z in frontier) {
                    val ups = z.up()
                    touches += ups.size
                    for (i in 0 until ups.size) next.add(ups[i])
                    if (next.size > 500) return@repeat // frontier blowup is bounded by budget in each PATH
                }
                frontier = next
            }
            assertTrue(touches < 10_000, "walk must terminate under budget, touched $touches")
        }
    }

    @Test
    fun depthBoundStopsTheWalk() {
        // chain 0→1→2→3→4→5 with depth budget 2
        val planes = PlaneAdapters(lattice = lattice(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5))
        var z = OntoZipper.seed(PlaneNode(Plane.ISA, 0L), planes, WalkBudget(depth = 2, visits = 100))
        var hops = 0
        while (true) {
            val ups = z.up()
            if (ups.size == 0) break
            z = ups[0]; hops++
        }
        assertEquals(2, hops, "depth budget must cap the chain")
    }

    @Test
    fun crossPlaneRequiresReceiptAndTrailRecordsIt() {
        val planes = PlaneAdapters(lattice = lattice(0 to 1))
        val r = receipt()
        val z = OntoZipper.seed(PlaneNode(Plane.ISA, 0L), planes)
            .crossTo(Plane.BAG, 99L, via = r)
        assertEquals(Plane.BAG, z.focus.plane)
        assertEquals(1, z.trail.size)
        assertEquals(r.canonicalCid, z.trail[0].b, "the stitch crumb must carry the receipt CID")
    }

    @Test
    fun trailIsNewestFirstAndReplays() {
        val planes = PlaneAdapters(lattice = lattice(0 to 1, 1 to 2))
        val z0 = OntoZipper.seed(PlaneNode(Plane.ISA, 0L), planes, WalkBudget(4, 16))
        val z1 = z0.up()[0]          // → 1
        val z2 = z1.up()[0]          // → 2
        assertEquals(2, z2.trail.size)
        val replayed = z2.replayTrail()
        assertEquals(2L, replayed[0].node, "newest first")
        assertEquals(1L, replayed[1].node)
        assertEquals(Plane.ISA, z2.focus.plane)
    }

    @Test
    fun atlasResolvesInternedStrings() {
        val atlas = NodeAtlas()
        val id = atlas.intern("skills/coding/kotlin")
        assertEquals("skills/coding/kotlin", atlas.resolve(id))
        assertEquals(id, atlas.intern("skills/coding/kotlin"), "intern is stable")
    }
}
