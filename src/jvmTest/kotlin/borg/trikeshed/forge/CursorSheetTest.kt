package borg.trikeshed.forge

import borg.trikeshed.blackboard.BlackboardSurface
import borg.trikeshed.forge.sheet.SheetRef
import borg.trikeshed.forge.sheet.confixSheets
import borg.trikeshed.forge.sheet.sheetSeed
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.parse.confix.confixDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cursor → sheet keeps the ColumnMeta exemplar; Confix → sheets nests containers as grid-in-cell refs. */
class CursorSheetTest {

    @Test
    fun blackboardSurfaceCursorBecomesAnEightColumnSheet() {
        val reduction = ForgeKanbanIngest.fallbackReduction()
        val cardById = reduction.board.cards.associateBy { it.id.value }
        val entities = reduction.correlations.mapNotNull { c -> cardById[c.taskId]?.let { correlationToBlock(c, it) } }
        val index = CausalGraphNodeIndex()
        reduction.causalNodes.forEach { index.addOrGet(it) }
        val surface = BlackboardSurface.project("sheet-test", index, entities)
        val sheet = sheetSeed("blackboard", "Blackboard", surface.asCursor())
        assertEquals(listOf("card_id", "lane", "phase", "facet", "provenance", "causalKey", "lcncKind", "title"), sheet.columns.map { it.name })
        assertEquals(entities.size, sheet.rows.size, "one row per entity")
        assertTrue(sheet.rows.all { it.size == 8 })
        assertTrue(sheet.rows.none { row -> row.any { it is SheetRef } }, "a flat cursor has no nested cells")
    }

    @Test
    fun confixDocumentBecomesNestedSheets() {
        val doc = confixDoc("""{"title":"t","defs":{"a":{"type":"string"},"b":[1,2,3]},"n":7}""")
        val family = confixSheets("doc", "Doc", doc)
        val byId = family.associateBy { it.id }
        val root = byId.getValue("doc")
        assertEquals(listOf("key", "value"), root.columns.map { it.name })
        assertEquals(listOf("title", "defs", "n"), root.rows.map { it[0] })
        assertEquals(SheetRef("doc/defs"), root.rows[1][1], "an object cell is a ref to its own sheet")
        assertEquals(7, (root.rows[2][1] as Number).toInt())
        val defs = byId.getValue("doc/defs")
        assertEquals("doc", defs.parent)
        assertEquals(SheetRef("doc/defs/b"), defs.rows[1][1])
        val b = byId.getValue("doc/defs/b")
        assertEquals(listOf("index", "value"), b.columns.map { it.name })
        assertEquals(3, b.rows.size)
        assertTrue(family.size >= 4, "root, defs, defs/a, defs/b")
    }
}
