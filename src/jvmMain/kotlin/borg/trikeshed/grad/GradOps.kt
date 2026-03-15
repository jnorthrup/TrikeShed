package borg.trikeshed.grad

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Dual number for forward-mode automatic differentiation.
 * v  = primal value
 * dv = tangent (derivative)
 */
data class Dual(val v: Double, val dv: Double) {

    // --- arithmetic operators ---

    operator fun plus(other: Dual): Dual = Dual(v + other.v, dv + other.dv)
    operator fun plus(c: Double): Dual = Dual(v + c, dv)

    operator fun minus(other: Dual): Dual = Dual(v - other.v, dv - other.dv)
    operator fun minus(c: Double): Dual = Dual(v - c, dv)

    operator fun times(other: Dual): Dual = Dual(v * other.v, dv * other.v + v * other.dv)
    operator fun times(c: Double): Dual = Dual(v * c, dv * c)

    operator fun div(other: Dual): Dual =
        Dual(v / other.v, (dv * other.v - v * other.dv) / (other.v * other.v))
    operator fun div(c: Double): Dual = Dual(v / c, dv / c)

    operator fun unaryMinus(): Dual = Dual(-v, -dv)

    companion object {
        /** Constant: derivative is 0 */
        fun const(v: Double): Dual = Dual(v, 0.0)

        /** Independent variable: derivative is 1 */
        fun variable(v: Double): Dual = Dual(v, 1.0)
    }
}

// --- Double operator extensions so `2.0 + dual` etc. work ---
operator fun Double.plus(d: Dual): Dual = Dual(this + d.v, d.dv)
operator fun Double.minus(d: Dual): Dual = Dual(this - d.v, -d.dv)
operator fun Double.times(d: Dual): Dual = Dual(this * d.v, this * d.dv)
operator fun Double.div(d: Dual): Dual = Dual(this / d.v, -this * d.dv / (d.v * d.v))

// --- math extensions on Dual ---

fun exp(d: Dual): Dual = exp(d.v).let { ev -> Dual(ev, d.dv * ev) }
fun ln(d: Dual): Dual = Dual(ln(d.v), d.dv / d.v)
fun sin(d: Dual): Dual = Dual(sin(d.v), d.dv * cos(d.v))
fun cos(d: Dual): Dual = Dual(cos(d.v), -d.dv * sin(d.v))
fun abs(d: Dual): Dual = Dual(abs(d.v), d.dv * if (d.v >= 0.0) 1.0 else -1.0)

/** d ^ n  for a real exponent n */
fun pow(d: Dual, n: Double): Dual = Dual(d.v.pow(n), d.dv * n * d.v.pow(n - 1))

// --- gradient helpers ---

/**
 * Compute the scalar gradient df/dx at x.
 *
 * @param x  the point at which to differentiate
 * @param f  a function expressed in Dual arithmetic
 * @return   df/dx
 */
fun grad(x: Double, f: (Dual) -> Dual): Double = f(Dual.variable(x)).dv

/**
 * Compute the partial derivative of f with respect to xs[i] at the given point.
 *
 * @param xs  the input vector
 * @param i   index of the variable to differentiate
 * @param f   a function expressed in Dual arithmetic over the whole input array
 * @return    ∂f/∂xs[i]
 */
fun gradVec(xs: DoubleArray, i: Int, f: (Array<Dual>) -> Dual): Double {
    val duals = Array(xs.size) { k -> if (k == i) Dual.variable(xs[k]) else Dual.const(xs[k]) }
    return f(duals).dv
}
