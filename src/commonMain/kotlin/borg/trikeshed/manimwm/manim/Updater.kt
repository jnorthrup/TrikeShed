package borg.trikeshed.manimwm.manim

/**
 * Updater abstraction for Manim scenes.
 */
interface Updater {
    fun update(dt: Double)
}

open class MobjectUpdater(val mobject: Mobject, val updateFunc: (Mobject, Double) -> Unit) : Updater {
    override fun update(dt: Double) {
        updateFunc(mobject, dt)
    }
}
