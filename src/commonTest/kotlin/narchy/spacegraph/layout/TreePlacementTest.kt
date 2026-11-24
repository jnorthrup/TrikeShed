package narchy.spacegraph.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Expected values are upstream test/layout/placement.test.ts, 'the tree placement' and 'the placement seam' blocks,
// copied by hand. Case names follow the upstream `it` titles.
private fun box(id: String, parent: String? = null, width: Double = 200.0) =
    PlacementItem(id, width, meta = TreeMeta(parent = parent))

class TreePlacementTest {
    @Test fun letsAnEngineSeatBoxesWithoutTheGrid() {
        val engine = TreePlacement(TreeConfig(columnPitch = 100.0, rowPitch = 50.0))
        val positions = engine.place(
            listOf(box("root", width = 50.0), box("child", "root", 50.0), box("other", "root", 50.0)),
        )

        assertEquals(-50.0, positions.getValue("child").y)
        assertEquals(62.0, positions.getValue("child").x)
        assertEquals(186.0, positions.getValue("other").x)
        assertEquals(124.0, positions.getValue("root").x)
    }

    @Test fun keepsAPlacedBoxWhereItWasWhenTheLayoutHandsThePositionsBack() {
        val engine = TreePlacement(TreeConfig(columnPitch = 100.0, rowPitch = 50.0))
        val before = engine.place(listOf(box("root"), box("child", "root")))
        val after = engine.place(listOf(box("root"), box("child", "root"), box("new", "child")), before)

        assertEquals(before["root"], after["root"])
        assertEquals(before["child"], after["child"])
        assertTrue(after.containsKey("new"))
    }

    @Test fun isDeterministic() {
        val engine = TreePlacement()
        val boxes = listOf(box("root"), box("a", "root"), box("b", "root"))

        assertEquals(engine.place(boxes).entries.toList(), engine.place(boxes).entries.toList())
    }

    @Test fun growsDownwardAChildSitsOneRowBelowItsParent() {
        val engine = TreePlacement(TreeConfig(columnPitch = 100.0, rowPitch = 40.0))
        val positions = engine.place(listOf(box("root", width = 50.0), box("a", "root", 50.0), box("b", "a", 50.0)))

        assertEquals(-40.0, positions.getValue("a").y - positions.getValue("root").y)
        assertEquals(-40.0, positions.getValue("b").y - positions.getValue("a").y)
    }

    @Test fun leavesAPlacedBoxWhereItWasWhenAChildIsAppended() {
        val engine = TreePlacement(TreeConfig(columnPitch = 100.0, rowPitch = 40.0))
        val before = engine.place(listOf(box("root", width = 50.0), box("a", "root", 50.0)))
        val after = engine.place(
            listOf(box("root", width = 50.0), box("a", "root", 50.0), box("b", "root", 50.0)),
            before,
        )

        assertEquals(before["root"], after["root"])
        assertEquals(before["a"], after["a"])
    }

    @Test fun placesAnAppendedBoxNextToItsParentWhenThereIsRoom() {
        val engine = TreePlacement(TreeConfig(columnPitch = 100.0, rowPitch = 40.0))
        val before = engine.place(listOf(box("root", width = 50.0), box("a", "root", 50.0)))
        val after = engine.place(
            listOf(box("root", width = 50.0), box("a", "root", 50.0), box("b", "root", 50.0)),
            before,
        )

        assertTrue(kotlin.math.abs(after.getValue("b").x - after.getValue("root").x) <= 100.0)
    }

    @Test fun foldsABlockToTheNextRowWhenItHoldsMoreLeavesThanTheColumnCount() {
        val wide = TreePlacement(TreeConfig(columnPitch = 100.0, rowPitch = 40.0, columns = 4))
        val narrow = TreePlacement(TreeConfig(columnPitch = 100.0, rowPitch = 40.0, columns = 2))
        val boxes = listOf(
            box("root", width = 50.0),
            box("a", "root", 50.0),
            box("b", "root", 50.0),
            box("c", "root", 50.0),
        )

        val widePositions = wide.place(boxes)
        val narrowPositions = narrow.place(boxes)

        assertEquals(248.0, widePositions.getValue("c").x - widePositions.getValue("a").x)
        assertEquals(widePositions.getValue("a").y, widePositions.getValue("c").y)

        assertEquals(narrowPositions.getValue("a").x, narrowPositions.getValue("c").x)
        assertTrue(narrowPositions.getValue("c").y < narrowPositions.getValue("a").y)
    }

    @Test fun keepsTwoBoxesFromOverlapping() {
        val engine = TreePlacement(TreeConfig(columnPitch = 100.0, rowPitch = 40.0))
        val points = engine.place(
            listOf(box("root", width = 50.0), box("a", "root", 50.0), box("b", "root", 50.0), box("c", "root", 50.0)),
        ).values.toList()

        for (i in points.indices) for (j in i + 1 until points.size) {
            assertTrue(
                kotlin.math.abs(points[i].x - points[j].x) >= 124.0 ||
                    kotlin.math.abs(points[i].y - points[j].y) >= 40.0,
            )
        }
    }

    @Test fun usesItemWidthWhenLargerThanColumnPitchSizeAware() {
        val engine = TreePlacement(TreeConfig(columnPitch = 100.0, rowPitch = 40.0, columns = 4))
        val positions = engine.place(
            listOf(box("root"), box("a", "root"), box("b", "root"), box("c", "root")),
        )

        assertEquals(496.0, positions.getValue("c").x - positions.getValue("a").x)
    }
}
