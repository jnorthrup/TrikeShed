package borg.trikeshed.windowtoolkit.widgets

import borg.trikeshed.windowtoolkit.internal.*
import kotlin.math.max
import kotlin.math.min

/**
 * Widget - UI component bound to a Signal.
 * Renders signal value to platform graphics.
 */
interface Widget<out T> {
    val signal: Signal<T>
    fun render(value: T, ctx: RenderContext): Rendered
}

/**
 * Composite widget with children.
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
    data class Row(val spacing: Double = 0.0, val alignment: Alignment = Alignment.Start) : Layout()
    data class Column(val spacing: Double = 0.0, val alignment: Alignment = Alignment.Start) : Layout()
    data class Grid(val rows: Int, val cols: Int, val cellWidth: Double = 100.0, val cellHeight: Double = 30.0) : Layout()
    data class Flex(val direction: FlexDirection = FlexDirection.Row, val wrap: Boolean = false, val spacing: Double = 0.0) : Layout()
    data class Absolute(val children: List<Positioned<*>>) : Layout()
    
    enum class FlexDirection { Row, Column }
    enum class Alignment { Start, Center, End, Stretch }
}

data class Positioned<T>(val widget: Widget<T>, val x: Double, val y: Double, val width: Double? = null, val height: Double? = null)

/**
 * Render context for widgets.
 */
interface RenderContext {
    val width: Double
    val height: Double
    fun drawRect(x: Double, y: Double, w: Double, h: Double, color: Color, filled: Boolean = true)
    fun drawText(text: String, x: Double, y: Double, color: Color, fontSize: Double = 12.0)
    fun drawLine(x1: Double, y1: Double, x2: Double, y2: Double, color: Color, strokeWidth: Double = 1.0)
    fun drawCircle(cx: Double, cy: Double, radius: Double, color: Color, filled: Boolean = true)
}

data class Color(val r: Int, val g: Int, val b: Int, val a: Int = 255) {
    companion object {
        val Black = Color(0, 0, 0)
        val White = Color(255, 255, 255)
        val Red = Color(255, 0, 0)
        val Green = Color(0, 255, 0)
        val Blue = Color(0, 0, 255)
        val Gray = Color(128, 128, 128)
        val LightGray = Color(200, 200, 200)
        val DarkGray = Color(64, 64, 64)
    }
}

data class Size(val width: Double, val height: Double)
data class Point(val x: Double, val y: Double)

/**
 * Rendered output.
 */
data class Rendered(val commands: List<RenderCommand>)

sealed class RenderCommand {
    data class FillRect(val x: Double, val y: Double, val w: Double, val h: Double, val color: Color) : RenderCommand()
    data class DrawText(val text: String, val x: Double, val y: Double, val color: Color, val fontSize: Double) : RenderCommand()
    data class DrawLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val color: Color, val stroke: Double) : RenderCommand()
    data class DrawCircle(val cx: Double, val cy: Double, val r: Double, val color: Color, val filled: Boolean) : RenderCommand()
}

// ====================================================================
// 0D WIDGETS
// ====================================================================

/**
 * Toggle button widget.
 */
class ToggleWidget(override val signal: Toggle) : Widget<Boolean> {
    override fun render(value: Boolean, ctx: RenderContext): Rendered {
        val color = if (value) Color.Green else Color.Gray
        return Rendered(listOf(
            RenderCommand.FillRect(0.0, 0.0, 40.0, 20.0, color),
            RenderCommand.DrawText(if (value) "ON" else "OFF", 5.0, 15.0, Color.White, 10.0)
        ))
    }
}

/**
 * Idiot light / status LED.
 */
class LightWidget(override val signal: IdiotLight) : Widget<Boolean> {
    override fun render(value: Boolean, ctx: RenderContext): Rendered {
        val color = if (value) Color.Red else Color.DarkGray
        return Rendered(listOf(
            RenderCommand.DrawCircle(10.0, 10.0, 8.0, color, filled = true)
        ))
    }
}

/**
 * Momentary button widget.
 */
class ButtonWidget(override val signal: MomentaryButton, val label: String = "Press") : Widget<Boolean> {
    override fun render(value: Boolean, ctx: RenderContext): Rendered {
        val color = if (value) Color.Blue else Color.LightGray
        return Rendered(listOf(
            RenderCommand.FillRect(0.0, 0.0, 60.0, 24.0, color),
            RenderCommand.DrawText(label, 5.0, 16.0, Color.Black, 10.0)
        ))
    }
}

