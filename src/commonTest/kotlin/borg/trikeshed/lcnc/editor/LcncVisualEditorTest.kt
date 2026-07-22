/*
 * Copyright (c) TrikeShed Contributors
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 */
package borg.trikeshed.lcnc.editor

import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.ccek.IngestStateElement
import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.lib.j
import borg.trikeshed.lcnc.collections.associative.PropertySchema
import borg.trikeshed.lcnc.collections.associative.PropertyType
import borg.trikeshed.lcnc.collections.associative.DatabaseSchema
import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.isam.LcncPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.fail

class LcncVisualEditorTest {

    @Test
    fun `BlockEditor supports CRUD operations on text`() = runTest {
        val block = LcncBlock(id = "b1", type = "paragraph", parentId = null, content = "Hello")
        val ingestState = IngestStateElement("test")
        val editor = BlockEditor(block, ingestState)
        
        editor.open()
        assertTrue(ingestState.fanout.receive() is ReactorAction.Opened)
        editor.activate()
        assertTrue(ingestState.fanout.receive() is ReactorAction.Activated)
        
        editor.updateContent("World")
        val action = ingestState.fanout.receive()
        assertTrue(action is ReactorAction.PublishEntity)
        assertEquals("World", (action.entity as LcncBlock).content)
    }

    @Test
    fun `BlockEditor handles drag-and-drop of blocks in canvas`() = runTest {
        val child1 = LcncBlock(id = "c1", type = "paragraph", parentId = "p1", content = "Child 1")
        val child2 = LcncBlock(id = "c2", type = "paragraph", parentId = "p1", content = "Child 2")
        val parent = LcncBlock(
            id = "p1", 
            type = "group", 
            parentId = null, 
            children = 2 j { i -> if (i == 0) child1 else child2 },
            content = "Parent"
        )
        
        val ingestState = IngestStateElement("test")
        val editor = BlockEditor(parent, ingestState)
        val html = editor.renderHtml()
        
        // ensure attributes needed for drag/drop or ordering exist
        assertTrue(html.contains("lcncMoveBlockUp"))
        assertTrue(html.contains("lcncMoveBlockDown"))
    }

    @Test
    fun `BlockEditor persists state to Database records`() = runTest {
        val page1 = LcncPage(id = "page1", title = "Row 1", parentId = "db1", contentBlocks = 0 j { null!! })
        val db = LcncDatabase(
            id = "db1",
            title = "My Database",
            parentId = "ws1",
            pages = 1 j { page1 }
        )
        
        val ingestState = IngestStateElement("test")
        val schema = DatabaseSchema(emptyMap())
        val view = DatabaseView(db, ingestState, schema)
        
        view.open()
        assertTrue(ingestState.fanout.receive() is ReactorAction.Opened)
        view.activate()
        assertTrue(ingestState.fanout.receive() is ReactorAction.Activated)

        val newPage = LcncPage(id = "page2", title = "Row 2", parentId = "db1", contentBlocks = 0 j { null!! })
        view.addRow(newPage)
        val action = ingestState.fanout.receive()
        assertTrue(action is ReactorAction.PublishEntity)
        assertEquals("page2", action.entity.id)
    }

    @Test
    fun `DatabaseView displays records as editable blocks`() = runTest {
        val page1 = LcncPage(id = "page1", title = "Row 1", parentId = "db1", contentBlocks = 0 j { null!! })
        val db = LcncDatabase(id = "db1", title = "DB", parentId = "ws1", pages = 1 j { page1 })
        
        val schema = DatabaseSchema(mapOf(
            "p1" to PropertySchema("p1", "TextCol", PropertyType.TEXT)
        ))

        val ingestState = IngestStateElement("test")
        val view = DatabaseView(db, ingestState, schema)
        val html = view.renderHtml()
        
        assertTrue(html.contains("type=\"text\""))
        assertTrue(html.contains("lcnc-prop-text"))
    }

    @Test
    fun `PropertyEditor handles NAME property correctly`() {
        val schema = PropertySchema("n1", "Name", PropertyType.TEXT)
        val editor = TextPropertyEditor(schema, "John Doe")
        val html = editor.renderHtml()
        assertTrue(html.contains("type=\"text\""))
        assertTrue(html.contains("value=\"John Doe\""))
        assertTrue(editor.validate("Jane Doe"))
    }

    @Test
    fun `PropertyEditor handles EMAIL property correctly`() {
        val schema = PropertySchema("e1", "Email", PropertyType.EMAIL)
        val editor = EmailPropertyEditor(schema, "test@example.com")
        val html = editor.renderHtml()
        assertTrue(html.contains("type=\"email\""))
        assertTrue(html.contains("value=\"test@example.com\""))
        assertTrue(editor.validate("valid@email.com"))
        assertFalse(editor.validate("invalid-email"))
    }

    @Test
    fun `PropertyEditor handles PHONE_NUMBER property correctly`() {
        val schema = PropertySchema("ph1", "Phone", PropertyType.PHONE_NUMBER)
        val editor = PhonePropertyEditor(schema, "+1-555-1234")
        val html = editor.renderHtml()
        assertTrue(html.contains("type=\"tel\""))
        assertTrue(html.contains("value=\"+1-555-1234\""))
        assertTrue(editor.validate("+1 (555) 555-5555"))
        assertFalse(editor.validate("123"))
    }
}
