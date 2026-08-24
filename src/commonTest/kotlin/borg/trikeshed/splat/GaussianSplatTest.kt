package borg.trikeshed.splat

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.shapeOf
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GaussianSplatTest {
    private fun point(vararg values: Double): Series<Double> = values.size j { i: Int -> values[i] }

    @Test
    fun nDimensionalGaussianPeaksAtItsMean() {
        val gaussian = ParameterGaussian(
            mean = point(1.0, 2.0, 3.0, 4.0),
            scale = point(1.0, 1.0, 1.0, 1.0),
            opacity = 0.75,
        )
        assertEquals(0.0, gaussian.mahalanobisSq(point(1.0, 2.0, 3.0, 4.0)))
        assertEquals(0.75, gaussian.kernelValue(point(1.0, 2.0, 3.0, 4.0)))
        assertTrue(gaussian.kernelValue(point(4.0, 2.0, 3.0, 4.0)) < 0.02)
    }

    @Test
    fun motionModelReturnsNormalizedNearbyOutcomeSplat() {
        val model = GaussianMotionModel<Double, String>(projector = { value -> point(value) }, initialScale = 1.0)
        model.observe(0.0, "left")
        model.observe(4.0, "right")
        val prediction = model.predict(0.1)
        assertEquals(2, prediction.size)
        assertTrue(abs(prediction.view.sumOf { it.b } - 1.0) < 1e-12)
        val weights = prediction.view.associate { it.a to it.b }
        assertTrue(weights.getValue("left") > weights.getValue("right"))
    }

    @Test
    fun powerIterationRecoversPrincipalProbabilityAxis() {
        val splat: Splat<String> = 2 j { i: Int -> if (i == 0) "a" j 0.8 else "b" j 0.2 }
        val signature = PowerIterationEigenFinder<String>(iterations = 64).extractSignature(splat)
        assertEquals(3, signature.a[0])
        assertTrue(abs(signature.b(shapeOf(0)) - 0.8) < 1e-6)
        assertTrue(abs(signature.b(shapeOf(1)) - 1.0) < 1e-6)
        assertTrue(abs(signature.b(shapeOf(2))) < 1e-6)
    }

    @Test
    fun spatialHashFindsNearestGaussianAcrossDimensions() {
        val gaussians: Series<ParameterGaussian> = 2 j { i: Int ->
            val center = if (i == 0) point(0.0, 0.0) else point(5.0, 5.0)
            ParameterGaussian(center, point(1.0, 1.0))
        }
        val grid = SpatialHashGrid(cellSize = 1.0, dimensions = 2)
        grid.insert(0, gaussians[0])
        grid.insert(1, gaussians[1])
        val nearest = grid.kNearest(point(0.2, 0.1), gaussians, k = 1, maxRadius = 6)
        assertEquals(1, nearest.size)
        assertEquals(0, nearest[0].a)
    }

    @Test
    fun empiricalAndChronologyRemainDeterministicSeriesProjections() {
        val model = EmpiricalMotionModel<String, String>()
        model.observe("ctx", "b")
        model.observe("ctx", "a")
        model.observe("ctx", "a")
        val splat = model.predict("ctx")
        assertEquals(listOf("a", "b"), splat.view.map { it.a })
        assertTrue(splat.toChronology().startsWith("{\"a\":"))
    }
}
