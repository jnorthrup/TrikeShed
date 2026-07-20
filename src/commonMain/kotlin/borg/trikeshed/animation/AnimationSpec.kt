package borg.trikeshed.animation

class AnimationSpec {
    var duration: Long = 0L
    var ease: (Double) -> Double = Easing.Linear
    var interpolator: (Double) -> Double = { it }

    fun interpolate(fraction: Double): Double {
        return interpolator(ease(fraction))
    }
}

object Easing {
    val Linear: (Double) -> Double = { it }
    val EaseInOut: (Double) -> Double = { t -> if (t < 0.5) 2 * t * t else -1 + (4 - 2 * t) * t }
}

fun animationSpec(block: AnimationSpec.() -> Unit): AnimationSpec {
    return AnimationSpec().apply(block)
}

class AnimationTrigger(val action: () -> Unit) {
    fun fire() {
        action()
    }
}
