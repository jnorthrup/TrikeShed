package borg.trikeshed.manimwm.manim

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

/**
 * A 2D point expressed as a Join of two Doubles (X and Y).
 */
typealias Point = Join<Double, Double>

val Point.x: Double get() = a
val Point.y: Double get() = b

/**
 * Helper to quickly build a Point.
 */
fun pt(x: Double, y: Double): Point = x j y

object UP {
    val vector: Point = pt(0.0, 1.0)
}

object DOWN {
    val vector: Point = pt(0.0, -1.0)
}

object LEFT {
    val vector: Point = pt(-1.0, 0.0)
}

object RIGHT {
    val vector: Point = pt(1.0, 0.0)
}

object ORIGIN {
    val vector: Point = pt(0.0, 0.0)
}

fun Point.add(other: Point): Point {
    return pt(this.x + other.x, this.y + other.y)
}

fun Point.sub(other: Point): Point {
    return pt(this.x - other.x, this.y - other.y)
}

fun Point.mul(scalar: Double): Point {
    return pt(this.x * scalar, this.y * scalar)
}
