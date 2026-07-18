package borg.trikeshed.manimwm

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.SeriesBuffer

/**
 * Represents causality events emitted by the native window manager (ManimWmSpi)
 * mapped back into TrikeShed algebra (Join and Series).
 */
sealed class WindowEvent {
    /**
     * Fired when the native window's dimensions are changed.
     */
    data class Resized(val width: Double, val height: Double) : WindowEvent()

    /**
     * Fired when the window is moved on the screen.
     */
    data class Moved(val x: Double, val y: Double) : WindowEvent()

    /**
     * Fired when a mouse button is pressed.
     */
    data class MousePressed(val x: Double, val y: Double, val button: Int) : WindowEvent()

    /**
     * Fired when a mouse button is released.
     */
    data class MouseReleased(val x: Double, val y: Double, val button: Int) : WindowEvent()

    /**
     * Fired when a key is pressed.
     */
    data class KeyPressed(val keyCode: Int) : WindowEvent()

    /**
     * Fired when a key is released.
     */
    data class KeyReleased(val keyCode: Int) : WindowEvent()

    /**
     * Fired when the mouse wheel is scrolled.
     */
    data class Scrolled(val dx: Double, val dy: Double) : WindowEvent()

    /**
     * Fired when the native window manager receives a close request.
     */
    object CloseRequested : WindowEvent()
}

/**
 * Cursor/Series capturing a timeline of window causality events.
 */
class CausalityEventCursor {
    private val buffer = SeriesBuffer<Join<Long, WindowEvent>>()

    fun append(timestampMs: Long, event: WindowEvent) {
        buffer.add(timestampMs j event)
    }

    val series: Series<Join<Long, WindowEvent>> get() = buffer
}
