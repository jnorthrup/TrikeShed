package narchy.spacegraph

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where a bay puts its books and where its wood stands: the rules [Bay] states, one test each. */
class BayTest {
    /** Nine books of unequal size: rows of four, four and one. */
    private val radii = listOf(30.0, 80.0, 55.0, 120.0, 45.0, 70.0, 20.0, 64.0, 33.0)
    private val bay = Bay("alpha", IntArray(radii.size) { it })
    private val at = bay.stand({ radii[it] }, left = 250.0, back = -440.0)
    private val rows = radii.indices.chunked(CuratorScope.ROW)
    private val eps = 1e-9

    @Test fun everyBookStandsInTheFrontPlaneOfItsBay() {
        for ((i, c) in at.withIndex()) assertEquals(bay.front, c.z, eps, "book $i")
    }

    @Test fun nothingOfABayStandsInFrontOfItsBooks() {
        val solids = bay.solids()
        for ((i, s) in solids.withIndex()) assertTrue(s.max.z <= bay.front + eps, "solid $i reaches ${s.max.z}, in front of the books at ${bay.front}")
    }

    @Test fun theWoodInFrontOfTheBackPanelIsFlushWithTheBooksSoTheRuleIsNotVacuous() {
        val solids = bay.solids()
        assertTrue(solids.first().max.z < bay.front - 1.0, "the back panel stands behind the books")
        assertEquals(solids.size - 1, solids.count { abs(it.max.z - bay.front) <= eps }, "every other solid ends at the front plane")
    }

    @Test fun aBookClearsBothPostsByTheGapAndNoWoodOverlapsItInThatPlane() {
        val solids = bay.solids()
        val inThePlane = solids.filter { it.max.z >= bay.front - eps }
        for ((i, c) in at.withIndex()) for ((j, s) in inThePlane.withIndex()) {
            val dx = max(max(s.min.x - c.x, c.x - s.max.x), 0.0)
            val dy = max(max(s.min.y - c.y, c.y - s.max.y), 0.0)
            assertTrue(hypot(dx, dy) >= radii[i] - eps, "book $i overlaps solid $j in the front plane")
        }
        for (post in listOf(solids[1], solids[2])) for ((i, c) in at.withIndex()) {
            val clear = max(max(post.min.x - c.x, c.x - post.max.x), 0.0) - radii[i]
            assertTrue(clear >= bay.gap - eps, "book $i is only $clear from a post, the gap is ${bay.gap}")
        }
    }

    @Test fun booksOfARowStandSideBySideAndEachRowOnItsOwnBoard() {
        assertEquals(rows.size + 1, bay.boards.size, "a board under every row and one over the last")
        for ((r, row) in rows.withIndex()) {
            for (b in row) assertEquals(bay.boards[r], at[b].y - radii[b], eps, "book $b stands on its board")
            for ((a, b) in row.zipWithNext()) assertEquals(radii[a] + radii[b] + bay.gap, at[b].x - at[a].x, eps, "books $a and $b")
            val tallest = row.maxOf { radii[it] }
            assertEquals(bay.boards[r] + 2 * tallest + bay.gap, bay.boards[r + 1] - bay.board, eps, "a gap over the tallest book of row $r")
        }
        assertEquals(bay.boards.last(), bay.h, eps)
    }

    @Test fun aBayIsAsWideAsItsWidestRowAndOneGapMore() {
        val widest = rows.maxOf { row -> at[row.last()].x + radii[row.last()] }
        assertEquals(widest + bay.gap, bay.x1 - bay.post, eps)
        assertEquals(250.0, bay.x0, eps)
    }
}
