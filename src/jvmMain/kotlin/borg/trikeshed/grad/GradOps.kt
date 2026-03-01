@file:Suppress("NonAsciiCharacters", "ObjectPropertyName")

/**
 * GradOps.kt — kotlingrad extensions to the TrikeShed algebraic shorthand.
 *
 * Design principles:
 *   - Single Unicode character where mathematical precedent exists
 *   - Infix for binary, extension property for unary
 *   - Composes with existing j, α, ▶, ↺
 *   - Every operator must pull its weight: if it doesn't compose, it doesn't ship
 *   - Every glyph gets a keyboard alias (ASCII-typeable)
 *
 * Existing vocabulary (TrikeShed):
 *   a j b          join construction       Join<A,B>
 *   v α f          functor map             Series<B>
 *   v.▶            iterable wrapper        IterableSeries<T>
 *   x.↺            left identity thunk     () -> T
 *
 * New vocabulary:
 *   f ∂ x  / f diff x         partial derivative        SFun<DReal>
 *   s.∇(params) / s.grad()    gradient vector           Series<SFun<DReal>>
 *   f ≈ bindings / f eval b   evaluate at point         Double
 *   d.↑ / d.lift              lift constant to expr     SFun<DReal>
 *   a ⊗ b / a tangent b       tangent product           Series<Join<A,B>>
 *   a ≅ b / a iso b           expression isomorphism    Boolean
 *   _d[1.0, 2.0]              diff literal              Series<SFun<DReal>>
 *   a ʘ b / a hadamard b      element-wise product      Series<T>
 */

package borg.trikeshed.grad

import ai.hypergraph.kotlingrad.api.*
import borg.trikeshed.lib.*

// -- ∂  Partial Derivative / diff --
//
//   f ∂ x              scalar: d(f)/d(x)
//   series ∂ x         pointwise: Series<SFun> where each element is d(s[i])/d(x)
//   (series ∂ x) ∂ y   second derivative: chain it

infix fun SFun<DReal>.`∂`(v: SVar<DReal>): SFun<DReal> = d(v)

infix fun Series<SFun<DReal>>.`∂`(v: SVar<DReal>): Series<SFun<DReal>> =
    size j { i: Int -> this[i].d(v) }

/** keyboard alias */
infix fun SFun<DReal>.diff(v: SVar<DReal>): SFun<DReal> = `∂`(v)
/** keyboard alias */
infix fun Series<SFun<DReal>>.diff(v: SVar<DReal>): Series<SFun<DReal>> = `∂`(v)


// -- ∇  Gradient Vector / grad --
//
//   f.∇(x, y, z)           Series<SFun> = [∂f/∂x, ∂f/∂y, ∂f/∂z]
//   (f.∇(x,y)) ≈ bindings  evaluate gradient at a point -> Series<Double>

fun SFun<DReal>.`∇`(vararg params: SVar<DReal>): Series<SFun<DReal>> =
    params.size j { i: Int -> d(params[i]) }

fun Series<SFun<DReal>>.`∇`(vararg params: SVar<DReal>): Series<Series<SFun<DReal>>> =
    size j { i: Int -> this[i].`∇`(*params) }

/** keyboard alias */
fun SFun<DReal>.grad(vararg params: SVar<DReal>): Series<SFun<DReal>> = `∇`(*params)
fun Series<SFun<DReal>>.grad(vararg params: SVar<DReal>): Series<Series<SFun<DReal>>> = `∇`(*params)


// -- ≈  Evaluate at Point / eval --
//
//   f ≈ mapOf(x to 3.0)        evaluate SFun -> Double
//   series ≈ bindings           pointwise evaluation -> Series<Double>
//   gradient ≈ bindings         evaluate gradient -> Series<Double>

infix fun SFun<DReal>.`≈`(bindings: Map<SVar<DReal>, Double>): Double {
    val pairs = bindings.entries.map { (k, v) -> k to v as Any }.toTypedArray()
    return invoke(*pairs).toDouble()
}

infix fun Series<SFun<DReal>>.`≈`(bindings: Map<SVar<DReal>, Double>): Series<Double> =
    size j { i: Int -> this[i] `≈` bindings }

/** keyboard alias */
infix fun SFun<DReal>.eval(bindings: Map<SVar<DReal>, Double>): Double = `≈`(bindings)
infix fun Series<SFun<DReal>>.eval(bindings: Map<SVar<DReal>, Double>): Series<Double> = `≈`(bindings)


// -- ↑  Lift Constant to Expression Space / lift --
//
//   3.14.↑                 wrap(3.14) : SFun<DReal>
//   series.↑               lift Series<Double> to Series<SFun<DReal>>

val Double.`↑`: SFun<DReal> get() = DReal.wrap(this)
val Float.`↑`: SFun<DReal> get() = DReal.wrap(this.toDouble())
val Int.`↑`: SFun<DReal> get() = DReal.wrap(this.toDouble())
val Long.`↑`: SFun<DReal> get() = DReal.wrap(this.toDouble())

