package borg.trikeshed.manimwm.manim






/**
 * Base abstraction for all objects in the Manim scene graph.
 */
interface Mobject {
    val subobjects: List<Mobject>
    fun add(vararg mobjects: Mobject)
    fun remove(vararg mobjects: Mobject)
    fun getCenter(): Point
    fun moveTo(point: Point)
    var color: Color
    fun addUpdater(updater: Updater)
    fun removeUpdater(updater: Updater)
    fun update(dt: Double)
}

/**
 * Base implementation of a Visual Mobject.
 */
open class VMobject : Mobject {
    private val _subobjects = mutableListOf<Mobject>()
    override val subobjects: List<Mobject> get() = _subobjects

    private var _center: Point = pt(0.0, 0.0)
    override var color: Color = Color.WHITE
    private val _updaters = mutableListOf<Updater>()

    override fun add(vararg mobjects: Mobject) {
        _subobjects.addAll(mobjects)
    }

    override fun remove(vararg mobjects: Mobject) {
        _subobjects.removeAll(mobjects.toSet())
    }

    override fun getCenter(): Point = _center

    override fun addUpdater(updater: Updater) {
        _updaters.add(updater)
    }

    override fun removeUpdater(updater: Updater) {
        _updaters.remove(updater)
    }

    override fun update(dt: Double) {
        for (updater in _updaters) {
            updater.update(dt)
        }
        for (subob in _subobjects) {
            subob.update(dt)
        }
    }

    override fun moveTo(point: Point) {
        _center = point
    }
}

/**
 * Group abstraction for Manim scenes.
 */
open class Group(vararg mobjects: Mobject) : Mobject {
    private val _subobjects = mobjects.toMutableList()
    override val subobjects: List<Mobject> get() = _subobjects

    override var color: Color = Color.WHITE

    override fun add(vararg mobjects: Mobject) {
        _subobjects.addAll(mobjects)
    }

    override fun remove(vararg mobjects: Mobject) {
        _subobjects.removeAll(mobjects.toSet())
    }

    override fun getCenter(): Point {
        if (_subobjects.isEmpty()) return pt(0.0, 0.0)
        var sumX = 0.0
        var sumY = 0.0
        for (mob in _subobjects) {
            val c = mob.getCenter()
            sumX += c.x
            sumY += c.y
        }
        return pt(sumX / _subobjects.size, sumY / _subobjects.size)
    }

    override fun moveTo(point: Point) {
        val currentCenter = getCenter()
        val dx = point.x - currentCenter.x
        val dy = point.y - currentCenter.y
        for (mob in _subobjects) {
            val mobCenter = mob.getCenter()
            mob.moveTo(pt(mobCenter.x + dx, mobCenter.y + dy))
        }
    }

    private val _updaters = mutableListOf<Updater>()
    override fun addUpdater(updater: Updater) {
        _updaters.add(updater)
    }

    override fun removeUpdater(updater: Updater) {
        _updaters.remove(updater)
    }

    override fun update(dt: Double) {
        for (updater in _updaters) {
            updater.update(dt)
        }
        for (subob in _subobjects) {
            subob.update(dt)
        }
    }
}
