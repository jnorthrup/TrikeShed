package borg.trikeshed.forge.table

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.cellsToRowVec
import borg.trikeshed.forge.sheet.sheetSeed
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals

class ForgeLensTest {
    private val keys: Series<CharSequence> = 4 j { i -> listOf("title", "status", "points", "done")[i] }

    private val data = listOf(
        listOf("Parse the statute", "doing", 5, false),
        listOf("Type the nouns", "todo", 3, false),
        listOf("Join the books", "doing", 8, false),
        listOf("Publish the wiki", "done", 2, true),
        listOf("Read the dictionary", null, 1, false),
    )

    private val source: Cursor = data.size j { r -> cellsToRowVec(4 j { c -> data[r][c] }, keys) }

    @Test
    fun noLensShowsEveryRowInSourceOrder() {
        assertEquals(listOf(0, 1, 2, 3, 4), ForgeLens().rows(source).toList())
    }

    @Test
    fun whereFiltersOnTypedCells() {
        assertEquals(listOf(0, 2), ForgeLens(where = listOf(Clause("status", Op.Eq, "doing"))).rows(source).toList())
        assertEquals(listOf(0, 2), ForgeLens(where = listOf(Clause("points", Op.Gt, "4"))).rows(source).toList())
        assertEquals(listOf(1, 3, 4), ForgeLens(where = listOf(Clause("points", Op.Lt, 4), Clause("title", Op.Contains, "THE"))).rows(source).toList())
    }

    @Test
    fun sortIsNumericOnNumbersAndStableOnTies() {
        assertEquals(listOf(4, 3, 1, 0, 2), ForgeLens(sort = Sort("points")).rows(source).toList())
        assertEquals(listOf(2, 0, 1, 3, 4), ForgeLens(sort = Sort("points", descending = true)).rows(source).toList())
        assertEquals(listOf(4, 3, 0, 2, 1), ForgeLens(sort = Sort("status")).rows(source).toList())
    }

    @Test
    fun lanesFollowFirstAppearanceAndKeepAbsentValuesTogether() {
        val lanes = ForgeLens(group = "status").lanes(source)
        assertEquals(listOf("doing", "todo", "done", LANE_NONE), (0 until lanes.size).map { lanes[it].a })
        assertEquals(listOf(0, 2), lanes[0].b.toList())
        assertEquals(listOf(4), lanes[3].b.toList())
    }

    @Test
    fun theThreeSurfacesNameTheSameSourceRows() {
        val lens = ForgeLens(where = listOf(Clause("done", Op.Eq, false)), sort = Sort("points", descending = true), group = "status")

        val sheet = sheetSeed("t", "tasks", lens.view(source))
        assertEquals(listOf("Join the books", "Parse the statute", "Type the nouns", "Read the dictionary"), sheet.rows.map { it[0] })

        val board = lens.board(source, title = "title")
        assertEquals(listOf("doing", "todo", "none"), board.columns.map { it.id })
        assertEquals(listOf("r2", "r0", "r1", "r4"), board.cards.map { it.id })
        assertEquals(listOf("doing", "doing", "todo", "none"), board.cards.map { it.column })

        val page = source.page(2, title = "title")
        assertEquals("r2", page.id)
        assertEquals("Join the books", page.title)
        assertEquals(listOf("status: doing", "points: 8", "done: false"), page.blocks.map { it.text })
    }

    @Test
    fun emptySourceYieldsEmptySurfaces() {
        val none: Cursor = 0 j { _: Int -> error("no rows") }
        assertEquals(0, ForgeLens(sort = Sort("x"), where = listOf(Clause("x", Op.Eq, 1))).rows(none).size)
        assertEquals(0, ForgeLens(group = "x").lanes(none).size)
    }
}
