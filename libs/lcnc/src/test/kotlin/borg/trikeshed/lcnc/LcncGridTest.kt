package borg.trikeshed.lcnc

import borg.trikeshed.parse.confix.*
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LcncGridTest {
    @Test
    fun testGridPagination() {
        val json1 = """{"id": 1, "name": "A"}"""
        val json2 = """{"id": 2, "name": "B"}"""
        val json3 = """{"id": 3, "name": "C"}"""

        val docs = listOf(json1, json2, json3).map { confixDoc(it) }
        val docSeries: Series<ConfixDoc> = docs.size j { i: Int -> docs[i] }
        val rows: Cursor = docs.size j { i: Int -> docs[i].root!! }

        val grid = LcncGrid(rows, docSeries)

        val page = grid.page(1, 3)
        assertEquals(2, page.rowCount)

        val nameB = page.srcDocs[0].root!!.kids[0].reify(page.srcDocs[0].src)
        assertEquals("B", nameB)

        val nameC = page.srcDocs[1].root!!.kids[0].reify(page.srcDocs[1].src)
        assertEquals("C", nameC)

        // Test formula
        val formulaGrid = page.addFormulaColumn { _, _ -> "formula_val" }
        assertEquals(2, formulaGrid.rowCount)
    }
}
