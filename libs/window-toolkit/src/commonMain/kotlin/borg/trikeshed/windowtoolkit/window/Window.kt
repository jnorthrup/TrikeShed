package borg.trikeshed.windowtoolkit.window

import borg.trikeshed.windowtoolkit.widgets.*

/**
 * Window - platform-native container for widgets.
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
    
    fun render()
}

/**
 * Handle to attached widget.
 */
interface WidgetHandle<T> {
    val widget: Widget<T>
    fun detach()
}

/**
 * Window factory - creates platform windows.
 */
interface WindowFactory {
    suspend fun create(title: String, size: Size, root: Widget<*>): Window
    suspend fun createChild(parent: Window, title: String, size: Size, root: Widget<*>): Window
}

/**
 * Input event types.
 */
sealed class InputEvent {
    data class Mouse(val x: Double, val y: Double, val button: MouseButton, val clickCount: Int, val pressed: Boolean) : InputEvent()
    data class Key(val code: KeyCode, val modifiers: Set<KeyModifier>, val pressed: Boolean) : InputEvent()
    data class Scroll(val deltaX: Double, val deltaY: Double) : InputEvent()
}

enum class MouseButton { Left, Right, Middle }
enum class KeyCode { A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, Enter, Escape, Space, Tab, Shift, Control, Alt, Up, Down, Left, Right, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 }
enum class KeyModifier { Shift, Control, Alt, Meta }

/**
 * Input dispatcher - routes input to widgets.
 */
class InputDispatcher {
    private val mouseHandlers = mutableMapOf<Widget<*>, (InputEvent.Mouse) -> Unit>()
    private val keyboardHandlers = mutableMapOf<Widget<*>, (InputEvent.Key) -> Unit>()
    
    fun dispatch(event: InputEvent): Boolean {
        return when (event) {
            is InputEvent.Mouse -> {
                mouseHandlers.entries.any { (_, handler) ->
                    handler(event)
                    true
                }
            }
            is InputEvent.Key -> {
                keyboardHandlers.entries.any { (_, handler) ->
                    handler(event)
                    true
                }
            }
            is InputEvent.Scroll -> false
        }
    }
    
    fun bindMouse(widget: Widget<*>, handler: (InputEvent.Mouse) -> Unit) {
        mouseHandlers[widget] = handler
    }
    
    fun bindKeyboard(widget: Widget<*>, handler: (InputEvent.Key) -> Unit) {
        keyboardHandlers[widget] = handler
    }
    
    fun unbindMouse(widget: Widget<*>) {
        mouseHandlers.remove(widget)
    }
    
    fun unbindKeyboard(widget: Widget<*>) {
        keyboardHandlers.remove(widget)
    }
}

/**
 * Swing implementation.
 */
class SwingWindowFactory : WindowFactory {
    override suspend fun create(title: String, size: Size, root: Widget<*>): Window = SwingWindow(title, size, root)
    override suspend fun createChild(parent: Window, title: String, size: Size, root: Widget<*>): Window = SwingWindow(title, size, root)
}

class SwingWindow(
    override val title: String,
    override val size: Size,
    private val root: Widget<*>
) : Window {
    
    private var _position = Point(100.0, 100.0)
    override val position: Point get() = _position
    
    private val handles = mutableMapOf<Widget<*>, WidgetHandle<*>>()
    private val dispatcher = InputDispatcher()
    
    override suspend fun open() {
        // In real impl: create JFrame, attach listeners
        println("Opening window: $title ${size.width}x${size.height}")
    }
    
    override suspend fun close() {
        println("Closing window: $title")
    }
    
    override suspend fun resize(width: Double, height: Double) {
        println("Resizing window: $title to ${width}x${height}")
    }
    
    override suspend fun move(x: Double, y: Double) {
        _position = Point(x, y)
        println("Moving window: $title to ($x, $y)")
    }
    
    override fun <T> attach(widget: Widget<T>): WidgetHandle<T> {
        val handle = SwingWidgetHandle(widget)
        handles[widget] = handle
        return handle
    }
    
    override fun render() {
        val ctx = object : RenderContext {
            override val width: Double = size.width
            override val height: Double = size.height
            
            override fun drawRect(x: Double, y: Double, w: Double, h: Double, color: Color, filled: Boolean) {
                println("  DrawRect($x, $y, $w, $h) $color filled=$filled")
            }
            
            override fun drawText(text: String, x: Double, y: Double, color: Color, fontSize: Double) {
                println("  Text('$text' at ($x, $y) size=$fontSize)")
            }
            
            override fun drawLine(x1: Double, y1: Double, x2: Double, y2: Double, color: Color, strokeWidth: Double) {
                println("  Line(($x1,$y1) -> ($x2,$y2) stroke=$strokeWidth)")
            }
            
            override fun drawCircle(cx: Double, cy: Double, radius: Double, color: Color, filled: Boolean) {
                println("  Circle(($cx,$cy) r=$radius filled=$filled)")
            }
        }
        
        root.render(Unit, ctx)
    }
}

class SwingWidgetHandle<T>(override val widget: Widget<T>) : WidgetHandle<T> {
    override fun detach() {
        println("Detaching widget: $widget")
    }
}

/**
 * Example: Create audio mixer UI.
 */
fun main() = runBlocking {
    // Create signals
    val power = toggle(false)
    val volume = slider(0.0, 100.0, 50.0)
    val bass = knob(-12.0, 12.0, 0.0, 24)
    val treble = knob(-12.0, 12.0, 0.0, 24)
    val inputSelect = radioToggle(listOf("Mic", "Line", "USB"), "Line")
    
    // Create widgets
    val powerWidget = ToggleWidget(power)
    val volumeWidget = SliderWidget(volume)
    val bassWidget = KnobWidget(bass)
    val trebleWidget = KnobWidget(treble)
    val inputWidget = RadioWidget(inputSelect)
    
    // Create layout
    val mixerPanel = PanelWidget(
        children = listOf(
            powerWidget,
            inputWidget,
            volumeWidget,
            PanelWidget(
                children = listOf(bassWidget, trebleWidget),
                layout = Layout.Row(8.0)
            )
        ),
        layout = Layout.Column(16.0),
        padding = 16.0
    )
    
    // Create window
    val factory = SwingWindowFactory()
    val window = factory.create("Audio Mixer", Size(300, 250), mixerPanel)
    window.attach(mixerPanel)
    
    // Open and render
    window.open()
    window.render()
    
    // Simulate signal changes
    delay(100)
    volume.setValue(75.0)
    window.render()
    
    delay(100)
    bass.rotateBy(2.0)
    window.render()
    
    delay(100)
    window.close()
}

private fun <T> kotlinx.coroutines.runBlocking(block: suspend () -> T): T = 
    kotlinx.coroutines.runBlocking { block() }