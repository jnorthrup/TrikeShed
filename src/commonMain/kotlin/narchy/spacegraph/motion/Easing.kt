package narchy.spacegraph.motion

// Port of upstream src/motion/easing.ts at 125b369 (P4.1). Pure easing curves: the representation easings plus
// analytic damped-spring responses. No clock or renderer dependency.

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

import narchy.spacegraph.representations.REPRESENTATION_EASINGS

data class SpringConfig(val stiffness: Double, val damping: Double, val mass: Double)

/** Numeric copies of the spring presets hosts already pass to their UI hooks. */
object SPRING_PRESETS {
    val gentle = SpringConfig(stiffness = 120.0, damping = 14.0, mass = 1.0)
    val smooth = SpringConfig(stiffness = 180.0, damping = 17.0, mass = 1.0)
    val snappy = SpringConfig(stiffness = 280.0, damping = 22.0, mass = 1.0)
    val bouncy = SpringConfig(stiffness = 400.0, damping = 20.0, mass = 1.0)
}

/** A normalized damped-spring step response. Overshoot is deliberate; hosts clamp the output channel. */
fun springEasing(config: SpringConfig): (Double) -> Double {
    val omega = sqrt(config.stiffness / config.mass)
    val zeta = config.damping / (2 * sqrt(config.stiffness * config.mass))
    val wd = omega * sqrt(maxOf(1 - zeta * zeta, 1e-6))
    val response = { t: Double ->
        1 - exp(-zeta * omega * t) * (cos(wd * t) + ((zeta * omega) / wd) * sin(wd * t))
    }
    val end = response(1.0).let { if (it == 0.0) 1.0 else it }
    return { t -> response(t) / end }
}

val MOTION_EASINGS: Map<String, (Double) -> Double> = REPRESENTATION_EASINGS + mapOf(
    "spring.gentle" to springEasing(SPRING_PRESETS.gentle),
    "spring.smooth" to springEasing(SPRING_PRESETS.smooth),
    "spring.snappy" to springEasing(SPRING_PRESETS.snappy),
    "spring.bouncy" to springEasing(SPRING_PRESETS.bouncy),
)
