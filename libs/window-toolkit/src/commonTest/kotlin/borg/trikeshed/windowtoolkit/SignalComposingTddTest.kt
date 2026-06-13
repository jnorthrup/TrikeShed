import borg.trikeshed.windowtoolkit.dsl.windowContext
import borg.trikeshed.windowtoolkit.internal.Toggle
import borg.trikeshed.windowtoolkit.internal.Slider
import borg.trikeshed.windowtoolkit.internal.TextField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for Window Toolkit - signal-driven UI composing.
 */
class SignalComposingTddTest {

    @Test
    fun `create toggle signals`() {
        val shell = windowContext {
            // Create individual signals
            val enabled = toggle("channel.0", false)
            val toggle1 = toggle("channel.1", true)
            val toggle2 = toggle("channel.2", false)
            
            assertEquals(false, enabled.value)
            assertEquals(true, toggle1.value)
            assertEquals(false, toggle2.value)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `create slider signals`() {
        val shell = windowContext {
            val freq440 = slider("freq.440", 20.0, 20000.0, 440.0, 1.0)
            val freq880 = slider("freq.880", 20.0, 20000.0, 880.0, 1.0)
            
            assertEquals(440.0, freq440.value)
            assertEquals(880.0, freq880.value)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `create text field signals`() {
        val shell = windowContext {
            val searchField = textField("search", "")
            val commandField = textField("command", "initial")
            
            assertEquals("", searchField.value.text)
            assertEquals("initial", commandField.value.text)
            assertEquals(0, searchField.value.caret)
            assertEquals(7, commandField.value.caret)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `toggle value can be changed`() {
        val shell = windowContext {
            val toggle = toggle("test.toggle", false)
            
            // Toggle returns new value
            val newValue = toggle.toggle()
            
            assertEquals(true, newValue)
            assertEquals(true, toggle.value)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `slider normalized value`() {
        val shell = windowContext {
            val slider = slider("test.slider", 0.0, 100.0, 50.0)
            
            // 50.0 is halfway between 0 and 100, so normalized is 0.5
            assertEquals(0.5, slider.normalized)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `slider set value`() {
        val shell = windowContext {
            val slider = slider("test.slider", 0.0, 100.0, 0.0)
            
            val newValue = slider.setValue(75.0)
            
            assertEquals(75.0, newValue)
            assertEquals(75.0, slider.value)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `slider increment decrement`() {
        val shell = windowContext {
            val slider = slider("test.slider", 0.0, 100.0, 50.0, 10.0)
            
            val inc = slider.increment()
            val dec = slider.decrement()
            
            assertEquals(60.0, inc)
            assertEquals(50.0, dec)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `text field insert text`() {
        val shell = windowContext {
            val field = textField("test.field", "")
            
            val newState = field.insert("hello")
            
            assertEquals("hello", newState.text)
            assertEquals(5, newState.caret)
            assertEquals("hello", field.value.text)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `text field backspace`() {
        val shell = windowContext {
            val field = textField("test.field", "hello")
            
            val newState = field.backspace()
            
            assertEquals("hell", newState.text)
            assertEquals(4, newState.caret)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `text field delete forward`() {
        val shell = windowContext {
            val field = textField("test.field", "hello")
            field.setSelection(1, 2) // select 'e'
            
            val newState = field.deleteForward()
            
            assertEquals("hllo", newState.text)
            assertEquals(1, newState.caret)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `text field move caret`() {
        val shell = windowContext {
            val field = textField("test.field", "hello")
            
            val newState = field.moveCaret(2)
            
            assertEquals(2, newState.caret)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `text field set selection`() {
        val shell = windowContext {
            val field = textField("test.field", "hello world")
            
            val newState = field.setSelection(0, 5)
            
            assertEquals(0, newState.selectionStart)
            assertEquals(5, newState.selectionEnd)
            assertTrue(newState.hasSelection)
            assertEquals("hello", newState.selectedText)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `text field clear`() {
        val shell = windowContext {
            val field = textField("test.field", "hello")
            
            val newState = field.clear()
            
            assertEquals("", newState.text)
            assertEquals(0, newState.caret)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `text field commit`() {
        val shell = windowContext {
            val field = textField("test.field", "hello")
            
            val newState = field.commit()
            
            assertTrue(newState.committed)
        }
        
        assertNotNull(shell)
    }

    @Test
    fun `text field focus blur`() {
        val shell = windowContext {
            val field = textField("test.field", "")
            
            val focused = field.focus()
            assertTrue(focused.focused)
            
            val blurred = field.blur()
            assertEquals(false, blurred.focused)
        }
        
        assertNotNull(shell)
    }
}