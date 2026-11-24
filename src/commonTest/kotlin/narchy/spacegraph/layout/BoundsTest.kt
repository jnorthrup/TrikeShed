package narchy.spacegraph.layout

import narchy.spacegraph.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Upstream src/layout/bounds.ts has no test file. Expected values are worked by hand from its source.
class BoundsTest {
    @Test fun boundsFromExtentsComputesCenterAndSize() {
        val b = boundsFromExtents(0.0, 0.0, 0.0, 4.0, 2.0, 6.0)
        assertEquals(Vec3(2.0, 1.0, 3.0), b.center)
        assertEquals(listOf(4.0, 2.0, 6.0), b.size.toList())
    }

    @Test fun accumulateBoundsReturnsNullForNoPoints() {
        assertNull(accumulateBounds(emptyList()))
    }

    @Test fun accumulateBoundsEnclosesPoints() {
        val b = accumulateBounds(listOf(Vec3(1.0, 5.0, 0.0), Vec3(-2.0, 3.0, 4.0)))!!
        assertEquals(Vec3(-2.0, 3.0, 0.0), b.min)
        assertEquals(Vec3(1.0, 5.0, 4.0), b.max)
    }

    @Test fun unionBoundsEnclosesBoth() {
        val a = boundsFromExtents(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        val c = boundsFromExtents(-1.0, 2.0, 0.0, 0.5, 3.0, 1.0)
        val u = unionBounds(a, c)
        assertEquals(Vec3(-1.0, 0.0, 0.0), u.min)
        assertEquals(Vec3(1.0, 3.0, 1.0), u.max)
    }

    @Test fun transformBoundsScalesThenTranslates() {
        val t = transformBounds(LayoutBounds(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 2.0, 3.0)), Vec3(10.0, 0.0, 0.0), 2.0)
        assertEquals(Vec3(10.0, 0.0, 0.0), t.min)
        assertEquals(Vec3(12.0, 4.0, 6.0), t.max)
    }

    @Test fun boundsOfEmptyIsZeroBounds() {
        assertEquals(ZERO_BOUNDS, boundsOf(emptyList()))
    }
}
