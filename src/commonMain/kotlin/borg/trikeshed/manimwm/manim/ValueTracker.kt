package borg.trikeshed.manimwm.manim

/**
 * ValueTracker abstraction for Manim scenes.
 */
class ValueTracker(var value: Double = 0.0) : VMobject() {
    fun incrementValue(d: Double) {
        value += d
    }

    fun getTrackedValue(): Double = value
    fun setTrackedValue(newValue: Double) {
        value = newValue
    }
}

class DecimalNumber(var value: Double = 0.0) : VMobject() {
    fun set_value(newValue: Double) {
        value = newValue
    }
}
