package borg.trikeshed.manimwm.manim

/**
 * AnimationBuilder abstraction for Manim scenes.
 */
class AnimationBuilder(val mobject: Mobject) {
    fun animate(): AnimationBuilder {
        return this
    }

    fun moveTo(point: Point): Animation {
        return Transform(mobject, mobject.apply { moveTo(point) })
    }

    fun shift(direction: Point): Animation {
        val currentCenter = mobject.getCenter()
        return Transform(mobject, mobject.apply { moveTo(pt(currentCenter.x + direction.x, currentCenter.y + direction.y)) })
    }

    fun set_color(color: Color): Animation {
        return Transform(mobject, mobject.apply { this.color = color })
    }

    fun scale(factor: Double): Animation {
        return Transform(mobject, mobject)
    }

    fun move_to(point: Point): Animation {
        return moveTo(point)
    }

    fun set_value(newValue: Double): Animation {
        if (mobject is ValueTracker) {
            return Transform(mobject, mobject.apply { setTrackedValue(newValue) })
        }
        return Transform(mobject, mobject)
    }

    fun set_width(width: Double): Animation {
        return Transform(mobject, mobject)
    }

    fun rotate(angle: Double, axis: Point = ORIGIN.vector, about_point: Point? = null): Animation {
        return Transform(mobject, mobject)
    }

    fun next_to(mobject_or_point: Any, direction: Point = RIGHT.vector, buff: Double = 0.25): Animation {
        return Transform(mobject, mobject)
    }

    fun align_to(mobject_or_point: Any, direction: Point = ORIGIN.vector): Animation {
        return Transform(mobject, mobject)
    }

    fun set_stroke(color: Color = Color.WHITE, width: Double = 1.0, opacity: Double = 1.0): Animation {
        return Transform(mobject, mobject)
    }

    fun set_fill(color: Color = Color.WHITE, opacity: Double = 1.0): Animation {
        return Transform(mobject, mobject)
    }

    fun set_opacity(opacity: Double): Animation {
         return Transform(mobject, mobject)
    }

    fun rotate_about_origin(angle: Double): Animation {
        return Transform(mobject, mobject)
    }

    fun shift(dx: Double, dy: Double, dz: Double): Animation {
        return shift(pt(dx, dy))
    }

    fun become(mobject: Mobject): Animation {
        return Transform(this.mobject, mobject)
    }
}

val Mobject.animate: AnimationBuilder get() = AnimationBuilder(this)

class AnimationGroup(vararg val animations: Animation) : Animation {
    override val mobject: Mobject get() = animations.first().mobject
    override val runTime: Double get() = animations.maxOf { it.runTime }
    override fun begin() {
        animations.forEach { it.begin() }
    }
    override fun finish() {
         animations.forEach { it.finish() }
    }
    override fun interpolate(alpha: Double) {
         animations.forEach { it.interpolate(alpha) }
    }
}

class LaggedStart(vararg val animations: Animation, val lag_ratio: Double = 0.05) : Animation {
    override val mobject: Mobject get() = animations.first().mobject
    override val runTime: Double get() = animations.maxOf { it.runTime } + lag_ratio * animations.size
    override fun begin() {}
    override fun finish() {}
    override fun interpolate(alpha: Double) {}
}

class ReplacementTransform(val mobject1: Mobject, val mobject2: Mobject, override val runTime: Double = 1.0) : Animation {
    override val mobject: Mobject get() = mobject1
    override fun begin() {}
    override fun finish() {}
    override fun interpolate(alpha: Double) {}
}

class Write(override val mobject: Mobject, override val runTime: Double = 1.0) : Animation {
    override fun begin() {}
    override fun finish() {}
    override fun interpolate(alpha: Double) {}
}
