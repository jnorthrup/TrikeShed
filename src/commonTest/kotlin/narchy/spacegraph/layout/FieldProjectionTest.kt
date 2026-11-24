package narchy.spacegraph.layout

// Upstream test/layout/field-projection.test.ts at 125b369: all 3 cases ported, expected values copied from upstream.

import narchy.spacegraph.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldProjectionTest {
    // Upstream bounds(minX, maxY, maxX, minY): the same argument order is kept so each literal reads like upstream.
    private fun bounds(minX: Double, maxY: Double, maxX: Double, minY: Double): CenterBounds =
        boundsFromExtents(minX, minY, 0.0, maxX, maxY, 0.0)

    private fun layout(): ScopeLayout {
        val frame = ScopeFrame(
            id = "run/unit-1",
            parent = null,
            depth = 1,
            origin = Vec3(0.0, 0.0, 0.0),
            scale = 1.0,
            bounds = bounds(-100.0, 50.0, 100.0, -50.0),
            active = true,
        )
        return ScopeLayout(
            positions = emptyMap(),
            locals = emptyMap(),
            frames = mapOf("run/unit-1" to frame),
            edges = emptyList(),
            bounds = bounds(-100.0, 50.0, 100.0, -50.0),
        )
    }

    private val project = FieldProjector { p -> ScreenPoint((p.x + 200) * 2, (200 - p.y) * 3) }

    // 'maps a solved frame through the host projector into a screen rectangle'
    @Test
    fun mapsASolvedFrameThroughTheHostProjectorIntoAScreenRectangle() {
        val rects = scopeFieldRects(layout(), project)
        assertEquals(mapOf("run/unit-1" to FieldRect(200.0, 450.0, 400.0, 300.0)), rects)
    }

    // 'grows the rectangle by padding in world units before projecting'
    @Test
    fun growsTheRectangleByPaddingInWorldUnitsBeforeProjecting() {
        val rects = scopeFieldRects(layout(), project, 10.0)
        assertEquals(FieldRect(180.0, 420.0, 440.0, 360.0), rects["run/unit-1"])
    }

    // 'yields nothing without a solved layout or a projector that can place the frame'
    @Test
    fun yieldsNothingWithoutASolvedLayoutOrAProjectorThatCanPlaceTheFrame() {
        assertTrue(scopeFieldRects(null, project).isEmpty())
        assertTrue(scopeFieldRects(layout(), FieldProjector { null }).isEmpty())
    }
}
