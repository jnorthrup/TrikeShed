package borg.trikeshed.manifold

import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ManifoldTest {
    private val shifted = Chart<Double, Double>(
        name = "shifted",
        dimension = 1,
        contains = { point: Double -> point > 5.0 },
        project = { point: Double -> coordinatesOf(point - 10.0) },
        embed = { coordinates: Coordinates<Double> -> coordinates[0] + 10.0 },
    )

    private val identity = Chart<Double, Double>(
        name = "identity",
        dimension = 1,
        contains = { point: Double -> point <= 20.0 },
        project = { point: Double -> coordinatesOf(point) },
        embed = { coordinates: Coordinates<Double> -> coordinates[0] },
    )

    private val manifold = Manifold(
        Atlas(2 j { index: Int -> if (index == 0) shifted else identity })
    )

    @Test
    fun `atlas selects the first matching chart`() {
        val located = manifold.locate(17.0)

        assertNotNull(located)
        assertEquals("shifted", located.a.name)
        assertEquals(7.0, located.b[0])
    }

    @Test
    fun `transition reprojects semantic coordinates across charts`() {
        val shiftedCoordinates = manifold.transition(
            fromChartName = "identity",
            toChartName = "shifted",
            coordinates = coordinatesOf(17.0),
        )
        val identityCoordinates = manifold.transition(
            fromChartName = "shifted",
            toChartName = "identity",
            coordinates = coordinatesOf(7.0),
        )

        assertNotNull(shiftedCoordinates)
        assertEquals(7.0, shiftedCoordinates[0])
        assertNotNull(identityCoordinates)
        assertEquals(17.0, identityCoordinates[0])
    }

    @Test
    fun `dense lowering stays explicit and separate`() {
        val semantic = coordinatesOf(2.0, 3.0, 5.0)
        val dense = semantic.lowered()
        val restored = dense.semantic()

        assertEquals(listOf(2.0, 3.0, 5.0), dense.axes)
        assertEquals(3, restored.dimension)
        assertEquals(2.0, restored[0])
        assertEquals(3.0, restored[1])
        assertEquals(5.0, restored[2])
    }

    @Test
    fun `transition returns null when destination chart does not cover the point`() {
        val missing = manifold.transition(
            fromChartName = "identity",
            toChartName = "shifted",
            coordinates = coordinatesOf(3.0),
        )

        assertNull(missing)
    }
}
