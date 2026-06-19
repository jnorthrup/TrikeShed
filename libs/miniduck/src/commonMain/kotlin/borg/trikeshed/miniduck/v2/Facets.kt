package borg.trikeshed.miniduck.v2

import borg.trikeshed.lib.*
import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.mutable.CowSeriesHandle
import borg.trikeshed.mutable.CowSeriesBody

@DslMarker
annotation class MiniDuckDsl

sealed class Facet {
    data class Project(val columns: List<String>) : Facet()
    data class Filter(val predicate: (MiniDuckSeries) -> Boolean) : Facet()
    data class Aggregate(val keyColumn: String, val valueColumn: String, val fn: (List<Any?>) -> Any?) : Facet()
    data class Chain(val first: Facet, val second: Facet) : Facet()

    companion object {
        fun project(vararg columns: String) = Project(columns.toList())
        fun filter(predicate: (MiniDuckSeries) -> Boolean) = Filter(predicate)
        fun aggregate(keyColumn: String, valueColumn: String, fn: (List<Any?>) -> Any?) = Aggregate(keyColumn, valueColumn, fn)
    }

    infix fun then(other: Facet): Facet = Chain(this, other)
}

class FacetedCursor(
    private val base: Cursor,
    private val facets: MutableMap<String, Facet> = mutableMapOf(),
    private val cache: MutableMap<String, Cursor> = mutableMapOf(),
) : Series<MiniDuckSeries> {
    override val a: Int get() = base.a
    override val b: (Int) -> MiniDuckSeries get() = base.b

    fun addFacet(name: String, facet: Facet) {
        facets[name] = facet
        cache.remove(name)
    }

    fun facet(name: String): Cursor = cache.getOrPut(name) { applyFacet(base, facets[name]!!) }

    val facetNames: Set<String> get() = facets.keys

    fun materialize(): Map<String, Cursor> = facets.keys.associateWith { facet(it) }

    private fun applyFacet(source: Cursor, facet: Facet): Cursor = when (facet) {
        is Facet.Project -> source.project(facet.columns)
        is Facet.Filter -> source.filter(facet.predicate)
        is Facet.Aggregate -> source.aggregate(facet.keyColumn, facet.valueColumn, facet.fn)
        is Facet.Chain -> applyFacet(applyFacet(source, facet.first), facet.second)
    }

    infix fun Project.columns(vararg cols: String): FacetedCursor {
        addFacet("proj_${cols.joinToString("_")}", Facet.Project(cols.toList()))
        return this
    }

    infix fun Filter.where(predicate: (MiniDuckSeries) -> Boolean): FacetedCursor {
        addFacet("filter_${facets.size}", Facet.Filter(predicate))
        return this
    }

    infix fun Aggregate.by(key: String, value: String, fn: (List<Any?>) -> Any?): FacetedCursor {
        addFacet("agg_${key}_$value", Facet.Aggregate(key, value, fn))
        return this
    }
}

@MiniDuckDsl
class FacetDSL<T : MiniDuckSeries> {
    private val facets = mutableListOf<Pair<String, Facet>>()

    inline fun <reified R> project(vararg columns: String): FacetDSL<T> {
        facets += "proj_${columns.joinToString("_")}" to Facet.Project(columns.toList())
        return this
    }

    inline fun <reified R> filter(predicate: (T) -> Boolean): FacetDSL<T> {
        facets += "filter_${facets.size}" to Facet.Filter { it as T; predicate(it) }
        return this
    }

    inline fun <reified R> aggregate(key: String, value: String, fn: (List<R>) -> Any?): FacetDSL<T> {
        facets += "agg_${key}_$value" to Facet.Aggregate(key, value, { it.map { (it as R) } })
        return this
    }

    fun build(): List<Pair<String, Facet>> = facets.toList()
}

fun Cursor.faceted(): FacetedCursor = FacetedCursor(this)

class FacetedTable(
    name: String,
    baseSchema: ColumnSchema,
) : Table(name, Cursor(), baseSchema) {
    private val facets = mutableMapOf<String, Facet>()

    fun defineFacet(name: String, facet: Facet): FacetedTable {
        facets[name] = facet
        return this
    }

    fun facet(name: String): Cursor = FacetedCursor(cursor, facets).facet(name)

    inline fun <reified T> query(dsl: FacetDSL<T>.() -> Unit): Cursor {
        val builder = FacetDSL<T>()
        builder.dsl()
        var result = cursor
        for ((_, facet) in builder.build()) {
            result = FacetedCursor(result, emptyMap()).applyFacet(result, facet)
        }
        return result
    }
}
