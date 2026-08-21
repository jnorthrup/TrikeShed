package borg.trikeshed.forge.blackboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SiteTileSurfaceProjectionTest {
    @Test
    fun stickyPlacementAcrossCallsWithSameConfixPath() {
        val t1 = SiteTile("a/b/c.kt", "lines", 10, "label1")
        val p1 = SiteTileSurfaceProjection("bytecode")
        val now = 1000L
        val (view1, surface1, index1) = p1.project(listOf(t1), ForgeBlackboardView.DEFAULT, now)

        val firstEnvelope = surface1.envelopes.first()

        val t2 = SiteTile("a/b/c.kt", "lines", 20, "label2")
        val p2 = SiteTileSurfaceProjection("bytecode")
        // Merge with previous view to keep placements stable
        val (view2, surface2, index2) = p2.project(listOf(t2), view1, now)

        val secondEnvelope = surface2.envelopes.first()

        assertEquals(firstEnvelope.centerX, secondEnvelope.centerX)
        assertEquals(firstEnvelope.centerY, secondEnvelope.centerY)
    }

    @Test
    fun validAnchorsBytecodeAndVm() {
        val t = SiteTile("x/y.kt", "lines", 10, "label")
        val pb = SiteTileSurfaceProjection("bytecode")
        val pv = SiteTileSurfaceProjection("vm")

        val now = 1000L
        val (vb, sb, ib) = pb.project(listOf(t), ForgeBlackboardView.DEFAULT, now)
        val (vv, sv, iv) = pv.project(listOf(t), ForgeBlackboardView.DEFAULT, now)

        assertNotNull(sb)
        assertNotNull(sv)
        assertEquals("bytecode", sb.anchorSectionId)
        assertEquals("vm", sv.anchorSectionId)
    }
}
