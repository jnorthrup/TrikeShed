package borg.trikeshed.windowtoolkit

import borg.trikeshed.windowtoolkit.dsl.windowContext
import borg.trikeshed.windowtoolkit.internal.Toggle
import borg.trikeshed.windowtoolkit.internal.Slider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}