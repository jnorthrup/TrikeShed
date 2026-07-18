package borg.trikeshed.manimwm

import borg.trikeshed.manimwm.spi.ManimWmSpi

/**
 * JVM target implementation of the ManimWmSpi.
 * Acts as a native peering layer for testing/demonstration.
 */
class JvmManimWmSpi : ManimWmSpi {
    override fun initSurface(width: Int, height: Int) {
        println("JvmManimWmSpi: Initializing render surface (${width}x${height})")
    }

    override fun destroySurface() {
        println("JvmManimWmSpi: Destroying render surface")
    }

    override fun resizeSurface(width: Int, height: Int) {
        println("JvmManimWmSpi: Resizing render surface to (${width}x${height})")
    }

    override fun requestFrame() {
        println("JvmManimWmSpi: Frame requested")
    }

    override fun pushEvent(event: WindowEvent) {
        // In a real implementation, this would push GLFW, AWT, or JavaFX events
        // via manimWindowElement.pushEvent(...)
        println("JvmManimWmSpi: Pushing event $event")
    }
}
