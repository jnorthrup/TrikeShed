package borg.trikeshed.manimwm.manim

/**
 * Animation abstraction for Manim scenes.
 */
interface Animation {
    val mobject: Mobject
    val runTime: Double
    fun begin()
    fun finish()
    fun interpolate(alpha: Double)
}

class Transform(val mobject1: Mobject, val mobject2: Mobject, override val runTime: Double = 1.0) : Animation {
    override val mobject: Mobject get() = mobject1
    override fun begin() {}
    override fun finish() {}
    override fun interpolate(alpha: Double) {}
}
