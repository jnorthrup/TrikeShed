package narchy.spacegraph.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Expected behaviour is upstream test/layout/placement.test.ts, case 'places a grid box in its lane and column'.
// The tree and force cases in that file are not ported yet.
class GridPlacementTest {
    @Test fun placesAGridBoxInItsLaneAndColumn() {
        val engine = GridPlacement(GridConfig(lanes = listOf("top", "bottom"), lanePitch = 60.0, step = 24.0))
        val positions = engine.place(
            listOf(
                PlacementItem("a", 200.0, meta = GridMeta(group = "c1", lane = "bottom")),
                PlacementItem("b", 200.0, meta = GridMeta(group = "c2", lane = "bottom")),
            ),
        )

        assertEquals(60.0, positions.getValue("a").y)
        assertTrue(positions.getValue("b").x > positions.getValue("a").x)
    }
}
