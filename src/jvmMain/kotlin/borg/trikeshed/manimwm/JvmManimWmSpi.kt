package borg.trikeshed.manimwm

import borg.trikeshed.manimwm.spi.ManimWmSpi

/**
 * JVM target implementation of the ManimWmSpi.
 * Acts as a native peering layer for testing/demonstration.
 */
class JvmManimWmSpi : ManimWmSpi {
    override fun createWindow(title: String, width: Int, height: Int) {
        println("JvmManimWmSpi: Creating window '$title' (${width}x${height})")
    }

    override fun destroyWindow() {
        println("JvmManimWmSpi: Destroying window")
    }

    override fun setWindowTitle(title: String) {
        println("JvmManimWmSpi: Setting window title to '$title'")
    }

    override fun setWindowDecorations(decorated: Boolean) {
        println("JvmManimWmSpi: Setting window decorations to $decorated")
    }

    override fun pollEvents(onEvent: (WindowEvent) -> Unit) {
        // In a real implementation, this would poll GLFW, AWT, or JavaFX events
        // and push them via manimWindowElement.pushEvent(...)
        println("JvmManimWmSpi: Polling events")
    }
}
