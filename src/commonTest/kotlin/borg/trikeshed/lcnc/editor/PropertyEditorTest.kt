/*
 * Copyright (c) TrikeShed Contributors
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 */
package borg.trikeshed.lcnc.editor

import borg.trikeshed.lcnc.collections.associative.PropertySchema
import borg.trikeshed.lcnc.collections.associative.PropertyType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PropertyEditorTest {
    @Test
    fun testTextPropertyEditor() {
        val schema = PropertySchema("1", "Name", PropertyType.TEXT)
        val editor = TextPropertyEditor(schema, "John Doe")
        val html = editor.renderHtml()
        assertTrue(html.contains("type=\"text\""))
        assertTrue(html.contains("value=\"John Doe\""))
        assertTrue(editor.validate("Jane Doe"))
    }

    @Test
    fun testSelectPropertyEditor() {
        val schema = PropertySchema("2", "Status", PropertyType.SELECT, mapOf("options" to listOf("To Do", "In Progress", "Done")))
        val editor = SelectPropertyEditor(schema, "To Do")
        val html = editor.renderHtml()
        assertTrue(html.contains("<select"))
        assertTrue(html.contains("value=\"To Do\" selected"))
        assertTrue(html.contains("value=\"In Progress\""))
        assertTrue(editor.validate("Done"))
        assertFalse(editor.validate("Not Started"), "Should reject invalid option")
    }

    @Test
    fun testCheckboxPropertyEditor() {
        val schema = PropertySchema("3", "Done", PropertyType.CHECKBOX)
        val editor = CheckboxPropertyEditor(schema, true)
        val html = editor.renderHtml()
        assertTrue(html.contains("type=\"checkbox\""))
        assertTrue(html.contains("checked"))
        assertTrue(editor.validate(false))
    }

    @Test
    fun testEmailPropertyEditorValidation() {
        val schema = PropertySchema("4", "Email", PropertyType.EMAIL)
        val editor = EmailPropertyEditor(schema, "test@example.com")
        assertTrue(editor.validate("valid@email.com"))
        assertFalse(editor.validate("invalid-email"))
    }

    @Test
    fun testUrlPropertyEditorValidation() {
        val schema = PropertySchema("5", "Website", PropertyType.URL)
        val editor = UrlPropertyEditor(schema, "https://example.com")
        assertTrue(editor.validate("http://test.com"))
        assertFalse(editor.validate("not-a-url"))
    }

    @Test
    fun testPhonePropertyEditorValidation() {
        val schema = PropertySchema("6", "Phone", PropertyType.PHONE_NUMBER)
        val editor = PhonePropertyEditor(schema, "+1-555-1234")
        assertTrue(editor.validate("+1 (555) 555-5555"))
        assertFalse(editor.validate("123"))
    }

    @Test
    fun testMultiSelectPropertyEditor() {
        val schema = PropertySchema(
            "7", "Tags", PropertyType.MULTI_SELECT,
            mapOf("options" to listOf("Tag A", "Tag B", "Tag C"))
        )
        val editor = MultiSelectPropertyEditor(schema, listOf("Tag A", "Tag C"))

        val html = editor.renderHtml()
        assertTrue(html.contains("<select multiple"))
        assertTrue(html.contains("value=\"Tag A\" selected"))
        assertTrue(html.contains("value=\"Tag C\" selected"))
        assertTrue(html.contains("value=\"Tag B\">")) // Note: NOT selected

        assertTrue(editor.validate(listOf("Tag B")))
        assertTrue(editor.validate(listOf("Tag A", "Tag B")))
        assertFalse(editor.validate(listOf("Tag D")), "Should reject unconfigured option")
        assertFalse(editor.validate("Not a list"), "Should reject non-list input")
    }
}
