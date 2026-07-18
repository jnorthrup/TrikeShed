package borg.trikeshed.manimwm.spi

import borg.trikeshed.manimwm.WindowEvent

/**
 * Service Provider Interface for the Manim Window Manager.
 * Platform-specific interactions must be handled by injecting implementations of this interface,
 * avoiding pure 'expect'/'actual' modifiers to remain strictly portable and decoupled.
 */
interface ManimWmSpi {
    /**
     * Initializes a render/composit surface.
     */
    fun initSurface(width: Int, height: Int)

    /**
     * Closes and cleans up native resources associated with the render surface.
     */
    fun destroySurface()

    /**
     * Resizes the render surface.
     */
    fun resizeSurface(width: Int, height: Int)

    /**
     * Requests a new frame to be drawn to the render surface.
     */
    fun requestFrame()

    /**
     * Pushes a causality event to the reactive causality primitive layer.
     */
    fun pushEvent(event: WindowEvent)
}
