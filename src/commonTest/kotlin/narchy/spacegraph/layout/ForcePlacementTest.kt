package narchy.spacegraph.layout

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Expected behaviour is upstream test/layout/placement.test.ts, 'the force placement' block (4 cases), copied.
private fun item(id: String, links: List<String>? = null, width: Double = 200.0) =
    PlacementItem(id, width, meta = ForceMeta(links = links))

private fun dist(a: narchy.spacegraph.Vec3, b: narchy.spacegraph.Vec3): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

class ForcePlacementTest {
    private val boxes = listOf(
        item("a"),
        item("b"),
        item("c"),
        item("d", links = listOf("a")),
        item("e", links = listOf("a")),
    )

    @Test fun isDeterministic() {
        val engine = ForcePlacement()
        assertEquals(engine.place(boxes).entries.toList(), engine.place(boxes).entries.toList())
    }

    @Test fun keepsAPlacedBoxWhereItWasWhenTheLayoutHandsThePositionsBack() {
        val engine = ForcePlacement()
        val before = engine.place(boxes)
        val after = engine.place(boxes + item("f", links = listOf("a")), before)

        for ((id, p) in before) assertEquals(p, after[id])
        assertTrue(after.containsKey("f"))
    }

    @Test fun pushesTwoBoxesApart() {
        val engine = ForcePlacement(ForceCoreConfig(spacing = 200.0, iterations = 200))
        val positions = engine.place(listOf(item("a"), item("b")))

        assertTrue(dist(positions.getValue("a"), positions.getValue("b")) > 100.0)
    }

    @Test fun pullsALinkedBoxTowardsTheOneItLinksTo() {
        val engine = ForcePlacement(ForceCoreConfig(spacing = 200.0, iterations = 200))
        val positions = engine.place(listOf(item("a"), item("linked", links = listOf("a"))))

        assertTrue(dist(positions.getValue("a"), positions.getValue("linked")) < 600.0)
    }
}
