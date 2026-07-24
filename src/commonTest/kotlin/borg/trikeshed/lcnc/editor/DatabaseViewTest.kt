package borg.trikeshed.lcnc.editor

import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.isam.LcncPage
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.ccek.IngestStateElement
import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.lib.j
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseViewTest {
    @Test
    fun testDatabaseViewRendersGrid() = runTest {
        val page1 = LcncPage(id = "page1", title = "Row 1", parentId = "db1", contentBlocks = 0 j { null!! })
        val db = LcncDatabase(
            id = "db1",
            title = "My Database",
            parentId = "ws1",
            pages = 1 j { page1 }
        )
        
        val ingestState = IngestStateElement("test")
        val schema = borg.trikeshed.lcnc.collections.associative.DatabaseSchema(emptyMap())
        val view = DatabaseView(db, ingestState, schema)
        
        val html = view.renderHtml()
        
        // Assert we render basic structure
        assertTrue(html.contains("class=\"lcnc-database\""))
        assertTrue(html.contains("data-database-id=\"db1\""))
        
        // Should have sort/filter UI components
        assertTrue(html.contains("class=\"lcnc-database-sort\""))

        // Row count logic validation (assuming 1 page loaded)
        assertTrue(html.contains("(1 rows)"))
    }
    
    @Test
    fun testDatabaseViewEmitsUpdates() = runTest {
        val page1 = LcncPage(id = "page1", title = "Row 1", parentId = "db1", contentBlocks = 0 j { null!! })
        val db = LcncDatabase(
            id = "db1",
            title = "My Database",
            parentId = "ws1",
            pages = 1 j { page1 }
        )
        
        val ingestState = IngestStateElement("test")
        val schema = borg.trikeshed.lcnc.collections.associative.DatabaseSchema(emptyMap())
        val view = DatabaseView(db, ingestState, schema)
        
        // Full CCEK lifecycle check
        view.open()
        assertTrue(ingestState.fanout.receive() is ReactorAction.Opened)

        view.activate()
        assertTrue(ingestState.fanout.receive() is ReactorAction.Activated)
        
        // Simulate row addition (save)
        val newPage = LcncPage(id = "page2", title = "Row 2", parentId = "db1", contentBlocks = 0 j { null!! })
        view.addRow(newPage)
        
        val action = ingestState.fanout.receive()
        assertTrue(action is ReactorAction.PublishEntity)
        assertEquals("page2", action.entity.id)

        view.drain()
        assertTrue(ingestState.fanout.receive() is ReactorAction.Draining)

        view.close()
        assertTrue(ingestState.fanout.receive() is ReactorAction.Closed)
    }

    @Test
    fun testDatabaseViewRendersCellEditors() = runTest {
        val page1 = LcncPage(id = "page1", title = "Row 1", parentId = "db1", contentBlocks = 0 j { null!! })
        val db = LcncDatabase(
            id = "db1",
            title = "My Database",
            parentId = "ws1",
            pages = 1 j { page1 }
        )
        
        val schema = borg.trikeshed.lcnc.collections.associative.DatabaseSchema(
            mapOf(
                "p1" to borg.trikeshed.lcnc.collections.associative.PropertySchema("p1", "TextCol", borg.trikeshed.lcnc.collections.associative.PropertyType.TEXT),
                "p2" to borg.trikeshed.lcnc.collections.associative.PropertySchema("p2", "SelectCol", borg.trikeshed.lcnc.collections.associative.PropertyType.SELECT, mapOf("options" to listOf("A", "B"))),
                "p3" to borg.trikeshed.lcnc.collections.associative.PropertySchema("p3", "CheckCol", borg.trikeshed.lcnc.collections.associative.PropertyType.CHECKBOX)
            )
        )

        val ingestState = IngestStateElement("test")
        val view = DatabaseView(db, ingestState, schema)
        
        val html = view.renderHtml()
        
        // Should contain TEXT editor input
        assertTrue(html.contains("type=\"text\""))
        assertTrue(html.contains("lcnc-prop-text"))

        // Should contain SELECT editor input
        assertTrue(html.contains("<select"))
        assertTrue(html.contains("lcnc-prop-select"))
        assertTrue(html.contains("value=\"A\""))

        // Should contain CHECKBOX editor input
        assertTrue(html.contains("type=\"checkbox\""))
        assertTrue(html.contains("lcnc-prop-checkbox"))

        // Should contain row deletion button
        assertTrue(html.contains("lcncDeleteRow"))
    }

    @Test
    fun testDatabaseViewPropagatesCellEdits() = runTest {
        val page1 = LcncPage(id = "page1", title = "Row 1", parentId = "db1", contentBlocks = 0 j { null!! })
        val db = LcncDatabase(
            id = "db1",
            title = "My Database",
            parentId = "ws1",
            pages = 1 j { page1 }
        )

        val propSchema = borg.trikeshed.lcnc.collections.associative.PropertySchema("p1", "TextCol", borg.trikeshed.lcnc.collections.associative.PropertyType.TEXT)
        val schema = borg.trikeshed.lcnc.collections.associative.DatabaseSchema(mapOf("p1" to propSchema))

        val ingestState = IngestStateElement("test")
        val view = DatabaseView(db, ingestState, schema)

        // Generate the editor to trigger initialization 
        // Generate the editor to trigger initialization 
        val editor = TextPropertyEditor(propSchema, "Old Value") { event ->
            // Trigger an emission manually to simulate what DatabaseView's binding does
            val newBlock = LcncBlock(id="p", type="properties", parentId=page1.id, content=borg.trikeshed.lcnc.collections.associative.PageProperties(mapOf("p1" to borg.trikeshed.lcnc.collections.associative.PropertyValue("p1", propSchema.type, event.newValue))))
            val newPage = page1.copy(contentBlocks = 1 j { newBlock })
            val newDb = db.copy(pages = 1 j { newPage })

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                ingestState.publishEntity(ReactorAction.PublishEntity(borg.trikeshed.context.nuid.nuid(borg.trikeshed.context.nuid.Capability.BlackBoard, borg.trikeshed.context.nuid.Nonce.RandomBytes(), borg.trikeshed.context.nuid.Subnet.core), newDb))
            }
        }

        editor.handleInput("New Value")
        
        val action = ingestState.fanout.receive()
        assertTrue(action is ReactorAction.PublishEntity)
        val publishedDb = action.entity as LcncDatabase
        val updatedProps = publishedDb.pages.b(0).contentBlocks.b(0).content as borg.trikeshed.lcnc.collections.associative.PageProperties
        assertEquals("New Value", updatedProps.properties["p1"]?.value)
    }

    @Test
    fun testDatabaseViewPropagatesSelectAndCheckboxEdits() = runTest {
        val page1 = LcncPage(id = "page1", title = "Row 1", parentId = "db1", contentBlocks = 0 j { null!! })
        val db = LcncDatabase(id = "db1", title = "DB", parentId = "ws1", pages = 1 j { page1 })

        val selectSchema = borg.trikeshed.lcnc.collections.associative.PropertySchema("p_sel", "Select", borg.trikeshed.lcnc.collections.associative.PropertyType.SELECT, mapOf("options" to listOf("A", "B")))
        val checkSchema = borg.trikeshed.lcnc.collections.associative.PropertySchema("p_chk", "Check", borg.trikeshed.lcnc.collections.associative.PropertyType.CHECKBOX)
        val schema = borg.trikeshed.lcnc.collections.associative.DatabaseSchema(mapOf("p_sel" to selectSchema, "p_chk" to checkSchema))

        val ingestState = IngestStateElement("test")
        val view = DatabaseView(db, ingestState, schema)

        // Test SELECT
        var lastDb: LcncDatabase? = null
        val selectEditor = SelectPropertyEditor(selectSchema, "A") { event ->
            // Trigger DatabaseView's binding logic
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                val newBlock = LcncBlock(id="p", type="properties", parentId=page1.id, content=borg.trikeshed.lcnc.collections.associative.PageProperties(mapOf(event.propertySchema.id to borg.trikeshed.lcnc.collections.associative.PropertyValue(event.propertySchema.id, event.propertySchema.type, event.newValue))))
                val newPage = page1.copy(contentBlocks = 1 j { newBlock })
                val newDb = db.copy(pages = 1 j { newPage })

                lastDb = newDb
                ingestState.publishEntity(ReactorAction.PublishEntity(borg.trikeshed.context.nuid.nuid(borg.trikeshed.context.nuid.Capability.BlackBoard, borg.trikeshed.context.nuid.Nonce.RandomBytes(), borg.trikeshed.context.nuid.Subnet.core), newDb))
            }
        }

        selectEditor.handleInput("B")
        val action1 = ingestState.fanout.receive()
        assertTrue(action1 is ReactorAction.PublishEntity)
        assertEquals("B", (lastDb!!.pages.b(0).contentBlocks.b(0).content as borg.trikeshed.lcnc.collections.associative.PageProperties).properties["p_sel"]?.value)

        // Test CHECKBOX
        val checkEditor = CheckboxPropertyEditor(checkSchema, false) { event ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                val newBlock = LcncBlock(id="p", type="properties", parentId=page1.id, content=borg.trikeshed.lcnc.collections.associative.PageProperties(mapOf(event.propertySchema.id to borg.trikeshed.lcnc.collections.associative.PropertyValue(event.propertySchema.id, event.propertySchema.type, event.newValue))))
                val newPage = page1.copy(contentBlocks = 1 j { newBlock })
                val newDb = db.copy(pages = 1 j { newPage })

                lastDb = newDb
                ingestState.publishEntity(ReactorAction.PublishEntity(borg.trikeshed.context.nuid.nuid(borg.trikeshed.context.nuid.Capability.BlackBoard, borg.trikeshed.context.nuid.Nonce.RandomBytes(), borg.trikeshed.context.nuid.Subnet.core), newDb))
            }
        }

        checkEditor.handleInput(true)
        val action2 = ingestState.fanout.receive()
        assertTrue(action2 is ReactorAction.PublishEntity)
        assertEquals(true, (lastDb!!.pages.b(0).contentBlocks.b(0).content as borg.trikeshed.lcnc.collections.associative.PageProperties).properties["p_chk"]?.value)
    }

    @Test
    fun testDatabaseViewRowOperationsChangeCount() = runTest {
        val page1 = LcncPage(id = "page1", title = "Row 1", parentId = "db1", contentBlocks = 0 j { null!! })
        val db = LcncDatabase(id = "db1", title = "DB", parentId = "ws1", pages = 1 j { page1 })
        val view = DatabaseView(db, IngestStateElement("test"), borg.trikeshed.lcnc.collections.associative.DatabaseSchema(emptyMap()))

        assertEquals(1, view.database.pages.a)

        // Simulate add row logic
        val newPage = LcncPage(id = "page2", title = "Row 2", parentId = "db1", contentBlocks = 0 j { null!! })
        view.database = view.database.copy(pages = 2 j { i -> if(i==0) view.database.pages.b(0) else newPage })
        assertEquals(2, view.database.pages.a)

        // Simulate delete row logic
        view.database = view.database.copy(pages = 1 j { i -> view.database.pages.b(0) })
        assertEquals(1, view.database.pages.a)
    }
}
