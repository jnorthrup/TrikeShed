package narchy.spacegraph.zoom

// Upstream test/zoom-bands.test.ts at 125b369: all eight cases ported, expected values copied from upstream.
// Upstream has no test file for ScaleDomain or core/zoom; those are checked only by the gate compile.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZoomBandsTest {
    private val bandSet = listOf(
        ZoomBand(id = "overview", min = 0.0, max = 0.34, label = "Overview"),
        ZoomBand(id = "execution", min = 0.34, max = 0.67, label = "Execution"),
        ZoomBand(id = "detail", min = 0.67, max = 1.0, label = "Detail"),
    )

    // "resolves bands at the threshold edges"
    @Test fun resolvesBandsAtTheThresholdEdges() {
        val bands = ZoomBands(ZoomBandsConfig(bands = bandSet))
        assertEquals("overview", bands.band)
        assertEquals("overview", bands.feedNormalized(0.339).band)
        assertEquals("execution", bands.feedNormalized(0.34).band)
        assertEquals("execution", bands.feedNormalized(0.669).band)
        assertEquals("detail", bands.feedNormalized(0.67).band)
        assertEquals("detail", bands.feedNormalized(1.0).band)
    }

    // "emits a change with from/to and the previous snapshot"
    @Test fun emitsAChangeWithFromToAndThePreviousSnapshot() {
        val bands = ZoomBands(ZoomBandsConfig(bands = bandSet))
        val changes = mutableListOf<ZoomBandChange>()
        bands.onChange { changes.add(it) }
        bands.feedNormalized(0.9)
        assertEquals(1, changes.size)
        val change = changes[0]
        assertEquals("overview", change.from)
        assertEquals("detail", change.band)
        assertEquals("Detail", change.label)
        assertEquals("overview", change.previous.band)
        assertEquals(0.9, change.normalized, 1e-9)
    }

    // "does not flap across a boundary within the hysteresis dead-zone"
    @Test fun doesNotFlapAcrossABoundaryWithinTheHysteresisDeadZone() {
        val bands = ZoomBands(ZoomBandsConfig(bands = bandSet, hysteresis = 0.05))
        val changes = mutableListOf<ZoomBandChange>()
        bands.onChange { changes.add(it) }
        bands.feedNormalized(0.33)
        bands.feedNormalized(0.35)
        bands.feedNormalized(0.33)
        assertTrue(changes.isEmpty())
        assertEquals("overview", bands.band)
        assertEquals("execution", bands.feedNormalized(0.4).band)
    }

    // "normalizes a raw camera zoom through the supplied range"
    @Test fun normalizesARawCameraZoomThroughTheSuppliedRange() {
        val bands = ZoomBands(ZoomBandsConfig(bands = bandSet))
        val range = ZoomRange(min = 2.0, max = 1000.0, closerIsLarger = false)
        assertEquals("detail", bands.feed(2.0, range).band)
        assertEquals("overview", bands.feed(1000.0, range).band)
        assertEquals(1000.0, bands.snapshot().zoom, 1e-9)
    }

    // "tracks band changes across a projection switch"
    @Test fun tracksBandChangesAcrossAProjectionSwitch() {
        val bands = ZoomBands(ZoomBandsConfig(bands = bandSet))
        val perspective = ZoomRange(min = 50.0, max = 2000.0, closerIsLarger = false)
        val orthographic = ZoomRange(min = 0.01, max = 100.0, closerIsLarger = true)
        assertEquals("overview", bands.feed(2000.0, perspective).band)
        assertEquals("detail", bands.feed(100.0, orthographic).band)
        assertEquals("overview", bands.feed(0.01, orthographic).band)
    }

    // "reports the band index by id"
    @Test fun reportsTheBandIndexById() {
        val bands = ZoomBands(ZoomBandsConfig(bands = bandSet))
        assertEquals(0, bands.indexOf("overview"))
        assertEquals(2, bands.indexOf("detail"))
        assertEquals(-1, bands.indexOf("missing"))
    }

    // "unsubscribes listeners"
    @Test fun unsubscribesListeners() {
        val bands = ZoomBands(ZoomBandsConfig(bands = bandSet))
        var calls = 0
        val unsubscribe = bands.onChange { calls++ }
        unsubscribe()
        bands.feedNormalized(0.9)
        assertEquals(0, calls)
    }

    // "rejects empty, uncovered, and gapped band sets"
    @Test fun rejectsEmptyUncoveredAndGappedBandSets() {
        assertFailsWith<ConfigurationError> { ZoomBands(ZoomBandsConfig(bands = emptyList())) }
        assertFailsWith<ConfigurationError> {
            ZoomBands(ZoomBandsConfig(bands = listOf(ZoomBand(id = "a", min = 0.2, max = 0.8))))
        }
        assertFailsWith<ConfigurationError> {
            ZoomBands(
                ZoomBandsConfig(
                    bands = listOf(
                        ZoomBand(id = "a", min = 0.0, max = 0.4),
                        ZoomBand(id = "b", min = 0.5, max = 1.0),
                    ),
                ),
            )
        }
    }
}
