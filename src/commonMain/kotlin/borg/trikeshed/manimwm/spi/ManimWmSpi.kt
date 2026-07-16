package borg.trikeshed.manimwm.spi

import borg.trikeshed.manimwm.WindowEvent

/**
 * Service Provider Interface for the Manim Window Manager.
 * Platform-specific interactions must be handled by injecting implementations of this interface,
 * avoiding pure 'expect'/'actual' modifiers to remain strictly portable and decoupled.
 */
interface ManimWmSpi {
    /**
     * Initializes and shows a native platform window.
     */
    fun createWindow(title: String, width: Int, height: Int)

    /**
     * Closes and cleans up native resources associated with the window.
     */
    fun destroyWindow()

    /**
     * Updates the text displayed in the title bar of the window.
     */
    fun setWindowTitle(title: String)

    /**
     * Toggles platform-specific window decorations (title bar, borders, etc).
     */
    fun setWindowDecorations(decorated: Boolean)

    /**
     * Polls native window events (like resize, close requests, mouse movements)
     * and forwards them to the reactive Causality primitive layer.
     */
    fun pollEvents(onEvent: (WindowEvent) -> Unit)
}
