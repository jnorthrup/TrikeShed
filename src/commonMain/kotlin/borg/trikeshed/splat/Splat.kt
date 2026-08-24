package borg.trikeshed.splat

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α

typealias OutcomeVec<T> = Join<T, Double>
typealias Splat<T> = Series<OutcomeVec<T>>

interface SplatModel<Context, T> {
    fun predict(context: Context): Splat<T>
}

fun <T> Splat<T>.toChronology(): String =
    (this α { pair ->
        val outcome = pair.a
        val prob = pair.b
        "\"$outcome\": $prob"
    }).view.joinToString(", ", "{", "}")
