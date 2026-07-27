package borg.trikeshed.forge

import kotlin.test.Test
import kotlin.test.assertEquals

class KanbanColumnParserTest {
    @Test
    fun testKanbanColumnParsingRegexOptimizations() {
        val rootPageId = ForgeBlockId("page-1")
        val columnsJson = """[{"id":"col-1","name":"To Do","order":1,"wipLimit":5},{"id":"col-2","name":"Doing","order":2}]"""
        val pageBlock = ForgeBlock(
            id = rootPageId,
            kind = ForgeBlockKind.PAGE,
            text = "Test Page",
            parentId = null,
            properties = mapOf("kanban.columns" to columnsJson)
        )
        val doc = ForgeDocument(
            rootPageId = rootPageId, 
            cursor = ForgeCursor(rootPageId, rootPageId),
            blocks = mapOf(rootPageId.value to pageBlock)
        )
        
        val board = doc.toKanbanBoard()
        assertEquals(2, board.columns.size)
        
        val col1 = board.columns[0]
        assertEquals("col-1", col1.id.value)
        assertEquals("To Do", col1.name)
        assertEquals(1, col1.order)
        assertEquals(5, col1.wipLimit)

        val col2 = board.columns[1]
        assertEquals("col-2", col2.id.value)
        assertEquals("Doing", col2.name)
        assertEquals(2, col2.order)
        assertEquals(null, col2.wipLimit)
    }
}
