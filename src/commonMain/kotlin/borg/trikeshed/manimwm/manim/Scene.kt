package borg.trikeshed.manimwm.manim

/**
 * A Manim scene manages a collection of Mobjects and coordinates animations.
 * Extended for RTS to support World vs HUD layers.
 */
open class Scene {
    private val _worldMobjects = mutableListOf<Mobject>()
    val worldMobjects: List<Mobject> get() = _worldMobjects

    private val _hudMobjects = mutableListOf<Mobject>()
    val hudMobjects: List<Mobject> get() = _hudMobjects

    // Legacy support, defaults to world
    val mobjects: List<Mobject> get() = _worldMobjects

    val camera: Camera = Camera()

    open fun construct() {}

    fun add(vararg mobs: Mobject) {
        _worldMobjects.addAll(mobs)
    }

    fun remove(vararg mobs: Mobject) {
        _worldMobjects.removeAll(mobs.toSet())
    }

    fun addHud(vararg mobs: Mobject) {
        _hudMobjects.addAll(mobs)
    }

    fun removeHud(vararg mobs: Mobject) {
        _hudMobjects.removeAll(mobs.toSet())
    }

    fun play(vararg animations: Animation, runTime: Double = 1.0) {
        // Animation logic placeholder
        for (anim in animations) {
            add(anim.mobject)
            anim.begin()
            anim.interpolate(1.0)
            anim.finish()
        }
    }

    fun wait(duration: Double = 1.0) {
        // Wait logic placeholder
    }
}
