package borg.trikeshed.usersignals.facets

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.usersignals.Signal
import kotlinx.coroutines.flow.Flow
import kotlin.jvm.JvmInline

@DslMarker
annotation class FacetDsl

@JvmInline
value class FacetKey(val value: String) {
    override fun toString(): String = value
}

typealias SignalFacet = Join<FacetKey, Signal<*>>
typealias SignalFacetSeries = Series<SignalFacet>

/**
 * User-signal facet surface for Stage 1 LCNC foundations.
 *
 * Facets are intentionally passive named signal projections. Storage is a
 * `Series<Join<FacetKey, Signal<*>>>`; list-backed collections remain outside
 * this core facet shape.
 */
class FacetedSignal<I : Signal<*>>(
    val base: I,
    val facets: SignalFacetSeries = emptySeriesOf(),
) : Signal<Any?> {
    override val value: Any? get() = base.value

    override val changes: Flow<Any?> = base.changes

    fun facet(key: FacetKey, signal: Signal<*>): FacetedSignal<I> =
        FacetedSignal(base, facets.append(key j signal))

    fun facet(name: String, signal: Signal<*>): FacetedSignal<I> =
        facet(FacetKey(name), signal)

    fun getFacet(key: FacetKey): Signal<*>? {
        for (index in 0 until facets.size) {
            val facet = facets[index]
            if (facet.a == key) return facet.b
        }
        return null
    }

    fun getFacet(name: String): Signal<*>? = getFacet(FacetKey(name))

    companion object {
        fun <I : Signal<*>> of(base: I): FacetedSignal<I> = FacetedSignal(base)
    }
}

fun <I : Signal<*>> facetedSignal(
    base: I,
    block: FacetBuilder<I>.() -> Unit = {},
): FacetedSignal<I> = FacetBuilder(base).apply(block).build()

@FacetDsl
class FacetBuilder<I : Signal<*>>(private val base: I) {
    private var facets: SignalFacetSeries = emptySeriesOf()

    fun facet(key: FacetKey, signal: Signal<*>): FacetBuilder<I> {
        facets = facets.append(key j signal)
        return this
    }

    fun facet(name: String, signal: Signal<*>): FacetBuilder<I> =
        facet(FacetKey(name), signal)

    fun build(): FacetedSignal<I> = FacetedSignal(base, facets)
}

object FacetKeys {
    val LABEL = FacetKey("label")
    val DETAIL = FacetKey("detail")
    val TYPE = FacetKey("type")
    val TEMPLATE = FacetKey("template")
}

private fun SignalFacetSeries.append(facet: SignalFacet): SignalFacetSeries {
    val source = this
    return (source.size + 1) j { index ->
        if (index < source.size) source[index] else facet
    }
}
