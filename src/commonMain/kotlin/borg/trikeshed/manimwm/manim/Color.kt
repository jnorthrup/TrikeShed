package borg.trikeshed.manimwm.manim

/**
 * Basic Color abstraction for Manim scenes.
 */
data class Color(val r: Double, val g: Double, val b: Double, val a: Double = 1.0) {
    companion object {
        val WHITE = Color(1.0, 1.0, 1.0)
        val BLACK = Color(0.0, 0.0, 0.0)
        val RED = Color(1.0, 0.0, 0.0)
        val GREEN = Color(0.0, 1.0, 0.0)
        val BLUE = Color(0.0, 0.0, 1.0)
        val YELLOW = Color(1.0, 1.0, 0.0)
        val ORANGE = Color(1.0, 0.5, 0.0)
        val PURPLE = Color(0.5, 0.0, 0.5)
        val PINK = Color(1.0, 0.0, 1.0)
        val TEAL = Color(0.0, 0.5, 0.5)
    }
}
