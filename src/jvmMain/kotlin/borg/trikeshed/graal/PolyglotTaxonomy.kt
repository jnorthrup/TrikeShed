package borg.trikeshed.graal

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series

/**
 * PolyglotFacet represents different language features for pointcuts.
 */
sealed class PolyglotFacet(
    override val a: String = "PolyglotFacet",
    override val b: String = "",
) : Join<String, String> {
    object PolyglotFunction : PolyglotFacet(b = "function")
    object PolyglotVariable : PolyglotFacet(b = "variable")
    object PolyglotBlock : PolyglotFacet(b = "block")
    object Unfaceted : PolyglotFacet(b = "unfaceted")
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
 * Simple series implementation for PolyglotTaxonomy.
 * Series<T> = Join<Int, (Int) -> T>
 */
class SimpleSeries<T> : Series<T> {
    private val items = mutableListOf<T>()
    override val a: Int get() = items.size
    override val b: (Int) -> T get() = { items[it] }
    fun add(item: T) { items.add(item) }
}

/**
 * PolyglotTaxonomy registers these rows into the broader TrikeShed taxonomic structure.
 */
class PolyglotTaxonomy {
    var rows: SimpleSeries<PolyglotCoordinateRow> = SimpleSeries()

    val size: Int get() = rows.a

    fun register(row: PolyglotCoordinateRow) {
        rows.add(row)
    }

    fun rowAt(index: Int): PolyglotCoordinateRow = rows.b(index)

    fun getRows(): Series<PolyglotCoordinateRow> = rows
}
