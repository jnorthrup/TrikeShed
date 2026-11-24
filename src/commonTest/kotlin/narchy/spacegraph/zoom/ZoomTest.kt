package narchy.spacegraph.zoom

// Upstream test/zoom.test.ts at 125b369, the 6 pure-math cases (normalizeZoom 3, denormalizeZoom 2, clamp 1),
// expected values copied from upstream. The 4 CameraControls orthographic cases are not ported: they need
// CameraControls.ts and THREE, neither ported.

import narchy.spacegraph.util.clamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val PERSPECTIVE = ZoomRange(min = 10.0, max = 10000.0, closerIsLarger = false)
private val ORTHO = ZoomRange(min = 0.01, max = 100.0, closerIsLarger = true)

private const val EPS = 1e-6

private fun closeTo(expected: Double, actual: Double, eps: Double = EPS) {
    assertTrue(kotlin.math.abs(expected - actual) < eps, "expected $expected, got $actual")
}

class ZoomTest {
    // normalizeZoom

    // 'maps perspective distance so the closest is 1 and the farthest is 0'
    @Test
    fun mapsPerspectiveDistanceSoTheClosestIs1AndTheFarthestIs0() {
        closeTo(1.0, normalizeZoom(10.0, PERSPECTIVE))
        closeTo(0.0, normalizeZoom(10000.0, PERSPECTIVE))
        assertEquals(1.0, normalizeZoom(5.0, PERSPECTIVE))
        assertEquals(0.0, normalizeZoom(20000.0, PERSPECTIVE))
    }

    // 'maps orthographic zoom so the closest is 1 and the farthest is 0'
    @Test
    fun mapsOrthographicZoomSoTheClosestIs1AndTheFarthestIs0() {
        closeTo(1.0, normalizeZoom(100.0, ORTHO))
        closeTo(0.0, normalizeZoom(0.01, ORTHO))
        assertEquals(0.0, normalizeZoom(0.001, ORTHO))
        assertEquals(1.0, normalizeZoom(1000.0, ORTHO))
    }

    // 'is monotonic in the middle of the range'
    @Test
    fun isMonotonicInTheMiddleOfTheRange() {
        assertTrue(normalizeZoom(100.0, PERSPECTIVE) > normalizeZoom(1000.0, PERSPECTIVE))
        assertTrue(normalizeZoom(1.0, ORTHO) > normalizeZoom(0.1, ORTHO))
    }

    // denormalizeZoom

    // 'inverts normalizeZoom for both projections'
    @Test
    fun invertsNormalizeZoomForBothProjections() {
        for (range in listOf(PERSPECTIVE, ORTHO)) for (value in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            closeTo(
                denormalizeZoom(value, range),
                denormalizeZoom(normalizeZoom(denormalizeZoom(value, range), range), range),
            )
        }
    }

    // 'clamps out-of-range normalized values to the range'
    @Test
    fun clampsOutOfRangeNormalizedValuesToTheRange() {
        closeTo(10000.0, denormalizeZoom(-1.0, PERSPECTIVE), 1e-6)
        closeTo(10.0, denormalizeZoom(2.0, PERSPECTIVE), 1e-6)
    }

    // clamp

    // 'bounds a value to both ends'
    @Test
    fun boundsAValueToBothEnds() {
        assertEquals(5.0, clamp(5.0, 0.0, 10.0))
        assertEquals(0.0, clamp(-1.0, 0.0, 10.0))
        assertEquals(10.0, clamp(11.0, 0.0, 10.0))
    }
}
