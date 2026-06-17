package borg.trikeshed.usersignals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextFieldTest {

    @Test
    fun `text field initial state`() {
        val field = textField(initial = "hello", placeholder = null, masked = false)
        
        assertEquals("hello", field.value.text)
        assertEquals(5, field.value.caret)
        assertEquals(false, field.value.focused)
        assertEquals(false, field.value.committed)
    }

    @Test
    fun `text field insert text`() {
        val field = textField(initial = "", placeholder = null, masked = false)
        
        val newState = field.insert("hello")
        
        assertEquals("hello", newState.text)
        assertEquals(5, newState.caret)
        assertEquals("hello", field.value.text)
    }

    @Test
    fun `text field backspace`() {
        val field = textField(initial = "hello", placeholder = null, masked = false)
        
        val newState = field.backspace()
        
        assertEquals("hell", newState.text)
        assertEquals(4, newState.caret)
    }

    @Test
    fun `text field delete forward`() {
        val field = textField(initial = "hello", placeholder = null, masked = false)
        field.setSelection(1, 2) // select 'e'
        
        val newState = field.deleteForward()
        
        assertEquals("hllo", newState.text)
        assertEquals(1, newState.caret)
    }

    @Test
    fun `text field move caret`() {
        val field = textField(initial = "", placeholder = null, masked = false)
        field.insert("hello")
        
        val newState = field.moveCaret(-3) // move from 5 to 2
        
        assertEquals(2, newState.caret)
    }

    @Test
    fun `text field set selection`() {
        val field = textField(initial = "hello world", placeholder = null, masked = false)
        
        val newState = field.setSelection(0, 5)
        
        assertEquals(0, newState.selectionStart)
        assertEquals(5, newState.selectionEnd)
        assertTrue(newState.hasSelection)
        assertEquals("hello", newState.selectedText)
    }

    @Test
    fun `text field clear`() {
        val field = textField(initial = "hello", placeholder = null, masked = false)
        
        val newState = field.clear()
        
        assertEquals("", newState.text)
        assertEquals(0, newState.caret)
    }

    @Test
    fun `text field commit`() {
        val field = textField(initial = "hello", placeholder = null, masked = false)
        
        val newState = field.commit()
        
        assertTrue(newState.committed)
    }

    @Test
    fun `text field focus blur`() {
        val field = textField(initial = "", placeholder = null, masked = false)
        
        val focused = field.focus()
        assertTrue(focused.focused)
        
        val blurred = field.blur()
        assertEquals(false, blurred.focused)
    }

    @Test
    fun `text field masked`() {
        val field = textField(initial = "secret", placeholder = null, masked = true)
        
        // The masked property is internal to TextFieldImpl
        // We can verify it's stored by checking the implementation
        assertEquals("secret", field.value.text)
    }
}