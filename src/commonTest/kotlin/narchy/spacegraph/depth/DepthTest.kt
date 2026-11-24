package narchy.spacegraph.depth

// Upstream test/depth.test.ts and test/depth-continuum.test.ts at 125b369: all cases ported, expected values copied.

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthTest {
    @Test
    fun flatIsTheOrthographicBaselineAndTheOthersArePerspective() {
        val flat = depthTreatment(DepthMode.flat)
        assertEquals(DepthProjection.orthographic, flat.projection)
        assertEquals(false, flat.orbit)
        assertEquals(0.0, flat.fade, 1e-9)
        for (mode in listOf(DepthMode.layered, DepthMode.depthStack, DepthMode.orbit)) {
            assertEquals(DepthProjection.perspective, depthTreatment(mode).projection)
        }
    }

    @Test
    fun onlyOrbitEnablesFreeOrbitAndOnlyFlatRemovesTheDepthFade() {
        assertEquals(listOf(DepthMode.orbit), DepthMode.entries.filter { depthTreatment(it).orbit })
        assertEquals(
            listOf(DepthMode.layered, DepthMode.depthStack),
            DepthMode.entries.filter { depthTreatment(it).fade > 0 },
        )
    }

    @Test
    fun theCameraTiltsFurtherAsDepthIncreasesAndFlatLooksStraightDown() {
        assertEquals(PI / 2, depthTreatment(DepthMode.flat).phi, 1e-9)
        assertTrue(depthTreatment(DepthMode.layered).phi > depthTreatment(DepthMode.depthStack).phi)
        assertEquals(PI / 2 - MAX_READABLE_TILT, depthTreatment(DepthMode.depthStack).phi, 1e-9)
        assertTrue(depthTreatment(DepthMode.orbit).phi < depthTreatment(DepthMode.depthStack).phi)
    }

    @Test
    fun depthStackFadesHarderThanLayered() {
        assertTrue(depthTreatment(DepthMode.depthStack).fade > depthTreatment(DepthMode.layered).fade)
    }

    @Test
    fun theTiltedTreatmentsFaceTheCameraAndTheFlatBaselineDoesNot() {
        assertEquals(CardFacing.plane, depthTreatment(DepthMode.flat).facing)
        assertEquals(CardFacing.camera, depthTreatment(DepthMode.layered).facing)
        assertEquals(CardFacing.camera, depthTreatment(DepthMode.depthStack).facing)
        assertEquals(CardFacing.plane, depthTreatment(DepthMode.orbit).facing)
    }

    @Test
    fun depthFadeIsOneAtTheCameraAndOneWhenDisabled() {
        assertEquals(1.0, depthFade(0.0, 0.003), 1e-9)
        assertEquals(1.0, depthFade(500.0, 0.0), 1e-9)
    }

    @Test
    fun depthFadeDecreasesMonotonicallyAndStaysInZeroOne() {
        val near = depthFade(100.0, 0.003)
        val far = depthFade(900.0, 0.003)
        assertTrue(far < near)
        assertTrue(far > 0)
        assertTrue(near <= 1)
    }

    @Test
    fun aDenserFadeAttenuatesSooner() {
        assertTrue(depthFade(500.0, 0.006) < depthFade(500.0, 0.002))
    }

    @Test
    fun sceneRelativeFadeIsDisabledForZeroStrengthAndNeverDividesByZero() {
        assertEquals(0.0, sceneFadeDensity(0.0, 1000.0, 200.0), 1e-9)
        assertTrue(sceneFadeDensity(0.5, 0.0, 0.0).isFinite())
    }

    @Test
    fun keepsTheSameVisibleAttenuationWhenTheFitDistanceScalesTheScene() {
        val near = depthFade(500.0, sceneFadeDensity(0.5, 500.0, 200.0))
        val far = depthFade(5000.0, sceneFadeDensity(0.5, 5000.0, 200.0))
        assertTrue(abs(near - far) < 0.15)
        assertTrue(near > 0.5)
    }

    @Test
    fun atAFixedCameraDistanceADeeperSceneFadesMoreGradually() {
        assertTrue(sceneFadeDensity(0.5, 1000.0, 400.0) < sceneFadeDensity(0.5, 1000.0, 100.0))
    }

    @Test
    fun continuumDeclaresHostSelectableAxesWithNoneAsBaseline() {
        assertEquals(listOf("none", "lane", "turn", "hierarchy"), DepthAxis.entries.map { it.name })
        assertEquals(DepthAxis.none, DEFAULT_DEPTH_CONTINUUM.axis)
    }

    @Test
    fun continuumBlendsModeTreatmentsWhileKeepingTheEndpointsExact() {
        val flat = blendDepth(DepthMode.flat, DepthMode.orbit, 0.0)
        val orbit = blendDepth(DepthMode.flat, DepthMode.orbit, 1.0)
        val middle = blendDepth(DepthMode.flat, DepthMode.orbit, 0.5)
        assertEquals(PI / 2, flat.phi, 1e-9)
        assertEquals(PI / 2 - 0.5, orbit.phi, 1e-9)
        assertEquals((flat.phi + orbit.phi) / 2, middle.phi, 1e-9)
        assertEquals(DepthProjection.perspective, middle.projection)
    }

    @Test
    fun continuumOffsetsNodesByTheHostAxisAndAttenuatesOpacityWithDepth() {
        val continuum = DepthContinuum(DepthMode.layered, DepthAxis.lane, strength = 1.0, spread = 1.0)
        val near = sampleDepth(continuum, 0.0, 2.0)
        val far = sampleDepth(continuum, 1.0, 2.0)
        assertEquals(-1.0, near.offset, 1e-9)
        assertEquals(1.0, far.offset, 1e-9)
        assertTrue(far.opacity < near.opacity)
        assertEquals(0.0, sampleDepth(DEFAULT_DEPTH_CONTINUUM, 1.0, 2.0).offset, 1e-9)
    }
}