val Series<Double>.`↑`: Series<SFun<DReal>>
    get() = size j { i: Int -> this[i].`↑` }

val DoubleArray.`↑`: Series<SFun<DReal>>
    get() = size j { i: Int -> this[i].`↑` }

/** keyboard alias */
val Double.lift: SFun<DReal> get() = `↑`
val Series<Double>.lift: Series<SFun<DReal>> get() = `↑`
val DoubleArray.lift: Series<SFun<DReal>> get() = `↑`


// -- ⊗  Tangent Product / tangent --
//
//   valueSeries ⊗ gradSeries    Series<Join<A, B>>
//
//  Tangent bundle constructor. At each index i:
//    (value ⊗ gradient)[i] = value[i] j gradient[i]

infix fun <A, B> Series<A>.`⊗`(other: Series<B>): Series<Join<A, B>> {
    require(size == other.size) { "⊗ requires matching sizes: $size vs ${other.size}" }
    return size j { i: Int -> this[i] j other[i] }
}

/** keyboard alias */
infix fun <A, B> Series<A>.tangent(other: Series<B>): Series<Join<A, B>> = `⊗`(other)


// -- ʘ  Hadamard (element-wise) product / hadamard --
//
//   seriesA ʘ seriesB           element-wise multiply

infix fun Series<Double>.`ʘ`(other: Series<Double>): Series<Double> {
    require(size == other.size) { "ʘ requires matching sizes: $size vs ${other.size}" }
    return size j { i: Int -> this[i] * other[i] }
}

@JvmName("ʘSFun")
infix fun Series<SFun<DReal>>.`ʘ`(other: Series<SFun<DReal>>): Series<SFun<DReal>> {
    require(size == other.size) { "ʘ requires matching sizes: $size vs ${other.size}" }
    return size j { i: Int -> this[i] * other[i] }
}

/** keyboard alias */
infix fun Series<Double>.hadamard(other: Series<Double>): Series<Double> = `ʘ`(other)
@JvmName("hadamardSFun")
infix fun Series<SFun<DReal>>.hadamard(other: Series<SFun<DReal>>): Series<SFun<DReal>> = `ʘ`(other)


// -- dot  Inner product --

infix fun Series<Double>.dot(other: Series<Double>): Double {
    require(size == other.size) { "dot requires matching sizes: $size vs ${other.size}" }
    var acc = 0.0
    for (i in 0 until size) acc += this[i] * other[i]
    return acc
}

@JvmName("dotSFun")
infix fun Series<SFun<DReal>>.dot(other: Series<SFun<DReal>>): SFun<DReal> {
    require(size == other.size) { "dot requires matching sizes: $size vs ${other.size}" }
    var acc: SFun<DReal> = DReal.wrap(0.0)
    for (i in 0 until size) acc = acc + this[i] * other[i]
    return acc
}


// -- ≅  Expression Isomorphism / iso --
//
//   exprA ≅ exprB     true if structurally isomorphic SFun trees

infix fun SFun<DReal>.`≅`(other: SFun<DReal>): Boolean =
    fingerprint() == other.fingerprint()

/** keyboard alias */
infix fun SFun<DReal>.iso(other: SFun<DReal>): Boolean = `≅`(other)

/** Fingerprint: structural hash of SFun expression tree. */
fun SFun<DReal>.fingerprint(): ExprFingerprint = when (this) {
    is SVar    -> ExprFingerprint.Var(name)
    is Sum     -> ExprFingerprint.Bin("+", left.fingerprint(), right.fingerprint())
    is Prod    -> ExprFingerprint.Bin("*", left.fingerprint(), right.fingerprint())
    is Negative -> ExprFingerprint.Un("-", input.fingerprint())
    is Power   -> ExprFingerprint.Bin("^", left.fingerprint(), right.fingerprint())
    is Log     -> ExprFingerprint.Bin("ln", left.fingerprint(), right.fingerprint())
    else       -> ExprFingerprint.Leaf(this::class.simpleName ?: "?", toString().hashCode())
}

sealed class ExprFingerprint {
    data class Var(val name: String) : ExprFingerprint()
    data class Const(val value: Double) : ExprFingerprint()
    data class Bin(val op: String, val l: ExprFingerprint, val r: ExprFingerprint) : ExprFingerprint()
    data class Un(val op: String, val x: ExprFingerprint) : ExprFingerprint()
    data class Leaf(val type: String, val hash: Int) : ExprFingerprint()
}


// -- _d  Differentiable Literal Constructor --
//
//   _d[1.0, 2.0, 3.0]     Series<SFun<DReal>> of wrapped constants
//   _d[series]             lift existing Series<Double>
//   _d("price", arr)       named SVar series for adversarial perturbation

object _d {
    operator fun get(vararg values: Double): Series<SFun<DReal>> =
        values.size j { i: Int -> values[i].`↑` }

    operator fun get(series: Series<Double>): Series<SFun<DReal>> =
        series.`↑`

    operator fun get(name: String, values: DoubleArray): Series<SVar<DReal>> =
        values.size j { i: Int -> SVar(DReal, "${name}_$i") }
}
