package borg.trikeshed.manimwm.manim






/**
 * 2.5D Fractal Zoom Camera for RTS Planning.
 */
class Camera {
    // 2D Pan Position
    var frameCenter: Point = pt(0.0, 0.0)

    // Fractal Depth/Zoom
    var zoomLevel: Double = 1.0

    // 2.5D Perspective (Euler angles)
    var phi: Double = 0.0 // Tilt/pitch
    var theta: Double = 0.0 // Rotation/yaw

    var frameWidth: Double = 14.222222222222221
    var frameHeight: Double = 8.0

    fun pan(dx: Double, dy: Double) {
        frameCenter = pt(frameCenter.x + dx, frameCenter.y + dy)
    }

    fun zoom(factor: Double) {
        zoomLevel *= factor
    }

    fun setOrientation(newPhi: Double, newTheta: Double) {
        phi = newPhi
        theta = newTheta
    }
}
