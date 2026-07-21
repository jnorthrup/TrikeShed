package borg.trikeshed.lcnc.editor

import borg.trikeshed.lcnc.isam.*
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LcncVisualEditorTest {
    @Test
    fun testBlockViewRendersBlock() {
        val block = LcncBlock(
            id = "b1",
            type = "paragraph",
            parentId = "p1",
            content = "Hello LCNC"
        )
        val view = BlockView(block)
        val html = view.renderHtml()
        assertTrue(html.contains("class=\"lcnc-block\""))
        assertTrue(html.contains("data-block-id=\"b1\""))
        assertTrue(html.contains("data-block-type=\"paragraph\""))
        assertTrue(html.contains("Hello LCNC"))
    }

    @Test
    fun testDatabaseViewRendersTable() {
        val page1 = LcncPage(id = "page1", title = "Row 1", parentId = "db1", contentBlocks = 0 j { null!! })
        val page2 = LcncPage(id = "page2", title = "Row 2", parentId = "db1", contentBlocks = 0 j { null!! })
        val db = LcncDatabase(
            id = "db1",
            title = "My Database",
            parentId = "ws1",
            pages = 2 j { i -> if (i == 0) page1 else page2 }
        )

        val view = DatabaseView(db)
        val html = view.renderHtml()

        assertTrue(html.contains("class=\"lcnc-database\""))
        assertTrue(html.contains("data-database-id=\"db1\""))
        assertTrue(html.contains("<th>Title</th>"))
        assertTrue(html.contains("<td>Row 1</td>"))
        assertTrue(html.contains("<td>Row 2</td>"))
    }
}