/**
 * Radio toggle widget.
 */
class <T> RadioWidget(override val signal: RadioToggle<T>, val labels: Map<T, String> = emptyMap()) : Widget<T> {
    override fun render(value: T, ctx: RenderContext): Rendered {
        val label = labels[value] ?: value.toString()
        return Rendered(listOf(
            RenderCommand.DrawText(label, 0.0, 12.0, Color.Black, 12.0)
        ))
    }
}

// ====================================================================
// 1D WIDGETS
// ====================================================================

/**
 * Slider widget.
 */
class SliderWidget(override val signal: Slider) : Widget<Double> {
    override fun render(value: Double, ctx: RenderContext): Rendered {
        val normalized = (value - signal.min) / (signal.max - signal.min)
        val thumbX = normalized * 100.0
        return Rendered(listOf(
            RenderCommand.DrawLine(0.0, 10.0, 100.0, 10.0, Color.Gray, 2.0),
            RenderCommand.DrawCircle(thumbX, 10.0, 5.0, Color.Blue, filled = true),
            RenderCommand.DrawText(value.toInt().toString(), 45.0, 25.0, Color.Black, 8.0)
        ))
    }
}

/**
 * Knob widget.
 */
class KnobWidget(override val signal: Knob, val size: Double = 40.0) : Widget<Double> {
    override fun render(value: Double, ctx: RenderContext): Rendered {
        val normalized = (value - signal.min) / (signal.max - signal.min)
        val angle = normalized * 270.0 - 135.0 // -135 to 135 degrees
        return Rendered(listOf(
            RenderCommand.DrawCircle(size/2, size/2, size/2 - 2, Color.LightGray, filled = false),
            RenderCommand.DrawLine(size/2, size/2, size/2 + 20 * kotlin.math.cos(Math.toRadians(angle)), size/2 + 20 * kotlin.math.sin(Math.toRadians(angle)), Color.Black, 2.0)
        ))
    }
}

/**
 * Dial widget.
 */
class <T> DialWidget(override val signal: Dial<T>, val labels: Map<T, String> = emptyMap()) : Widget<T> {
    override fun render(value: T, ctx: RenderContext): Rendered {
        val label = labels[value] ?: value.toString()
        return Rendered(listOf(
            RenderCommand.DrawText(label, 0.0, 12.0, Color.Black, 14.0)
        ))
    }
}

/**
 * Level meter widget.
 */
class MeterWidget(override val signal: LevelMeter, val width: Double = 20.0, val height: Double = 100.0) : Widget<Double> {
    override fun render(value: Double, ctx: RenderContext): Rendered {
        val levelHeight = value * height
        return Rendered(listOf(
            RenderCommand.FillRect(0.0, height - levelHeight, width, levelHeight, Color.Green),
            RenderCommand.DrawRect(0.0, 0.0, width, height, Color.Black, filled = false)
        ))
    }
}

/**
 * Text field widget.
 */
class TextFieldWidget(override val signal: TextField, val width: Double = 200.0, val height: Double = 24.0) : Widget<TextFieldState> {
    override fun render(state: TextFieldState, ctx: RenderContext): Rendered {
        val masked = if (signal is TextFieldImpl) signal.masked else false
        val displayText = if (masked) "●".repeat(state.text.length) else state.text
        val caretX = if (state.text.isEmpty()) 4.0 else 4.0 + (displayText.substring(0, min(state.caret, state.text.length)).length * 7.0)
        
        val commands = mutableListOf<RenderCommand>(
            RenderCommand.FillRect(0.0, 0.0, width, height, if (state.focused) Color.White else Color.LightGray),
            RenderCommand.DrawRect(0.0, 0.0, width, height, if (state.focused) Color.Blue else Color.Gray, filled = false),
            RenderCommand.DrawText(displayText, 4.0, height - 4.0, Color.Black, 12.0)
        )
        
        if (state.focused && state.hasSelection) {
            val selStartX = 4.0 + (displayText.substring(0, min(state.selectionStart, state.text.length)).length * 7.0)
            val selEndX = 4.0 + (displayText.substring(0, min(state.selectionEnd, state.text.length)).length * 7.0)
            val left = min(selStartX, selEndX)
            val right = max(selStartX, selEndX)
            commands.add(RenderCommand.FillRect(left, 2.0, right - left, height - 4.0, Color(0, 120, 215, 100)))
        }
        
        if (state.focused && !state.hasSelection) {
            commands.add(RenderCommand.DrawLine(caretX, 4.0, caretX, height - 4.0, Color.Blue, 1.0))
        }
        
        return Rendered(commands)
    }
}

