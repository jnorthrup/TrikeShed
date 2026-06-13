# User Signal Architecture - Window Toolkit Integration

## Overview

User signals + window toolkit = reactive UI from signal algebra.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        USER SIGNAL ARCHITECTURE                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     │
│  │   SIGNAL    │────▶│   WIDGET     │────▶│    WINDOW    │     │
│  │  Algebra    │     │   Renderer   │     │  Platform    │     │
│  └──────────────┘     └──────────────┘     └──────────────┘     │
│        │                     │                     │                  │
│        ▼                     ▼                     ▼                  │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     │
│  │   0D/1D      │     │   Layout    │     │   Input      │     │
│  │  Components  │     │   Engine    │     │  Dispatcher  │     │
│  └──────────────┘     └──────────────┘     └──────────────┘     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Signal Types (Existing)

### 0D Signals (Boolean)
| Signal | Description | Events |
|--------|-------------|--------|
| `Toggle` | On/off switch | toggle(), turnOn(), turnOff() |
| `IdiotLight` | Status indicator | flash(), pulse() |
| `MomentaryButton` | Press-and-release | press(), release(), tap() |
| `RadioToggle<T>` | Multi-option select | select(), clear() |

### 1D Signals (Continuous)
| Signal | Description | Events |
|--------|-------------|--------|
| `Slider` | Continuous range | setValue(), increment(), decrement() |
| `Knob` | Discrete steps with detents | rotateBy(), snapToDetent() |
| `Dial<T>` | Enumerated positions | next(), previous(), goto() |
| `LevelMeter` | Audio-style meter | setLevel(), resetPeak() |

## Widget System (New)

```kotlin
/**
 * Widget - UI component bound to a Signal.
 * Renders signal value to platform graphics.
 */
interface Widget<T> {
    val signal: Signal<T>
    fun render(value: T, ctx: RenderContext): Rendered
}

/**
 * Composable widget that combines multiple widgets.
 */
interface CompositeWidget : Widget<Unit> {
    val children: List<Widget<*>>
}

/**
 * Layout widget arranges children.
 */
interface LayoutWidget : CompositeWidget {
    val layout: Layout
}

/**
 * Layout algorithms.
 */
sealed class Layout {
    data class Row(val spacing: Double = 0.0) : Layout()
    data class Column(val spacing: Double = 0.0) : Layout()
    data class Grid(val rows: Int, val cols: Int) : Layout()
    data class Flex(val direction: Direction, val wrap: Boolean = false) : Layout()
    data class Absolute(val children: List<Positioned>) : Layout()
}
```

## Widget Implementations

```kotlin
// 0D Widgets
class ToggleWidget(override val signal: Toggle) : Widget<Boolean>
class LightWidget(override val signal: IdiotLight) : Widget<Boolean>
class ButtonWidget(override val signal: MomentaryButton) : Widget<Boolean>
class RadioWidget<T>(override val signal: RadioToggle<T>) : Widget<T>

// 1D Widgets
class SliderWidget(override val signal: Slider) : Widget<Double>
class KnobWidget(override val signal: Knob) : Widget<Double>
class DialWidget<T>(override val signal: Dial<T>) : Widget<T>
class MeterWidget(override val signal: LevelMeter) : Widget<Double>

// Container Widgets
class PanelWidget(override val children: List<Widget<*>>, val layout: Layout) : LayoutWidget
class WindowWidget(val title: String, override val children: List<Widget<*>>) : LayoutWidget
```

## Window Platform

```kotlin
/**
 * Window - platform-native container.
 */
interface Window {
    val title: String
    val size: Size
    val position: Point
    
    suspend fun open()
    suspend fun close()
    suspend fun resize(width: Double, height: Double)
    suspend fun move(x: Double, y: Double)
    
    fun <T> attach(widget: Widget<T>): WidgetHandle<T>
}

/**
 * Window factory.
 */
interface WindowFactory {
    suspend fun create(title: String, size: Size): Window
    suspend fun createChild(parent: Window, title: String, size: Size): Window
}

/**
 * Platform backends.
 */
class SwingWindowFactory : WindowFactory
class JavaFxWindowFactory : WindowFactory
class AwtWindowFactory : WindowFactory
```

## Input Dispatcher

```kotlin
/**
 * Input events → Signal mutations.
 */
interface InputDispatcher {
    fun dispatch(event: InputEvent): Boolean
    
    fun bindMouse(widget: Widget<*>, handler: (MouseEvent) -> Unit)
    fun bindKeyboard(widget: Widget<*>, handler: (KeyEvent) -> Unit)
}

/**
 * Input events.
 */
sealed class InputEvent {
    data class Mouse(val x: Double, val y: Double, val button: MouseButton, val clickCount: Int) : InputEvent()
    data class Key(val code: KeyCode, val modifiers: Set<KeyModifier>, val pressed: Boolean) : InputEvent()
    data class Scroll(val deltaX: Double, val deltaY: Double) : InputEvent()
}
```

## Complete Example

```kotlin
// Create signal sources
val powerToggle = toggle(false)
val volumeSlider = slider(0.0, 100.0, 50.0)
val freqKnob = knob(20.0, 20000.0, 1000.0, 12)

// Bind signals to widgets
val powerWidget = ToggleWidget(powerToggle)
val volumeWidget = SliderWidget(volumeSlider)
val freqWidget = KnobWidget(freqKnob)

// Compose layout
val panel = PanelWidget(
    children = listOf(powerWidget, volumeWidget, freqWidget),
    layout = Layout.Column(spacing = 8.0)
)

// Create window
val window = SwingWindowFactory().create("Audio Mixer", Size(400, 300))
window.attach(panel)

// React to changes
volumeSlider.changes.collect { volume ->
    println("Volume: $volume")
}
```

## Architecture Layers

```
┌────────────────────────────────────────────────────────────┐
│                      APPLICATION LAYER                       │
│  (Widgets, Layout, Signal Composition)                   │
├────────────────────────────────────────────────────────────┤
│                       RENDER LAYER                         │
│  (RenderContext, Graphics2D, Scene Graph)               │
├────────────────────────────────────────────────────────────┤
│                      PLATFORM LAYER                         │
│  (Swing, JavaFX, AWT, WebGL, HTML5 Canvas)              │
├────────────────────────────────────────────────────────────┤
│                      INPUT LAYER                            │
│  (Mouse, Keyboard, Touch, Gamepad)                       │
└────────────────────────────────────────────────────────────┘
```

## CCEK Integration

```kotlin
/**
 * Window runs in its own CCEK context.
 */
class WindowContextElement : AsyncContextElement {
    val window: Window
    val inputDispatcher: InputDispatcher
    val signalContext: SignalContextElement
    
    companion object Key : CoroutineContext.Key<WindowContextElement>
}

// Signals flow through CCEK
suspend fun runWindow() = coroutineScope {
    val ctx = WindowContextElement(window, dispatcher, signals)
    withContext(ctx) {
        window.open()
        // Signals auto-update UI
    }
}
```

## Implementation Plan

1. **Phase 1**: Widget interfaces + base implementations
2. **Phase 2**: Layout engine (Row, Column, Grid)
3. **Phase 3**: Window factory (Swing)
4. **Phase 4**: Input dispatcher
5. **Phase 5**: CCEK integration