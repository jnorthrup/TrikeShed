package borg.trikeshed.splat

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view

/** Discrete outcome distribution (original empirical model) */
typealias OutcomeVec<T> = Join<T, Double>
typealias Splat<T> = Series<OutcomeVec<T>>

/** SplatModel interface — implemented by both EmpiricalMotionModel and GaussianMotionModel */
interface SplatModel<Context, T> {
    fun predict(context: Context): Splat<T>
}

/** NGS types re-exported for consumers */
public typealias GUnit = borg.trikeshed.splat.GUnit
public typealias GUnitGradients = borg.trikeshed.splat.GUnitGradients
public typealias SpatialHashGrid = borg.trikeshed.splat.SpatialHashGrid
public typealias GaussianMotionModel = borg.trikeshed.splat.GaussianMotionModel
public typealias LocalAffine = borg.trikeshed.splat.LocalAffine
public typealias Quaternion = borg.trikeshed.splat.Quaternion

fun <T> Splat<T>.toChronology(): String =
    size.j { i: Int ->
        val pair = this.b(i)
        val outcome = pair.a
        val prob = pair.b
        "\"$outcome\": $prob"
    }.view.joinToString(", ", "{", "}")