// ====================================================================
// CONTAINER WIDGETS
// ====================================================================

/**
 * Panel with layout.
 */
class PanelWidget(
    override val children: List<Widget<*>>,
    override val layout: Layout,
    val padding: Double = 8.0,
    val background: Color = Color.White
) : LayoutWidget {
    override val signal: Signal<Unit> = signalOf(Unit)
    
    override fun render(value: Unit, ctx: RenderContext): Rendered {
        val commands = mutableListOf<RenderCommand>()
        var x = padding
        var y = padding
        
        when (layout) {
            is Layout.Row -> {
                children.forEach { child ->
                    val rendered = child.render(Unit, ctx)
                    commands.addAll(rendered.commands.map { shift(it, x, y) })
                    x += rendered.size(ctx).first + layout.spacing
                }
            }
            is Layout.Column -> {
                children.forEach { child ->
                    val rendered = child.render(Unit, ctx)
                    commands.addAll(rendered.commands.map { shift(it, x, y) })
                    y += rendered.size(ctx).second + layout.spacing
                }
            }
            is Layout.Grid -> {
                val spacing = 0.0 // Grid doesn't have spacing property
                var col = 0
                var row = 0
                children.forEach { child ->
                    val cx = col * (layout.cellWidth + spacing)
                    val cy = row * (layout.cellHeight + spacing)
                    val rendered = child.render(Unit, ctx)
                    commands.addAll(rendered.commands.map { shift(it, cx, cy) })
                    col++
                    if (col >= layout.cols) { col = 0; row++ }
                }
            }
            is Layout.Flex -> {
                val dir = layout.direction
                children.forEach { child ->
                    val rendered = child.render(Unit, ctx)
                    commands.addAll(rendered.commands.map { shift(it, x, y) })
                    if (dir == Layout.FlexDirection.Row) {
                        x += rendered.size(ctx).first + layout.spacing
                    } else {
                        y += rendered.size(ctx).second + layout.spacing
                    }
                }
            }
            is Layout.Absolute -> {
                layout.children.forEach { pos ->
                    @Suppress("UNCHECKED_CAST")
                    val widget = pos.widget as Widget<Unit>
                    val rendered = widget.render(Unit, ctx)
                    commands.addAll(rendered.commands.map { shift(it, pos.x, pos.y) })
                }
            }
        }
        
        // Background
        commands.add(0, RenderCommand.FillRect(0.0, 0.0, ctx.width, ctx.height, background))
        
        return Rendered(commands)
    }
    
    private fun shift(cmd: RenderCommand, dx: Double, dy: Double): RenderCommand = when (cmd) {
        is RenderCommand.FillRect -> cmd.copy(x = cmd.x + dx, y = cmd.y + dy)
        is RenderCommand.DrawText -> cmd.copy(x = cmd.x + dx, y = cmd.y + dy)
        is RenderCommand.DrawLine -> cmd.copy(x1 = cmd.x1 + dx, y1 = cmd.y1 + dy, x2 = cmd.x2 + dx, y2 = cmd.y2 + dy)
        is RenderCommand.DrawCircle -> cmd.copy(cx = cmd.cx + dx, cy = cmd.cy + dy)
    }
}

private fun Rendered.size(ctx: RenderContext): Pair<Double, Double> = 
    Pair(ctx.width, ctx.height)

/**
 * Window widget.
 */
class WindowWidget(
    val title: String,
    override val children: List<Widget<*>>,
    override val layout: Layout = Layout.Column()
) : LayoutWidget {
    override val signal: Signal<Unit> = signalOf(Unit)
    
    override fun render(value: Unit, ctx: RenderContext): Rendered {
        val panel = PanelWidget(children, layout)
        return panel.render(Unit, ctx)
    }
}

// ====================================================================
// SIGNAL FACTORY
// ====================================================================

/**
 * Constant signal.
 */
class ConstantSignal<T>(override val value: T) : Signal<T> {
    override val changes: kotlinx.coroutines.flow.Flow<T> = kotlinx.coroutines.flow.flowOf(value)
}

fun <T> signalOf(value: T): Signal<T> = ConstantSignal(value)