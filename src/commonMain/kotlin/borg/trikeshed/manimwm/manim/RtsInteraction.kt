package borg.trikeshed.manimwm.manim

import borg.trikeshed.manimwm.WindowEvent

/**
 * Maps Causality events into RTS camera/HUD changes.
 */
class RtsInteractionController(val scene: Scene) {
    private var isDragging = false
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0

    fun handleEvent(event: WindowEvent) {
        when (event) {
            is WindowEvent.MousePressed -> {
                if (event.button == 1) { // Left click
                    isDragging = true
                    lastMouseX = event.x
                    lastMouseY = event.y
                }
            }
            is WindowEvent.MouseReleased -> {
                if (event.button == 1) {
                    isDragging = false
                }
            }
            is WindowEvent.Moved -> {
                if (isDragging) {
                    val dx = event.x - lastMouseX
                    val dy = event.y - lastMouseY
                    // Convert screen drag to camera pan
                    scene.camera.pan(-dx * 0.01 / scene.camera.zoomLevel, dy * 0.01 / scene.camera.zoomLevel)
                    lastMouseX = event.x
                    lastMouseY = event.y
                }
            }
            is WindowEvent.Scrolled -> {
                // Map scroll to fractal zoom
                if (event.dy > 0) {
                    scene.camera.zoom(1.1)
                } else if (event.dy < 0) {
                    scene.camera.zoom(0.9)
                }
            }
            else -> {}
        }
    }
}
