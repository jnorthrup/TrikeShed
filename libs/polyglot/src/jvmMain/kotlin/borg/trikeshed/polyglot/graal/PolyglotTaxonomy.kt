package borg.trikeshed.polyglot.graal

import borg.trikeshed.lib.Series

/**
 * PointcutFacet — taxonomic classification for pointcut interception sites.
 * Local replacement for org.xvm.cursor.PointcutFacet
 */
sealed class PointcutFacet {
    object MethodEntry : PointcutFacet()
    object MethodExit : PointcutFacet()
    object FieldRead : PointcutFacet()
    object FieldWrite : PointcutFacet()
    object Allocation : PointcutFacet()
    object Unfaceted : PointcutFacet()
}

/**
 * PolyglotFacet represents different language features for pointcuts.
 */
sealed class PolyglotFacet : PointcutFacet() {
    object PolyglotFunction : PolyglotFacet()
    object PolyglotVariable : PolyglotFacet()
    object PolyglotBlock : PolyglotFacet()
    object Unfaceted : PolyglotFacet()
}

/**
 * PolyglotCoordinateRow stores metadata about intercepted executions.
 */
data class PolyglotCoordinateRow(
    val languageId: String,
    val symbolName: String,
    val sourceLocation: String,
    val poolId: Int,
    val facet: PolyglotFacet = PolyglotFacet.Unfaceted
)

/**
 * Simple mutable series backed by ArrayList for JVM use.
 */
class SimpleSeriesBuffer<T> : Series<T> {
    private val list = mutableListOf<T>()
    override val a: Int get() = list.size
    override val b: (Int) -> T get() = { list[it] }
    fun add(item: T) { list.add(item) }
}

/**
 * PolyglotTaxonomy registers these rows into the broader TrikeShed taxonomic structure.
 */
class PolyglotTaxonomy {
    var rows: SimpleSeriesBuffer<PolyglotCoordinateRow> = SimpleSeriesBuffer()

    val size: Int get() = rows.a

    fun register(row: PolyglotCoordinateRow) {
        rows.add(row)
    }

    fun rowAt(index: Int): PolyglotCoordinateRow = rows.b(index)

    fun toSeries(): SimpleSeriesBuffer<PolyglotCoordinateRow> = rows
}